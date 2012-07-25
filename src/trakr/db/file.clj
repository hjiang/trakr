(ns trakr.db.file
  (:use [somnium.congomongo :only [fetch-files insert-file! update!
                                   fetch-one-file destroy-file! object-id]]
        [onycloud.db.util :only [exec-query insert-sql-params return-one]]))

(defn insert-file [col-name file issue]
  (let [{:keys [tempfile filename user-id content-type]} file]
    (insert-file! col-name
                  tempfile
                  :filename filename
                  :contentType content-type
                  :metadata {:user_id user-id
                             :issue_id (:id issue)})))

(defn- fetch-files-in-postgres [issue]
  (exec-query ["SELECT * from issue_files WHERE issue_id = ?"
               (:id issue)]))

;;; TODO store it in postgres.trakr.issue_files
(defn- fetch-files-in-mongo [issue]
  (map (fn [m] {:issue_id (:id issue)
               :issue_local_id (:local_id issue)
               :project_name (:project_name issue)
               :file_id (-> m :_id str)
               :filename (:filename m)
               :content_type (:contentType m)
               :thumbnail_id (-> m :metadata :thumbnail_id str)
               :file_size (:length m)})
       (fetch-files "trakr_files"
                    :where {:metadata.issue_id (:id issue)
                            :metadata.type {:$ne "thumbnail"}})))

(defn fetch-issue-files [issue]
  (concat (fetch-files-in-mongo issue)
          (fetch-files-in-postgres issue)))

(defn fetch-thumbnail [file]
  (let [thumbnail-id (-> file :metadata :thumbnail_id)]
    (fetch-one-file "trakr_files"
                    :where {:_id thumbnail-id})))

(defn insert-thumbnail [& {:keys [file filename content-type user-id]}]
  (insert-file! "trakr_files"
                file
                :filename filename
                :contentType content-type
                :metadata {:user_id user-id
                           :type "thumbnail"}))

(defn insert-issue-file [& {:keys [file filename content-type user-id
                                   thumbnail-id]}]
  (insert-file! "trakr_files"
                file
                :filename filename
                :contentType content-type
                :metadata {:user_id user-id
                           :type "original"
                           :thumbnail_id thumbnail-id}))

(defn destroy-trakr-file! [id]
  (destroy-file! "trakr_files" {:_id (object-id id)}))

(defn delete! [issue-id assocation-id]
  (return-one ["DELETE from issue_files WHERE id = ?  And issue_id = ?
            RETURNING *" assocation-id issue-id]))

(defn assoc-to-issue [issue {:keys [filename file_id thumbnail_id
                                    content_type file_size]}]
  (return-one
   (insert-sql-params :issue_files
                      {:issue_id (:id issue)
                       :issue_local_id (:local_id issue)
                       :file_id file_id
                       :project_name (:project_name issue)
                       :thumbnail_id thumbnail_id
                       :filename filename
                       :content_type content_type
                       :file_size file_size})))
