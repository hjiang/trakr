(ns trakr.handlers.users
  (:require [trakr.db.user :as db])
  (:use [onycloud.db.user :only [fetch-user update-shortname shortname->id
                                 user-exists? update-user-info]]
        [sandbar.stateful-session :only [session-get session-put!]]
        (trakr.db [project :only [fetch-project-by-id
                                  fetch-projects-by-membership]]
                  [issue :only [fetch-updates]])
        [onycloud.db.util :only [to-object-id]]
        (onycloud [util :only [to-long]]
                  [validate :only [valid-email?]]
                  [middleware :only [*authenticated-user*]])))


(defn- stringfy-id [user]
  (assoc (dissoc user :_id)
    :id (str (:_id user))))

;; used when setting username
(defn update-user [req]
  (let [token (-> req :params :user-shortname)
        new-shortname (-> req :json-body :shortname)
        user (or (fetch-user {:_id (to-object-id token)})
                 (fetch-user {:shortname token}))]
    (cond (not user) {:status 404}
          (not new-shortname) {:status 430}
          (shortname->id new-shortname) {:status 431
                                         :body {:message "Short name already exists."}}
          :else (do (update-shortname (:_id user) new-shortname)
                    (session-put! :shortname new-shortname) ; TODO: remove
                    (session-put! :user (assoc (session-get :user)
                                          :shortname new-shortname))
                    (stringfy-id (fetch-user
                                  {:_id (:_id user)}
                                  :only [:email :name :shortname]))))))

(defn watched-updates [req]
  (let [user-name (-> req :params :user-shortname)
        since (when-let [ts (-> req :params :since to-long)]
                (java.util.Date. ts))
        project-names (if-let [project-name (-> req :params :project)]
                        [project-name]
                        (map :name (fetch-projects-by-membership
                                    (shortname->id user-name))))
        mongo-project-clause {:project_name {:$in project-names}}
        ;; used in 'dashboard -> (your actions)'
        mongo-user-clause (if-let [username (-> req :params :user)]
                            {:user.shortname username}
                            {})
        mongo-since-clause (if since {:date {:$gt since}} {})
        mongo-where (merge mongo-project-clause
                           mongo-user-clause
                           mongo-since-clause)]
    (fetch-updates mongo-where)))

(defn viewer-info [req]
  (if *authenticated-user*
    (let [user (fetch-user {:_id (:_id *authenticated-user*)}
                           :only [:email :name :shortname
                                  :trakr_tutorial_inserted])]
      (assoc (stringfy-id user)
        :is_new (db/new-to-trakr? :user user)))
    ;; TODO: this can be put into client side to reduce load on server
    {"id" "000000000000000000000000"
     "email" "guest@onycloud.com"
     "name" "Guest don't have a full name"
     "shortname" "guest"}))

(defn change-settings [req]
  (let [user (fetch-user {:_id (:_id *authenticated-user*)})
        data (select-keys (req :json-body) [:email :password])]
    (cond
     (not user) {:status 404}
     (not (valid-email? (:email data)))
     {:status 400
      :body {:message "The email address is invalid."}}
     (and (not= (:email data) (:email user))
          (user-exists? (:email data)))
     {:status 431
      :body {:message
             "This email address is already associated with another user."}}
     :else
     (let [update (update-user-info user data)]
       (when update
         {:status 200
          :body {:message "Settings saved."}})))))

(defn fetch-watched-projects [req]
  (let [auth-user-id (:_id *authenticated-user*)
        user [fetch-user {:_id auth-user-id}]]
    (cond
     (not user) {:status 404}
     :else (db/fetch-watched-projects-by-user-id (str auth-user-id)))))

(defn add-project-watching [req]
  (let [user (fetch-user {:_id (:_id *authenticated-user*)})
        project-id (-> req :json-body :project_id to-long)
        project (fetch-project-by-id project-id)
        project-public? (:public project)
        project-archived? (:archived project)]
    (cond
     (not user) {:status 404}
     (not project-public?)
     {:status 400
      :body {:message "Can't watch private project."}}
     project-archived?
     {:status 400
      :body {:message "Can't watch archived project."}}
     :else (db/add-project-watching (str (:_id user)) project-id))))

(defn delete-project-watching [req]
  (let [user (fetch-user {:_id (:_id *authenticated-user*)})
        id (to-long (-> req :params :id))]
    (cond
     (not user) {:status 404}
     (not= (str (:_id user))
           (:user_id (db/fetch-project-watching-by-id id)))
     {:status 403}
     :else (db/delete-project-watching id))))
