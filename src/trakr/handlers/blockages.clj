(ns trakr.handlers.blockages
  (:use clojure.contrib.trace
        [onycloud.middleware :only [*authenticated-user*]]
        [trakr.db.issue :only [fetch-issues]]
        [trakr.db.project :only [project-archived?]]
        [onycloud.util :only [to-int]]
        [trakr.db.membership :only [user-in-project? fetch-memberships]])
  (:require [trakr.db.blockage :as db]
            [trakr.db.issue :as issuedb]))

(defn- to-blockage-data
  "JOIN issues table, easy for javascript to display"
  [blockages]
  (let [issues (fetch-issues (map :blocking_issue_id blockages))]
    (for [i issues b blockages :when (= (:id i) (:blocking_issue_id b))]
      {:id (:id i)
       :local_id (:local_id i)
       :title (:title i)
       :closed (= (:status i) 4)        ; 4 is closed
       :copyed_from_id (:copyed_from_id b)
       :added_ts (:added_ts b)})))

(defn add-blockage* [blocking-issue-id blocked-issue-id]
  ;; refuse to add, if there is a relationship
  (if (db/can-block? blocked-issue-id blocking-issue-id)
    '()                                 ; return empty list
    (list (db/insert-blockage {:blocked_issue_id blocked-issue-id
                               :blocking_issue_id blocking-issue-id}))))

(defn delete-blockage [blocking-issue-id blocked-issue-id]
  (db/delete-blockage blocked-issue-id blocking-issue-id))

(defn- blockage-datas-ret
  "JOIN issues table, easy for javascript to display,
  to get blokced  data, access-key should be :blocking_issue_id,
  to get blocking data, access-key should be :blocked_issue_id."
  [blockages access-key]
  (let [issues (fetch-issues (map access-key blockages))]
    (for [i issues b blockages :when (= (:id i) (access-key b))]
      {:id (:id i)
       :local_id (:local_id i)
       :title (:title i)
       :closed (= (:status i) 4)        ; 4 is closed
       :copyed_from_id (:copyed_from_id b)
       :added_ts (:added_ts b)})))

(defn- blocking-datas-ret [blockages]
  (blockage-datas-ret blockages :blocked_issue_id))

(defn- blocked-datas-ret [blockages]
  (blockage-datas-ret blockages :blocking_issue_id))

(defn- blocking-data-ret [blockage]
  (first (blocking-datas-ret (list blockage))))

(defn- blocked-data-ret [blockage]
  (first (blocked-datas-ret (list blockage))))

(defn list-blocking-issues [issue-id]
  (blocking-datas-ret (db/fetch-blocking-issues issue-id)))

(defn list-blocked-by [issue-id]
  (blocked-datas-ret (db/fetch-blocked-by issue-id)))

;;; TODO perminssion
(defn delete-blocking [project-name blocking-local-id blocked-id]
  (let [blocking (issuedb/fetch-issue project-name
                                      (to-int blocking-local-id))]
    (blocking-data-ret
     (db/delete-blockage blocked-id (:id blocking)))))

;;; TODO perminssion
(defn delete-blocked [project-name blocked-local-id blocking-id]
  (let [blocked (issuedb/fetch-issue project-name
                                     (to-int blocked-local-id))]
    (blocked-data-ret
     (db/delete-blockage (:id blocked) blocking-id))))

;;; TODO permission
(defn add-blockage [project-name blocking-local-id blocked-local-id]
  (let [blocking (issuedb/fetch-issue project-name
                                      (to-int blocking-local-id))
        blocked (issuedb/fetch-issue project-name
                                     (to-int blocked-local-id))]
    (if (and (seq blocking) (seq blocked))
      (add-blockage* (:id blocking) (:id blocked)))))

;;; TODO permission
(defn add-blocking [project-name id1 id2]
  (blocking-datas-ret (add-blockage project-name id1 id2)))

;;; TODO permission
(defn add-blocked [project-name id1 id2]
  (blocked-datas-ret (add-blockage project-name id2 id1)))
