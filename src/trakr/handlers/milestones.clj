(ns trakr.handlers.milestones
  (:use clojure.contrib.trace
        [onycloud.middleware :only [*authenticated-user*]]
        [onycloud.handlers.util :only [when-has-permission]]
        [trakr.db.project :only [get-project-id-by-name project-archived?]]
        [trakr.db.membership :only [can-read-project? can-write-project?]])
  (:require [trakr.db.milestone :as db]
            [trakr.db.issue :as issuedb]))

(defn list-milestones [req]
  (let [project-name (-> req :params :project-name)
        show-all? (-> req :params :show_all)]
    (db/list-milestones project-name show-all?)))

(defn create-milestone [req]
  (let [project-name (-> req :params :project-name)
        project-id (get-project-id-by-name project-name)
        raw (:json-body req)
        milestone-name (:name raw)
        milestone-exists? (db/get-milestone-id-by-name project-name
                                                       milestone-name)]
    (when-has-permission
     (can-write-project? project-name)
     (cond
      (project-archived? project-name)
      {:status 400
       :body {:message "Can't create milestone in archived project."}}
      (not milestone-exists?)
      (db/create-milestone (assoc raw
                             :creator_name (:shortname *authenticated-user*)
                             :project_id project-id))
      :else {:status 431 :body {:message "Milestone already exists."}}))))

(defn update-milestone [req]
  (let [milestone-id (Integer/parseInt (-> req :params :milestone-id))
        project-name (-> req :params :project-name)
        milestone-exists? (db/get-milestone-name-by-id milestone-id)
        new-milestone (assoc (:json-body req)
                        :id milestone-id)
        new-milestone-exists? (db/get-milestone-id-by-name
                               project-name
                               (:name new-milestone))]
    (when-has-permission
     (can-write-project? project-name)
     (cond
      (project-archived? project-name)
      {:status 400
       :body {:message "Can't update milestone in archived project."}}
      (not milestone-exists?) {:status 404}
      ;; add fn valid-milestone-name ?
      (empty? (:name new-milestone)) {:status 430
                                      :body {:message
                                             "Invalid milestone name."}}
      new-milestone-exists? {:status 431
                             :body {:message "Milestone already exists."}}
      :else (db/update-milestone new-milestone)))))

(defn delete-milestone [req]
  (let [milestone-id (Integer/parseInt (-> req :params :milestone-id))
        project-name (-> req :params :project-name)
        milestone-exists? (db/get-milestone-name-by-id milestone-id)]
    (when-has-permission
     (can-write-project? project-name)
     (if (project-archived? project-name)
       {:status 400
        :body {:message "Can't delete milestone in archived project."}}
       (db/delete-milestone milestone-id)))))

(defn fetch-milestone [req]
  (let [milestone-id (Integer/parseInt (-> req :params :milestone-id))
        project-name (-> req :params :project-name)]
    (when-has-permission
     (can-read-project? project-name)
     (assoc (db/fetch-milestone project-name milestone-id)
       :stats (issuedb/fetch-stats-by-status project-name milestone-id)))))
