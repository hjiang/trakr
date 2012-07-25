(ns trakr.handlers.issues
  (:use [clojure.contrib.trace :only [trace]]
        [onycloud.handlers.util :only [when-has-permission]]
        [onycloud.plugins :only [defhook invoke-plugins]]
        [onycloud.util :only [to-long]]
        [onycloud.middleware :only [*authenticated-user*]]
        [onycloud.db.util :only [str2num-map]]
        [trakr.plugins.logging :only [fetch-recent-issues]]
        [trakr.db.project :only [fetch-project get-next-local-id
                                 project-archived? fetch-tags-by-project]]
        [trakr.db.membership :only [user-in-project? fetch-memberships
                                    can-read-project? can-write-to-project?
                                    can-add-issue-to-project?
                                    can-add-comment-to-project?
                                    can-update-issue?]])
  (:require [clojure.string :as string]
            [trakr.handlers.blockages :as blockage]
            [trakr.handlers.files :as file]
            [trakr.db.issue :as db]
            [trakr.db.milestone :as ms]
            [onycloud.util.tag :as tag]))

(defn- parse-csv [arg-str]
  (if arg-str (string/split arg-str #",") nil))

(defn- parse-conds [req & conds]
  (map flatten
       (filter second
               (map (fn [x] [x (parse-csv ((:params req) x))]) conds))))

(defn- add-tags-to-issue [issue project-name]
  (let [item-id (str project-name ":" (:local_id issue))
        tags (tag/fetch-tags-by-items :issue item-id)]
    (assoc issue :tags (map #(hash-map :id % :name %) tags))))

(defn- add-tags [issues project-name]
  (map #(add-tags-to-issue % project-name) issues))

(defhook post-list-issues post-update-issue)

(defn list-issues [req]
  (let [{:keys [limit offset order_by project-name search]
         :or {limit "50" offset "0" order_by "last_update.desc"}} (:params req)]
    (when-has-permission
     (can-read-project? project-name)
     (let [ms-id (and (contains? (:params req) :milestone)
                      (ms/get-milestone-id-by-name
                       project-name (-> req :params :milestone)))
           conditions (parse-conds req :assignee_name :status :priority :type)
           tags (-> req :params :tag parse-csv)
           conditions* (if ms-id
                         (cons (list :milestone_id ms-id) conditions)
                         conditions)
           issues (db/fetch-issues-by-project
                   project-name
                   :conditions conditions*
                   :search search
                   :limit (Integer/parseInt limit)
                   :offset (Integer/parseInt offset)
                   :sort-by order_by
                   :tags tags)]
       (do (invoke-plugins post-list-issues *authenticated-user*
                           project-name offset)
           (update-in issues [:data] #(-> %
                                          (add-tags project-name)
                                          db/add-email)))))))

(defhook post-create-issue)

(defn validate-issue [issue]
  (and (contains? (:type str2num-map) (:type issue))
       (contains? (:status str2num-map) (:status issue))
       (contains? (:priority str2num-map) (:priority issue))))

(defn create-issue [req]
  (let [project-name (-> req :params :project-name)
        project (fetch-project project-name)
        issue (:json-body req)]
    (when-has-permission
     (can-add-issue-to-project? project-name)
     (cond
      (not (validate-issue issue)) {:status 400
                                    :body {:message "Invalid issue data"}}
      (:archived project) {:status 400
                           :body {:message
                                  "Can't create issue in archived project."}}
      :else (if-let [issue (db/insert-issue-and-update issue project-name)]
              (do (invoke-plugins post-create-issue project issue)
                  (db/add-email issue))
              {:status 400
               :body {:message "Failed to create the new issue"}})))))

(defn update-issue [req]
  (let [{:keys [project-name local-id]} (:params req)]
    (when-has-permission
     (can-update-issue? project-name (to-long local-id))
     (if (project-archived? project-name)
       {:status 400 :body {:message "Can't update issue in archived project."}}
       (or (db/add-email (db/update-issue project-name
                                          (Integer/parseInt local-id)
                                          (:json-body req)))
           {:status 404})))))

(defhook post-visit-issue)

(defn get-issue [req]
  (let [{:keys [project-name local-id]} (:params req)
        local-id (Integer/parseInt local-id)]
    (when-has-permission
     (can-read-project? project-name)
     (if-let [issue (db/fetch-issue project-name local-id)]
       (let [issue-full (assoc issue
                          :files (file/get-files issue)
                          :blocking_issues (blockage/list-blocking-issues
                                            (:id issue))
                          :blocked_issues (blockage/list-blocked-by
                                           (:id issue))
                          :tags (db/list-tags-by-issue issue))]
         (do (invoke-plugins post-visit-issue *authenticated-user* issue-full)
             (-> issue-full db/add-email)))
       {:status 404}))))

(defn recent-issue-ret [issue]
  (select-keys (:data issue) [:project_name :local_id :title]))

(defn recent-issues [req]
  (let [{:keys [limit] :or {limit "6"}} (:params req)
        user-id (:_id *authenticated-user*)
        limit (Integer/parseInt limit)
        issues (fetch-recent-issues user-id limit)]
    (map recent-issue-ret issues)))

;;; An update records modifications to an issue. It can have the
;;; following fields:
;;; * _id - unique update ID
;;; * date - timestamp  TODO: change the field name to timestamp
;;; * project_name
;;; * issue_id - issue.id
;;; * issue_local_id - issue.local_id
;;; * user - a map including :name :shortname :email
;;; * type - "comment" or "modify" or "new_issue"
;;; * comment_text - if type is "comment", this is the body text.
;;; * changes - if type is "modify", this is a list of changes. Each
;;;   change has the form: [attr, old, new]
;;;           - if type is "new_issue", this is just like "modify",
;;;             but 'old' in [attr, old, new] should be nil
;;;      [comment, nil "text"] is legal, it's used to add comment
;;;                            about the change. client should be able
;;;                            to understand it

(defn list-updates [req]
  (let [{:keys [project-name local-id since]} (:params req)
        since (when-let [ts (to-long since)]
                (java.util.Date. ts))]
    (when-has-permission
     (can-read-project? project-name)
     (if-let [issue (db/fetch-issue project-name (Integer/parseInt local-id))]
       (db/fetch-updates-by-issue-id (:id issue) :since since)
       {:status 404}))))

(defhook post-create-comment)

(defn create-comment [req]
  (let [{:keys [project-name local-id]} (:params req)
        comment (:json-body req)]
    (when-has-permission
     (can-add-comment-to-project? project-name)
     (if-let [issue (db/fetch-issue project-name (Integer/parseInt local-id))]
       (if-let [comment
                (db/create-comment project-name (Integer/parseInt local-id)
                                   comment)]
         (do (invoke-plugins post-create-comment issue comment)
             comment)
         {:status 400 :body {:message "Server failed to save the comment."}})
       {:status 404}))))

(defn- int? [i]
  (try
    (Integer/parseInt i)
    (catch Exception e)))

(defn- to-auto-complete-data [issue]
  "Convert to jquery auto compelte understandable data structure"
  {:label (str "#" (:local_id issue) " " (:title issue))
   :value (str "#" (:local_id issue))})

(defn auto-complete [req]
  "Provide auto complete source for guiding user add blockage"
  (let [{:keys [project-name term limit]} (:params req)
        limit (if limit (int? limit) 10)
        term (when term (if (= \# (.charAt term 0))
                          (.substring term 1)
                          term))]
    (when-has-permission
     (can-read-project? project-name)
     (if-let [issue (and (int? term) ;; term is a valid loca-id
                         (db/fetch-issue project-name (int? term)))]
       (list (to-auto-complete-data issue))
       (map to-auto-complete-data
            (:data (db/fetch-issues-by-project
                    project-name :search term :limit limit)))))))

(defn- apply-blockage-issues-diff [diffs issue fns]
  (let [project-name (:project_name issue)
        local-id (:local_id issue)
        {:keys [add-fn delete-fn]} fns]
    (flatten
     (map (fn [b]
            (cond (= "+" (:_op b)) ; create new, client submit a local_id
                  (map (fn [i] {:_op "+" :_data i})
                       (add-fn
                        project-name local-id (-> b :_data :local_id)))
                  (= "-" (:_op b))
                  {:_op "-"
                   ;; delete
                   :_data (delete-fn
                           project-name local-id (-> b :_where :id))}))
          diffs))))

(defn- apply-blocking-issues-diff [diffs issue]
  (apply-blockage-issues-diff diffs issue
                              {:add-fn blockage/add-blocking
                               :delete-fn blockage/delete-blocking}))

(defn- apply-blocked-issues-diff [diffs issue]
  (apply-blockage-issues-diff diffs issue
                              {:add-fn blockage/add-blocked
                               :delete-fn blockage/delete-blocked}))

(defn- apply-files-diff [diffs issue]
  (map (fn [f]
         (cond (= "+" (:_op f))
               {:_op "+"
                :_data (file/assoc-to-issue issue (:_data f))}
               (= "-" (:_op f))
               {:_op "-"
                :_data (file/delete! (:id issue) (-> f :_where :id))}))
       diffs))

(defn- apply-tags-diff [diffs issue]
  (map (fn [x]
         (let [op (:_op x)]
           (cond
            (= "+" op) {:_op "+"
                        :_data (db/add-tag issue (-> x :_data :name))}
            (= "-" op) {:_op "-"
                        :_data (db/delete-tag issue (-> x :_where :id))})))
       diffs))

(defn- assoc-if [map & kvs]
  "like assoc, but drop false value"
  (let [kvs (apply concat
                   (filter #(second %) (partition 2 kvs)))]
    (if (seq kvs) (apply assoc map kvs) map)))

(defn- file-updates [files]
  (map (fn [f]
         (let [op (:_op f)
               file (:_data f)]
           (cond
            (= "+" op) ["file" nil file]
            (= "-" op) ["file" file nil])))
       files))

(defn- tag-updates [tags]
  (map (fn [x]
         (let [op (:_op x)]
           (cond
            (= "+" op) ["tag" nil (-> x :_data :name)]
            (= "-" op) ["tag" (-> x :_data :id) nil])))
       tags))

(defn- blockage-updates [category diff]
  (map (fn [b]
         (let [{:keys [local_id title]} (:_data b)
               op (:_op b)]
           (cond (= "+" op)
                 [category nil {:local_id local_id
                                :title title}]
                 (= "-" op)
                 [category {:local_id local_id
                            :title title} nil])))
       diff))

(def ^{:private true} dummy (keyword (gensym)))

;;; diffs is new-issue(or {} if issue is not changed)
;;; merge with other update
(defn- find-updates [diffs old-issue]
  (let [files (-> diffs :files)
        tags (-> diffs :tags)
        blocking-issues (-> diffs :blocking_issues)
        issue-updates (filter identity
                              (map (fn [[k old-v]]
                                     ;; dummy is needed, diff maybe {}
                                     (let [new-v (diffs k dummy)]
                                       ;; actually changed
                                       (when (and (not= new-v dummy)
                                                  (not= new-v old-v))
                                         [k old-v new-v])))
                                   (seq old-issue)))]
    (concat issue-updates
            (blockage-updates "blocking_issue" (-> diffs :blocking_issues))
            (blockage-updates "blocked_issue" (-> diffs :blocked_issues))
            (file-updates files)
            (tag-updates tags))))

;;; eg: add blocking should create update for blocked by
(defn- compute-and-save-derived-update
  [updates {:keys [local_id title project_name] :as old-issue}]
  (doseq [[attr old-v new-v] updates]
    (when (= attr "blocking_issue")
      (cond (nil? old-v)                ; add blocking issue
            (let [issue (db/fetch-issue project_name
                                        (:local_id new-v))]
              (db/create-update (:id issue)
                                project_name
                                (:local_id issue)
                                {:changes (list ["blocked_issue" nil
                                                 {:local_id local_id
                                                  :title title}])
                                 :user *authenticated-user*
                                 :type "modify"}))
            (nil? new-v)                ; remove blocking issue
            (let [issue (db/fetch-issue (:project_name old-issue)
                                        (:local_id old-v))]
              (db/create-update (:id issue)
                                project_name
                                (:local_id issue)
                                {:changes (list ["blocked_issue"
                                                 {:local_id local_id
                                                  :title title} nil])
                                 :user *authenticated-user*
                                 :type "modify"}))))))

(defn- save-update "comment is why do this change, maybe nil"
  [diffs old-issue comment]
  (let [updates (if comment (concat (find-updates diffs old-issue)
                                    (list [:comment nil comment]))
                    (find-updates diffs old-issue))]

    (compute-and-save-derived-update updates old-issue)
    (if (seq updates)
      (db/create-update (:id old-issue)
                        (:project_name old-issue)
                        (:local_id old-issue)
                        {:changes updates
                         :user *authenticated-user*
                         :type "modify"}))))

(comment
  ;; Request sample
  {:_op "+|-|!"
   :_data {:assignee_name "someone"
           ;; ....
           :blocking_issues [{:_op "+|-|!"
                              :_where {} ;;
                              :data {}}]
           :files [{:_op "+|-|!"
                    :data {}}]}}
  ;; delete, update will have _where, add will not have.
  ;; server need where to identify which up delete or udpate
  :_where {:local_id 1}
  ;; Response  sample
  {:_op "+|0|!"
   :_data {:assignee_name "somebody"
           :blocking_issues [{:_op "!",
                              :_data {}
                              }]        ; nested change
           ;; .....
           } ;;server changed data
   })

(defn- apply-issue-diffs* [diffs original]
  (let [project-name (:project_name original)
        blocking-issues (when-let [issue-diffs (:blocking_issues diffs)]
                          (apply-blocking-issues-diff issue-diffs original))
        blocked-issues (when-let [issue-diffs (:blocked_issues diffs)]
                         (apply-blocked-issues-diff issue-diffs original))
        files (when-let [files-diff (:files diffs)]
                (apply-files-diff files-diff original))
        tags (when-let [tags-diff (:tags diffs)]
               (apply-tags-diff tags-diff original))]
    (when-let [updated-issue (db/apply-issue-diff diffs original)]
      (let [computed-diff (assoc-if ;; aggregate server diff
                           (or updated-issue {})
                           :blocking_issues blocking-issues
                           :blocked_issues blocked-issues
                           :files files
                           :tags tags)
            ;; based on server diff, compute udpates
            update (save-update computed-diff
                                (dissoc original :last_update)
                                (:comment diffs))]
        (invoke-plugins post-update-issue ; send email, etc
                        :updated update-issue
                        :original original
                        :changes (:changes update))
        ;; returned diff client, client will apply it
        {:_op "!"
         :_data (assoc computed-diff :update update)}))))

(defn apply-issue-diffs [req]
  (let [{:keys [project-name local-id]} (:params req)
        issue (db/fetch-issue project-name (Integer/parseInt local-id))]
    (when-has-permission
     (can-write-to-project? project-name)
     (if (project-archived? project-name)
       {:status 400
        :body {:message "Can't update issue in archived project."}}
       (or (apply-issue-diffs* (-> req :json-body :_data) issue)
           {:status 400,
            :body {:message "Invalid update request."}})))))

(defn- to-auto-complete-tag-data [tag]
  "Convert to jquery auto compelte understandable data structure"
  {:value tag})

(defn auto-complete-tag-source [req]
  "Provide auto complete source for guiding user add blockage"
  (let [{:keys [project-name term limit]} (:params req)]
    (when-has-permission
     (can-read-project? project-name)
     (->> (fetch-tags-by-project project-name)
          (filter #(.contains % term))
          (map to-auto-complete-tag-data)))))
