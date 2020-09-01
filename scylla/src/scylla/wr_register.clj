(ns scylla.wr-register
  "This test performs transactional writes and reads to a set of registers, each stored in a distinct row containing a single int value."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info]]
            [jepsen [client :as client]
                    [checker :as checker]
                    [generator :as gen]
                    [util :as util]]
            [jepsen.tests.cycle.wr :as wr]
            [scylla [client :as c]]
            [qbits [alia :as a]
                   [hayt :as h]]))

(defn table-for
  "What table should we use for this key?"
  [test k]
  (str "registers"))

(defn all-tables
  "All tables for a test."
  [test]
  (mapv (partial table-for test) [0]))

(defn maybe-long
  "Coerces non-null values to longs; nil values remain nil."
  [x]
  (when x (long x)))

(defn write-batch!
  "Takes a test, a session, and a write-only txn. Performs the txn in a batch,
  batch, returning the resulting txn."
  [test session txn]
  (let [queries (map (fn [[f k v]]
                       (merge (h/update (table-for test k)
                                        (h/set-columns {:value v})
                                        (h/where [[= :part 0]
                                                  [= :id k]]))
                              (when (:lwt test)
                                ; This trivial IF always returns true.
                                (h/only-if [[= :lwt_trivial nil]]))))
                     txn)
        ; _ (info :queries queries)
        results (a/execute session (h/batch (apply h/queries queries))
                           (c/write-opts test))]
    (c/assert-applied results)
    ; Batch results make no sense so we... just ignore them. Wooo!)
    txn))

(defn read-many
  "Takes a test, a session, and a read-only txn. Performs the read as a single
  CQL select, returning the resulting txn."
  [test session txn]
  (let [ks      (distinct (map second txn))
        tables  (distinct (map (partial table-for test) ks))
        _       (assert (= 1 (count tables)))
        table   (first tables)
        results (a/execute session
                           (h/select table
                                     (h/where [[= :part 0]
                                               [:in :id ks]]))
                           (merge {:consistency :serial}
                                  (c/read-opts test)))
        values  (into {} (map (juxt :id (comp maybe-long :value)) results))]
    (mapv (fn [[f k v]] [f k (get values k)]) txn)))

(defn single-write!
  "Takes a test, session, and a transaction with a single write mop. Performs
  the write via a CQL conditional update."
  [test session txn]
  (let [[f k v] (first txn)]
    (c/assert-applied
      (a/execute session
                 (merge (h/update (table-for test k)
                                  (h/set-columns {:value v})
                                  (h/where [[= :part 0]
                                            [= :id k]]))
                        (when (:lwt test)
                          (h/only-if [[= :lwt_trivial nil]])))
                 (c/write-opts test))))
  txn)

(defn single-read
  "Takes a test, session, and a transaction with a single read mop. performs a
  single CQL select by primary key, and returns the completed txn."
  [test session [[f k v]]]
  [[f k (->> (a/execute session
                        (h/select (table-for test k)
                                  (h/where [[= :part 0]
                                            [= :id   k]]))
                        (merge {:consistency :serial}
                               (c/read-opts test)))
             first
             :value
             maybe-long)]])

(defn write-only?
  "Is this txn write-only?"
  [txn]
  (every? (comp #{:w} first) txn))

(defn read-only?
  "Is this txn read-only?"
  [txn]
  (every? (comp #{:r} first) txn))

(defn apply-txn!
  "Takes a test, a session, and a txn. Performs the txn, returning the
  completed txn."
  [test session txn]
  (if (= 1 (count txn))
    (cond (read-only?  txn) (single-read   test session txn)
          (write-only? txn) (single-write! test session txn)
          true              (assert false "what even is this"))
    (cond (read-only? txn)  (read-many     test session txn)
          (write-only? txn) (write-batch!  test session txn)
          true              (assert false "what even is this"))))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (c/open test node)))

  (setup! [this test]
    (let [s (:session conn)]
      (c/retry-each
        (a/execute s (h/create-keyspace
                       :jepsen_keyspace
                       (h/if-exists false)
                       (h/with {:replication {:class :SimpleStrategy
                                              :replication_factor 3}})))
        (a/execute s (h/use-keyspace :jepsen_keyspace))
        (doseq [t (all-tables test)]
          (a/execute s (h/create-table
                         t
                         (h/if-exists false)
                         (h/column-definitions {:part         :int
                                                :id           :int
                                                ; We can't do LWT without SOME
                                                ; kind of IF statement (why?),
                                                ; so we leave a trivial null
                                                ; column here.
                                                :lwt_trivial    :int
                                                :value        :int
                                                :primary-key  [:part :id]})
                         (h/with {:compaction {:class (:compaction-strategy test)}})))))))

  (invoke! [this test op]
    (let [s (:session conn)]
      (c/with-errors op #{}
        (a/execute s (h/use-keyspace :jepsen_keyspace))
        (assoc op
               :type  :ok
               :value (apply-txn! test s (:value op))))))

  (close! [this test]
    (c/close! conn))

  (teardown! [this test])

  client/Reusable
  (reusable? [_ _] true))

(defn workload
  "See options for jepsen.tests.append/test"
  [opts]
  {:client    (Client. nil)
   :generator (gen/filter (fn [op]
                            (let [txn (:value op)]
                              (or (read-only? txn)
                                  (write-only? txn))))
                          (wr/gen opts))
   :checker   (wr/checker
                (merge
                  (if (and (:lwt opts)
                           (= :serial (:read-consistency opts :serial)))
                    ; If all updates use LWT and all reads use SERIAL, we
                    ; expect strict-1SR.
                    {:linearizable-keys? true
                     :consistency-models [:strict-serializable]}

                    ; Otherwise, Scylla docs claim UPDATE and BATCH are
                    ; "performed in isolation" on single partitions; we
                    ; should observe serializability--but we can't rely on
                    ; sequential or linearizable key constraints.
                    {:consistency-models [:serializable]})
                  opts))})

; Patch a bug in Elle real quick--I've got it all torn open and don't want to
; bump versions right now.
(ns elle.rw-register)

(defn cyclic-version-cases
  "Given a map of version graphs, returns a sequence (or nil) of cycles in that
  graph."
  [version-graphs]
  (seq
    (reduce (fn [cases [k version-graph]]
              (let [sccs (g/strongly-connected-components version-graph)]
                (->> sccs
                     (sort-by (partial reduce (fn option-compare [a b]
                                                (cond (nil? a) b
                                                      (nil? b) a
                                                      true     (min a b)))))
                     (map (fn [scc]
                            {:key k
                             :scc scc}))
                     (into cases))))
            []
            version-graphs)))
