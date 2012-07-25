(ns trakr.mail
  (:use [clojure.contrib.strint :only [<<]]
        [onycloud.mail :only [send-mail]]))

(defn- issue-link [project-name local-id]
  (<<
   "https://trakrapp.com/trakr/a#projects/~{project-name}/issues/~{local-id}"))

(defn- issue-mail-subject [project-name local-id issue-title]
  (<< "[~{project-name}] #~{local-id} ~{issue-title}"))

(defn- issue-mail-body [project-name local-id message]
  (str message "\n" (issue-link project-name local-id)))

(defn send-issue-update-mail [& {:keys [project-name local-id issue-title
                                        message recipients]}]
  (send-mail :recipients recipients
             :from "noreply@trakrapp.com"
             :subject (issue-mail-subject project-name local-id issue-title)
             :body (issue-mail-body project-name local-id message)))
