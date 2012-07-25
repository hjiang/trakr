(ns trakr.plugins.issues
  (:use [clojure.contrib.trace :only [trace]]
        [onycloud.middleware :only [*authenticated-user*]]
        [onycloud.db.user :only [fetch-users-by-shortnames fetch-users-by-ids]]
        [trakr.db.issue :only [post-update-issue]]
        [trakr.db.membership :only [fetch-memberships]]
        [trakr.mail :only [send-issue-update-mail]]))

(defn- get-project-mail-recipients [project-id]
  (let [user-ids (map :user_id (fetch-memberships project-id))]
    (map :email (fetch-users-by-ids user-ids))))

(defn- get-mail-recipients [issue]
  (let [shortnames (filter #(not (empty? %))
                           (vals (select-keys issue [:creator_name
                                                     :assignee_name])))
        recipients (fetch-users-by-shortnames shortnames)]
    (map :email recipients)))

(defn send-issue-update-email [& {:keys [updated original changes]}]
  (send-issue-update-mail
   :project-name (:project_name updated)
   :local-id (:local_id updated)
   :issue-title (:title updated)
   :recipients (get-mail-recipients updated)
   :message (str
             (:shortname *authenticated-user*) " updated this issue:\n"
             (apply str (map (fn [c] (str (name (c 0)) ": " (c 1) " -> " (c 2)
                                         "\n"))
                             changes)))))

(defn send-issue-creation-email [project issue]
  (send-issue-update-mail :project-name (:name project)
                          :local-id (:local_id issue)
                          :issue-title (:title issue)
                          :recipients (get-project-mail-recipients
                                       (:id project))
                          :message (str (:shortname *authenticated-user*)
                                        " created a new issue:\n"
                                        (:title issue))))

(defn send-comment-email [issue comment]
  (send-issue-update-mail :project-name (:project_name issue)
                          :local-id (:local_id issue)
                          :issue-title (:title issue)
                          :recipients (get-mail-recipients issue)
                          :message (str
                                    (:shortname *authenticated-user*)
                                    " posted a comment to this issue.\n"
                                    (:comment_text comment))))

