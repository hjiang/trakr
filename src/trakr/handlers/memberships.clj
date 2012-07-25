(ns trakr.handlers.memberships
  (:use [trakr.db.project :only [fetch-project]]
        clojure.contrib.trace
        [onycloud.handlers.util :only [when-has-permission]]
        [onycloud.db.user :only [fetch-user fetch-users-by-ids
                             shortname->id]]
        [onycloud.middleware :only [*authenticated-user*]])
  (:require [trakr.db.membership :as db]))

(defn list-memberships [req]
  (let [project-name (-> req :params :project-name)
        project (fetch-project project-name)]
    (when-has-permission
     (and project (db/can-read-project? project-name))
     (db/fetch-memberships (:id project)))))

(defn add-membership*
  [project-id user role]
  (if (db/fetch-membership project-id (str (:_id user)))
    {:status 400
     :body {:message "The user has already been added."}}
    (let [membership (db/insert-membership {:project_id project-id
                                            :user_id (str (:_id user))
                                            :role role})]
      (assoc membership
        :user_email (:email user)
        :user_shortname (:shortname user)))))

(defn add-membership [req]
  (when-has-permission
   (db/can-write-project? (-> req :params :project-name))
   (let [membership (req :json-body)
         project-id (-> req :params :project-name fetch-project :id)
         user (fetch-user {:shortname (:user_shortname membership)})
         role (:role  membership "member")]
     (if user
       (add-membership* project-id user role)
       {:status 400
        :body {:message "The user does not exist."}}))))

(defn- last-admin? [project membership-id]
  (let [admins (db/fetch-admins (:id project))]
    (and (= (count admins) 1)
         (= (-> admins first :id)
            (Integer/parseInt membership-id)))))

(defn delete-membership [req]
  (let [{:keys [project-name membership-id]} (req :params)
        project (fetch-project project-name)
        membership  (db/fetch-membership (:id project)
                                         (str (:_id *authenticated-user*)))]
    (when-has-permission
     (db/can-write-project? project-name)
     (if (last-admin? project membership-id)
       {:status 400
        :body {:message "This is the last admin, you can't remove him/her."}}
       (db/delete-membership (Integer/parseInt membership-id))))))

(defn update-role [req]
  (let [{:keys [project-name membership-id]} (req :params)
        project (fetch-project project-name)
        role (-> req :json-body :role)
        membership (db/fetch-membership (:id project)
                                        (str (:_id *authenticated-user*)))]
    (when-has-permission
     (db/can-write-project? project-name)
     (if (and (not= "admin" role)       ; update role to non-admin
              (last-admin? project membership-id))
       {:status 400
        :body {:message "Opps, you are the last admin"}}
       (db/update-membership {:id (Integer/parseInt membership-id)
                              :role role})))))

(defn- email-privacy [email]
  (let [[whole name domain] (re-find #"(.+)@(.+)" email)
        length (count name)]
    (apply str (concat
                (take (/ length 2) name)
                (repeat 2 \.)
                "@"
                domain))))

(defn- prepare-for-auto-complete [user]
  (let [email (:email user)
        shortname (:shortname user)]
    {:label (str shortname " <" (email-privacy email) ">")
     :value shortname}))

(defn auto-complete-user-source [req]
  (let [term (-> req :params :term)
        exact-match (fetch-user
                     {:$or [{:email term} {:shortname term}]})
        user-id-str (-> *authenticated-user* :_id str)]
    (if exact-match
      (list (prepare-for-auto-complete exact-match))
      (let [matching-user-ids (filter #(not= user-id-str %)
                                      (db/fetch-related-people user-id-str))
            matching-users (fetch-users-by-ids matching-user-ids)]
        (map prepare-for-auto-complete
             (filter #(or (.contains (:email %) term)
                          (.contains (:shortname %) term))
                     matching-users))))))
