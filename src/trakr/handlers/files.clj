(ns trakr.handlers.files
  (:use [ring.util.response :only (redirect)]
        [clojure.contrib.strint :only [<<]]
        [onycloud.middleware.json :only [json-response]]
        [onycloud.middleware :only [*authenticated-user*]]
        [trakr.db.project :only [fetch-project get-next-local-id
                                 project-archived?]]
        [trakr.db.membership :only [user-in-project? fetch-memberships
                                    can-read-project? can-write-to-project?]]
        [onycloud.util.image :only [make-thumbnail]])
  (:require [clojure.contrib.io :as io]
            [trakr.db.issue :as issuedb]
            [trakr.db.file :as db]
            [onycloud.util :as util]
            [clojure.string :as string])
  (:import [java.io ByteArrayInputStream File]))

(defn- image? [content-type]
  (.startsWith (or content-type "") "image"))

(defn- get-mime-href [ctype]
  (let [base "public"
        parts (string/split ctype #"/")
        filenames [(string/join "-" parts) (second parts) (first parts)]
        file-paths (map #(str"/images/mime/" % ".png") filenames)
        first-match (some #(when (.exists (io/file-str base %)) %) file-paths)]
    (if first-match
      (str "/static" first-match)
      (str "/static/images/mime/unknown.png"))))

(defn- icon-href [{:keys [project_name issue_local_id thumbnail_id
                          content_type filename]}]
  (if (string/blank? thumbnail_id)
    (get-mime-href content_type)
    (str
     (<< "/trakr/api/projects/~{project_name}/issues/")
     (<< "~{issue_local_id}/files/~{thumbnail_id}/~{filename}"))))

(defn prepare-issue-file-attrs
  [{:keys [project_name issue_local_id filename file_size
           content_type file_id thumbnail_id id] :as m}]
  (let [href (str
              (<< "/trakr/api/projects/~{project_name}/issues/")
              (<< "~{issue_local_id}/files/~{file_id}/~{filename}"))
        data {:file_id file_id
              :content_type content_type
              :thumbnail_id thumbnail_id
              :filename filename
              :is_img (image? content_type)
              :file_size file_size
              :href href
              :icon_href (icon-href m)}]
    (if id (assoc data :id id) data)))

(defn- get-href [project-name issue-local-id file]
  (let [id (-> file :_id str)
        filename (:filename file)]
    (str (<< "/trakr/api/projects/~{project-name}/")
         (<< "issues/~{issue-local-id}/files/~{id}/~{filename}"))))

(defn- get-icon-href [project-name issue-local-id file]
  (let [has-thumbnail? (and (image? (:contentType file))
                            (= (-> file :metadata :type) "original"))
        thumbnail (when has-thumbnail? (db/fetch-thumbnail file))]
    (if has-thumbnail?
      (get-href project-name issue-local-id thumbnail)
      (get-mime-href (:contentType file)))))

(defn- issue-file-ds [file project-name local-id]
  (let [id (-> file :_id str)
        filename (:filename file)
        content-type (:contentType file)
        is-img (image? content-type)
        href (get-href project-name local-id file)
        icon-href (get-icon-href project-name local-id file)]
    {:id id
     :contentType content-type
     :filename filename
     :href href
     :icon_href icon-href
     :isImg is-img}))

(defn get-file [req]
  (let [{:keys [project-name local-id file-id]} (:params req)
        headers (:headers req)]
    (if (not (can-read-project? project-name))
      {:status 403}
      (let [resp (util/get-image headers file-id "trakr_files")]
        (if (= (:status resp) 404)
          (redirect "/static/images/trakr/no-such-file.gif")
          resp)))))

(defn get-files [issue]
  (map prepare-issue-file-attrs
       (db/fetch-issue-files issue)))

(defn assoc-to-issue [issue file]
  (prepare-issue-file-attrs
   (db/assoc-to-issue issue file)))

(defn delete! [issue-id, assocation-id]
  "Delete the given assocation"
  (when-let [deleted (db/delete! issue-id assocation-id)]
    (when-let [file-id (:file_id deleted)]
      (db/destroy-trakr-file! file-id))
    (when-let [thumbnail_id (:thumbnail_id deleted)]
      (db/destroy-trakr-file! thumbnail_id))
    (prepare-issue-file-attrs deleted)))

(defn save-tmp-file [req]
  (let [{:keys [project-name local-id]} (:params req)
        {:strs [x-file-contenttype x-file-name]} (:headers req)
        user-id (-> *authenticated-user* :_id str)
        ;; avoid big file consume too much memory
        tmpfile (File/createTempFile "issue_upload_" ".tmp")]
    (try
      (let [_ (io/copy (:body req) tmpfile)
            thumbnail-id (when (image? x-file-contenttype)
                           (:_id (db/insert-thumbnail
                                  :file (make-thumbnail tmpfile 96)
                                  :filename x-file-name
                                  :content-type x-file-contenttype
                                  :user-id user-id)))
            file (db/insert-issue-file :file tmpfile
                                       :filename x-file-name
                                       :content-type x-file-contenttype
                                       :thumbnail-id thumbnail-id
                                       :user-id user-id)]
        (json-response
         200 (prepare-issue-file-attrs
              {:filename x-file-name
               :content_type x-file-contenttype
               :project_name project-name
               :issue_local_id local-id
               :file_size (:length file)
               :file_id (-> file :_id str)
               :thumbnail_id (when thumbnail-id
                               (str thumbnail-id))})))
      (finally (.delete tmpfile)
               {:status 403}))))
