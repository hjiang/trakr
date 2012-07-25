(ns trakr.db.milestone
  (:use [onycloud.db.util :only [exec-query insert-sql-params update-sql-params]]
        [clojure.java.jdbc :only [transaction]]))

(defn get-milestone-id-by-name [proj-name milestone-name]
  (-> (exec-query [(str "SELECT milestones.id FROM milestones, projects "
                        "WHERE milestones.project_id = projects.id "
                        "AND milestones.name = ? "
                        "AND projects.name = ?")
                   milestone-name proj-name])
      first :id))

(defn get-milestone-name-by-id [milestone-id]
  (-> (exec-query
       ["SELECT name FROM milestones WHERE milestones.id = ?"
        milestone-id])
      first :name))

(defn fetch-milestone [proj-name milestone-id]
  (first (exec-query
          [(str "SELECT milestones.* FROM milestones, projects "
                "WHERE milestones.project_id = projects.id "
                "AND milestones.id = ? "
                "AND projects.name = ?")
           milestone-id proj-name])))

(defn create-milestone [milestone]
  (first (exec-query (insert-sql-params :milestones milestone))))

(defn list-milestones
  ([project-name] (list-milestones project-name false))
  ([project-name show-all?]
     (exec-query [(str
                   "SELECT DISTINCT(milestones.*) "
                   "FROM milestones, projects "
                   "WHERE milestones.project_id = projects.id "
                   "AND projects.name = ? "
                   (when-not show-all?
                     (let [unclosed (str
                                 "(SELECT COUNT(*) FROM issues "
                                 "WHERE issues.milestone_id = milestones.id "
                                 "AND issues.status < 4)")
                           total (str
                                  "(SELECT COUNT(*) FROM issues "
                                  "WHERE issues.milestone_id = milestones.id)")]
                       (str "AND (" unclosed " > 0 "
                            "OR " total " = 0)"))))
                  project-name])))

(defn update-milestone [data]
  (let [good-data (select-keys data [:id :name])]
    (first
     (exec-query (update-sql-params :milestones :id good-data)))))

(defn delete-milestone [milestone-id]
  (do (exec-query [(str "UPDATE issues SET milestone_id = null "
                        "WHERE milestone_id = ? RETURNING *")
                   milestone-id])
      (first (exec-query ["DELETE FROM milestones WHERE id = ? RETURNING *"
                          milestone-id]))))
