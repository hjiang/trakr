(ns trakr.db.user
  (:use [somnium.congomongo :only [fetch]]
        [onycloud.db.user :only [fetch-user]]
        [onycloud.db.util :only [exec-query return-one insert-sql-params]]
        [trakr.config :only [VIP]]))

(defn new-to-trakr? [& {:keys [user-id user]}]
  (let [user (or user (fetch-user {:_id user-id}
                                  :only [:trakr_tutorial_inserted]))]
    (not (:trakr_tutorial_inserted user))))

(defn fetch-all-chargeable-trakr-usernames []
  (remove #(or (nil? %) (contains? VIP %))
          (map :shortname
               (fetch :users
                      :only [:shortname]
                      :where {:trakr_tutorial_inserted true}))))

(defn fetch-project-watching-by-id [id]
  (return-one [(str "SELECT * FROM project_watchings "
                    "WHERE id = ?")
               id]))

(defn fetch-watched-projects-by-user-id [user-id]
  (exec-query
   [(str "SELECT projects.*, project_watchings.id AS watching_id "
         "FROM projects, project_watchings "
         "WHERE project_watchings.project_id = projects.id "
         "AND project_watchings.user_id = ? "
         "AND projects.archived = false")
    user-id]))

(defn add-project-watching [user-id project-id]
  (when-let [watching (return-one (insert-sql-params :project_watchings
                                                     {:user_id user-id
                                                      :project_id project-id}))]
    (return-one
     [(str "SELECT projects.*, project_watchings.id AS watching_id "
           "FROM projects, project_watchings "
           "WHERE project_watchings.project_id = projects.id "
           "AND project_watchings.id = ?")
      (:id watching)])))

(defn delete-project-watching [id]
  (return-one ["DELETE FROM project_watchings WHERE id = ? RETURNING *" id]))
