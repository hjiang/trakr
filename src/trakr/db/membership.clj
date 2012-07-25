(ns trakr.db.membership
  (:use [onycloud.db.util :only [exec-query insert-sql-params update-sql-params
                              return-one]]
        [onycloud.db.user :only [fetch-users-by-ids]]
        [onycloud.middleware :only [*authenticated-user*]]
        [somnium.congomongo :only [destroy! fetch-one fetch mass-insert!]]
        [trakr.db.project :only [project-public? get-project-id-by-name]]
        [clojure.set :only [rename-keys]]
        [trakr.db.issue :only [fetch-issue]]))

;; A membership has the following fields:
;; * project_id
;; * user_id
;; * role: Either "admin" or "member". We might add more later.

;; (defn- fetch-memberships-from-cache [project-id]
;;   (fetch :trakr_project_memberships :where {:project_id project-id}))

;; (defn- fetch-admins-from-cache [project-id]
;;   (fetch :trakr_project_memberships :where {:project_id project-id
;;                                             :role "admin"}))

;; (defn- fetch-membership-from-cache [project-id user-id]
;;   (fetch-one :trakr_project_memberships :where {:project_id project-id
;;                                                 :user_id user-id}))

;; (defn- invalidate-memberships-cache [project-id]
;;   (destroy! :trakr_project_memberships {:project_id project-id}))

(defn fetch-memberships [project-id]
  (let [memberships (exec-query [(str "SELECT * FROM memberships "
                                      "WHERE project_id = ?")
                                 project-id])
        users (map #(rename-keys % {:shortname :user_shortname
                                    :email :user_email})
                   (fetch-users-by-ids (map :user_id memberships)))
        memberships-full (for [u users, m memberships
                               :when (= (:user_id m) (str (:_id u)))]
                           (merge m u))]
    (mass-insert! :trakr_project_memberships memberships-full)
    memberships-full))

(defn count-members-by-project-creator [start-date username]
  (:count (return-one [(str "SELECT COUNT(DISTINCT memberships.user_id)"
                            "FROM projects, memberships "
                            "WHERE projects.id = memberships.project_id "
                            "AND projects.archived = false "
                            "AND projects.public = false "
                            "AND projects.date > ? "
                            "AND projects.creator_name = ? ")
                       start-date username])))

(defn fetch-admins [project-id]
  (exec-query ["SELECT * FROM memberships WHERE project_id = ?
                 AND role = ?"
               project-id "admin"]))

(defn insert-membership [membership]
  ;; (invalidate-memberships-cache (:project_id membership))
  (first (exec-query (insert-sql-params :memberships membership))))

(defn delete-membership [membership-id]
  (let [deleted (return-one ["DELETE FROM memberships WHERE id = ? RETURNING *"
                             membership-id])]
    ;; (invalidate-memberships-cache (:project_id deleted))
    deleted))

(defn update-membership [new-data]
  (let [updated (first (exec-query (update-sql-params :memberships new-data)))]
    ;; (invalidate-memberships-cache (:project_id updated))
    updated))

(defn fetch-membership [project-id user-id]
  (first (exec-query ["SELECT
                             *
                           FROM memberships
                           WHERE project_id = ? AND user_id = ?"
                      project-id user-id])))

(defn user-in-project? [project-name user-id]
  (first
   (exec-query ["SELECT
                       memberships.id
                 FROM memberships
                       JOIN projects ON memberships.project_id = projects.id
                 WHERE
                       memberships.user_id = ? AND projects.name = ?"
                user-id project-name])))

(defn fetch-related-people [user-id]
  (map :user_id
       (exec-query
        ["SELECT DISTINCT user_id FROM memberships
          WHERE project_id  IN
         (SELECT project_id FROM memberships WHERE user_id = ?)"
         user-id])))

(defn can-read-project?
  ([project-name] (can-read-project? (:_id *authenticated-user*)
                                     project-name))
  ([user-id project-name]
     (or (project-public? project-name)
         (user-in-project? project-name (str user-id)))))

(defn can-add-comment-to-project?
  ([project-name] (can-add-comment-to-project? (:_id *authenticated-user*)
                                               project-name))
  ([user-id project-name]
     (and user-id
          (or (project-public? project-name)
              (user-in-project? project-name (str user-id))))))

(defn can-write-to-project?
  ([project-name] (can-write-to-project? (:_id *authenticated-user*)
                                         project-name))
  ([user-id project-name]
     (user-in-project? project-name (str user-id))))

(defn can-add-issue-to-project?
  ([project-name] (can-add-issue-to-project? (:_id *authenticated-user*)
                                             project-name))
  ([user-id project-name]
     (and user-id
          (or (project-public? project-name)
              (user-in-project? project-name (str user-id))))))

(defn can-update-issue?
  ([project-name local-id] (can-update-issue? (:_id *authenticated-user*)
                                              project-name
                                              local-id))
  ([user-id project-name local-id]
     (or (user-in-project? project-name (str user-id))
         (and (project-public? project-name)
              (= (:shortname *authenticated-user*)
                 (:creator_name (fetch-issue project-name local-id)))))))

(defn can-write-project?
  ([project-name] (can-write-project? (:_id *authenticated-user*)
                                      project-name))
  ([user-id project-name]
     (let [project-id (get-project-id-by-name project-name)]
       (= "admin" (:role (fetch-membership project-id (str user-id)))))))
