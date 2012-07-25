(ns trakr.handlers.projects
  (:use [clojure.contrib.strint :only [<<]]
        [onycloud.middleware :only [*authenticated-user*]]
        [onycloud.handlers.util :only [when-has-permission]]
        [trakr.db.membership :only [insert-membership]]
        [onycloud.plugins :only [defhook invoke-plugins]]
        [trakr.plugins.logging :only [get-access-times-in-last-72hours]]
        [sandbar.stateful-session :only [session-get]]
        [trakr.db.membership :only [can-write-to-project? can-write-project?
                                    can-read-project?]]
        [onycloud.db.user :only [shortname->email]]
        clojure.contrib.trace)
  (:require [trakr.db.project :as db]
            [clojure.string :as str]
            [trakr.import.pivotal :as pivotal]
            [trakr.import.github :as github]
            [onycloud.util.tag :as tag]))

(defhook pre-list-projects)

(defn list-projects [req]
  "list projects tracked by trakr"
  (let [user-id (str (:_id *authenticated-user*))
        active-only (not (-> req :params :show_all))]
    (do (invoke-plugins pre-list-projects)
        (let [projects (db/fetch-projects-by-membership user-id active-only)]
          (map #(assoc %
                  :stats (db/fetch-project-status (:name %))
                  :access_times (get-access-times-in-last-72hours user-id
                                                                  (:name %)))
               projects)))))

(defn- _create-project [project admin-name admin-id]
  (if (db/fetch-project (:name project))
    {:status 431
     :body {:message (<< "Project \"~(:name project)\" already exists.")}}
    (let [p (db/insert-project
             (assoc project
               :max_issue_id 0
               :creator_name admin-name))]
      (insert-membership {:user_id (str admin-id)
                          :project_id (:id p)
                          :role "admin"})
      (assoc p "role" "admin"))))

(defn create-project [req]
  "create a project that will be tracked by trakr"
  (let [project (:json-body req)]
    ;; validate the project value?
    (_create-project project
                     (:shortname *authenticated-user*)
                     (:_id *authenticated-user*))))

(defn update-project [req]
  "update a tracked project"
  (let [name (-> req :params :project-name)
        new-project (assoc (:json-body req)
                      :name name)]
    (when-has-permission
     (can-write-project? name)
     (db/update-project new-project))))

(defn get-project [req]
  (let [name (-> req :params :project-name)]
    (when-has-permission
     (can-read-project? name)
     (let [proj (db/fetch-project name)
           status (db/fetch-project-status name)]
       (assoc proj :stats status)))))

(defn archive-project [req]
  (let [name (-> req :params :project-name)]
    (when-has-permission
     (can-write-project? name)
     (db/archive-project name))))

(defn import-other-tracker [req]
  (let [project-name (-> req :params :project-name)
        target-project (db/fetch-project project-name)
        provider (-> req :json-body :provider str/trim)]
    (when-has-permission
     (can-write-to-project? project-name)
     (if (db/project-archived? project-name)
       {:status 400
        :body {:message "Can't import stuff to archived project."}}
       (case provider
         "pivotaltraker"
         (pivotal/import-proj (:json-body req)
                              target-project *authenticated-user*)
         "github"
         (github/import-proj (:json-body req)
                             target-project *authenticated-user*)
         :else
         {:message (str "Sorry, import from "
                        provider " is not supported")})))))

(defn list-tags-by-project [req]
  (let [name (-> req :params :project-name)]
    (when-has-permission
     (can-read-project? name)
     (map #(hash-map :id %) (db/fetch-tags-by-project name)))))
