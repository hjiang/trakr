(ns trakr.plugins.logging
  (:use clojure.contrib.trace
        [onycloud.db.util :only [to-object-id]]
        [somnium.congomongo :only [fetch insert! update! destroy!
                                    fetch-count]])
  (:import java.util.Date))

(defn log-user-activity [what who when body]
  (insert! :oc_user_activity
           {:event what
            :user who
            :timestamp when
            :data body}))

(defn log-issue-atime [user-id timestamp issue-id data]
  "Logging issue last access time."
  (update! :oc_issue_atime {:user_id user-id :issue_id issue-id}
           {:user_id user-id
            :timestamp timestamp
            :issue_id issue-id
            :data data}))

(defn log-listing-issues [user project-name offset]
  (let [data {:project_name project-name :offset offset}
        timestamp (java.util.Date.)]
    (log-user-activity "listing-issues" user timestamp data)))

(defn get-access-times-in-last-72hours [user-id project-name]
  (let [three-days-ago (.getTime (doto (java.util.Calendar/getInstance)
                                   (.add java.util.Calendar/DATE -3)))]
    (fetch-count :oc_user_activity
                 :where {:user._id (to-object-id user-id)
                         :timestamp {:$gte three-days-ago}
                         :data.project_name project-name})))

(defn log-visiting-issue [user issue]
  (let [timestamp (java.util.Date.)
        user-id (:_id user)
        issue-id (:id issue)
        data {:issue_id issue-id}
        data-recent (select-keys issue [:project_name :title :local_id])]
    (log-user-activity "visiting-issue" user timestamp data)
    (log-issue-atime user-id timestamp issue-id data-recent)))

(defn fetch-recent-issues [user-id limit]
  (fetch :oc_issue_atime
         :where {:user_id (to-object-id user-id)}
         :limit limit
         :sort {:timestamp -1}))
