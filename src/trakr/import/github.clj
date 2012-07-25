(ns trakr.import.github
  (:use [onycloud.util.http :only [http-get]]
        [clojure.contrib.strint :only [<<]]
        [clojure.tools.logging :only [error info]]
        [trakr.import.util :only [parse-date]]
        [clojure.data.json :only [read-json]])
  (:require (trakr.db [issue :as db]
                      [project :as projectdb]))
  (:import java.text.SimpleDateFormat
           java.sql.Timestamp))

(defn fetch-comments [username repo id]
  (let [url (str "http://github.com/api/v2/json/issues/comments/"
                 (<< "~{username}/~{repo}/~{id}"))
        resp (http-get url)]
    (if (= 200 (:status resp))
      (let [all (-> resp :body read-json :comments)]
        (info url "get" (count all) "comments")
        (map (fn [c]
               {:comment (:body c)
                :user (:user c)
                :timestamp (-> c :created_at parse-date)
                :gravatar_id (:gravatar_id c)}) all)))))

(defn- fetch-stories [username repo status]
  (let [url (str "http://github.com/api/v2/json/issues/list/"
                 (<< "~{username}/~{repo}/~{status}"))
        resp (http-get url)]
    (if (= 200 (:status resp))
      (let [stories (-> resp :body read-json :issues)]
        (info url "get" (count stories) "stories")
        (map #(let [comments (when (> (:comments %) 0)
                               (fetch-comments username repo (:number %)))]
                {:title (:title %)
                 :desc (:body %)
                 :tags (:labels %)
                 :comments comments
                 :github_id (:number %)
                 :status (if (= (:state %) "open") "new" "closed")
                 :date (-> % :created_at parse-date)
                 :last_update (-> % :updated_at parse-date)}) stories))
      (throw (Exception. (str "Error, github returned " (:status resp)))))))

(defn get-stories [username repo]
  (concat (fetch-stories username repo "open")
          (fetch-stories username repo "closed")))

(defn import-story [story project importer]
  (let [local-id (projectdb/get-next-local-id (:name project))
        issue (db/insert-issue {:local_id local-id
                                :title (:title story)
                                :desc (:desc story)
                                :status (:status story)
                                :type "bug"
                                :priority "medium"
                                :date (:date story)
                                :creator_name (:shortname importer)
                                :project_name (:name project)
                                :last_update (:last_update story)})]
    (doseq [tag (:tags story)]
      (db/add-tag issue tag))
    (doseq [c (:comments story)]
      (db/create-update (:id issue)
                        (:project_name issue)
                        (:local_id issue)
                        {:user {:shortname (:user c)
                                :gravatar_id (:gravatar_id c)}
                         :type "comment"
                         :timestamp (:timestamp c)
                         :comment_text (:comment c)}))
    issue))

(defn import-proj [{:keys [username repo] :as params} target-project user]
  (try
    (let [stories (get-stories username repo)
          issues (map #(import-story % target-project user) stories)]
      {:message (<< "Imported ~(count issues) issues." )})
    (catch Exception e
      {:message (.getMessage e)})))

