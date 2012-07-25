(ns trakr.db.project
  (:use [clojure.contrib.strint :only [<<]]
        [onycloud.db.util :only [exec-commands exec-query insert-sql-params
                              update-sql-params]])
  (:require [onycloud.util.tag :as tag]))

;;; not used, exists as reference
(defrecord Project [id name desc date creator_name max_issue_id])

(defn get-project-id-by-name [project-name]
  (-> (exec-query ["SELECT id FROM projects WHERE name = ?" project-name])
      first :id))

(defn fetch-projects-by-membership [user-id & [active-only]]
  (exec-query [(str "SELECT projects.*, memberships.role "
                    "FROM projects, memberships "
                    "WHERE projects.id = memberships.project_id "
                    "AND memberships.user_id = ? "
                    (if active-only "AND projects.archived = false"))
               (str user-id)]))

(defn project-archived? [name]
  (-> (exec-query ["SELECT archived FROM projects WHERE name = ?" name])
      first :archived))

(defn project-public? [name]
  (-> (exec-query ["SELECT public FROM projects WHERE name = ?" name])
      first :public))

(defn fetch-project [project-name]
  (first
   (exec-query ["SELECT * FROM projects WHERE name = ?" project-name])))

(defn destroy-project! [name]
  (exec-commands (<< "DELETE FROM projects WHERE name = '~{name}'")))

(defn fetch-project-by-id [project-id]
  (first
   (exec-query ["SELECT * FROM projects WHERE id = ?" project-id])))

(defn fetch-project-status [project-name & [assignee-name]]
  (let [stats (exec-query
               [(str "SELECT status, COUNT(local_id) as count "
                     "FROM issues "
                     "WHERE project_name = ? "
                     "GROUP BY status")
                project-name])]
    (into {} (map #(vector (:status %) (:count %))
                  (vec stats)))))

(defn insert-project [proj]
  (first
   (exec-query (insert-sql-params :projects proj))))

(defn update-project [data]
  (let [good-data (select-keys data [:name :desc :archived :public])]
    (first
     (exec-query (update-sql-params :projects :name good-data)))))

(defn get-next-local-id [project-name]
  (:max_issue_id
   (first
    (exec-query
     [(str "UPDATE projects SET max_issue_id = max_issue_id + 1 "
           "WHERE name = ? "
           "RETURNING max_issue_id")
      project-name]))))

(defn archive-project [project-name]
  (first
   (exec-query
    ["UPDATE projects SET archived = true WHERE name = ? RETURNING *"
     project-name])))

(defn archive-all-projects-by-creator [username]
  (exec-query
   ["UPDATE projects SET archived = true WHERE creator_name = ? RETURNING *"
    username]))

(defn fetch-tags-by-project [project-name]
  (tag/fetch-tags {:extra_data.project_name project-name}))
