(ns trakr.db.blockage
  (:use [onycloud.db.util :only [exec-query
                              insert-sql-params update-sql-params]]))
;;; fetch-children
(defn fetch-blocking-issues [issue-id]
  "return issues that can't continue until `issue-id` is finished"
  (exec-query ["SELECT * FROM blockages WHERE blocking_issue_id = ?"
               issue-id]))

(defn fetch-blocked-by [issue-id]
  "return issues that have to be finished before `issue-id` can be finished"
  (exec-query ["SELECT * FROM blockages WHERE blocked_issue_id = ?"
               issue-id]))

;;;blocking_issue_id, blocked_issue_id, copyed_from_id, added_ts
(defn insert-blockage [map]
  (first
   (exec-query (insert-sql-params :blockages map))))

(defn can-block? [issue-id1 issue-id2]
  (or (= issue-id1 issue-id2)
      (>
       (:count
        (first (exec-query ["SELECT count (*) FROM blockages WHERE
                           (blocking_issue_id = ?  AND blocked_issue_id = ?) OR
                           (blocked_issue_id = ? AND blocking_issue_id = ?)"
                            issue-id1 issue-id2 issue-id1 issue-id2]))) 0)))

(defn delete-blockage
  "Delete all blockages, including old copyed_from blockages."
  [blocked-id blocking-id]
  (first
   (exec-query ["DELETE FROM blockages WHERE blocked_issue_id = ? AND
                 blocking_issue_id = ? RETURNING *"
                blocked-id blocking-id])))
