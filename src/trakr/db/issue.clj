(ns trakr.db.issue
  (:use [clojure.contrib.strint :only [<<]]
        [clojure.walk :only [postwalk]]
        [onycloud.db.sql-helpers :only [select eq && || update]]
        [onycloud.plugins :only [invoke-plugins defhook]]
        [onycloud.db.user :only [fetch-users-by-shortnames shortname->id]]
        [onycloud.db.util :only [exec-query insert-sql-params update-sql-params
                                 str2num-map return-one]]
        [onycloud.middleware :only [*authenticated-user*]]
        [onycloud.util :only [server-timestamp force-dissoc to-int]]
        [somnium.congomongo :only [fetch fetch-files insert!]]
        [trakr.db.project :only [get-next-local-id]])
  (:require [trakr [config :as cfg]]
            [clojure.string :as str]
            [trakr.db.milestone :as ms]
            [onycloud.util.tag :as tag-util]))

(def issue-attrs [:id :title :status :desc :priority :local_id
                  :creator_name :assignee_name :type :project_name
                  :date :last_update :milestone :creator_email :assignee_email])

(defn- sanitize-issue [issue]
  (let [issue (select-keys issue issue-attrs)]
    (when-let [title (:title issue)]
      (let [title (str/trim title)]
        (when-not (empty? title)
          (assoc issue :title title))))))

(defn- sanitize-diff [diff]
  (let [diff (select-keys diff issue-attrs)]
    (if (:title diff)
      (let [title (str/trim (:title diff))]
        (when-not (empty? title)
          (assoc diff :title title)))
      diff)))

;;; Swap the keys and values in nested maps.
(defn- swapkv [mmap]
  (zipmap (keys mmap) (map (fn [m] (zipmap (vals m) (keys m))) (vals mmap))))

(def num2str-map (swapkv str2num-map))

;;; Modify a value in the map kvs according to function swapfn. If
;;; newkey is given the new value is associated with newkey, and the
;;; old key k is removed.
(defn- swap-val [kvs k swapfn & {:keys [newkey]}]
  (let [nk (or newkey k)]
    (if (contains? kvs k)
      (assoc (force-dissoc kvs k) nk (swapfn (kvs k)))
      kvs)))

;;; Convert the values of map kvs to their string presentation as
;;; defined in num2str-map
(defn- val-to-str [kvs]
  (reduce (fn [kvs k]
            (swap-val kvs k #(get-in num2str-map [k %])))
          kvs
          (keys num2str-map)))

;;; Convert the values of map kvs to their numeric presentation as
;;; defined in str2num-map
(defn- val-to-num [kvs]
  (reduce (fn [kvs k]
            (swap-val kvs k #(get-in str2num-map [k %])))
          kvs
          (keys str2num-map)))

;;; Prepare the issue attributes kvs for database.
(defn- issue-kvs-for-db [kvs project-name]
  (let [kvs (val-to-num kvs)]
    (swap-val kvs :milestone
              (fn [mname] (ms/get-milestone-id-by-name project-name mname))
              :newkey :milestone_id)))

;;; Prepare the issue attributes kvs for web presentation.
(defn- issue-kvs-for-web [kvs]
  (let [kvs (val-to-str kvs)]
    (swap-val kvs :milestone_id
              (fn [mid] (ms/get-milestone-name-by-id mid))
              :newkey :milestone)))

(defn- <-keyword? [x] (when (keyword? x) x))

(defmulti add-email sequential?)

(defmethod add-email false [issue] (first (add-email [issue])))
(defmethod add-email true [issues]
  ;; FIXME: too complex to understand
  (let [names (remove nil?
                      (reduce #(conj %1 (:creator_name %2) (:assignee_name %2))
                              #{} issues))
        data (fetch-users-by-shortnames names)
        maps (into {} (map #(vector (:shortname %) (:email %)) data))]
    (map #(assoc %
            :creator_email (maps (:creator_name %))
            :assignee_email (maps (:assignee_name %)))
         issues)))

(declare convert-condition*)

(defn convert-condition [target]
  (if (coll? target) (convert-condition* str2num-map target) target))

(defn- convert-condition* [cmap target]
  (or (when-let [key (<-keyword? (first target))]
        (when-let [childmap (cmap key)]
          (cons (first target)
                (map (fn [x] (childmap x x)) (rest target)))))
      ;; TODO: the following is probably not needed.
      (map convert-condition target)))

(defn- fetch-issues-by-tags [tags project-name]
  (let [extract-local-id #(to-int (second (clojure.string/split % #":" 2)))]
    (set (map extract-local-id
              (filter #(.startsWith % (str project-name ":"))
                      (tag-util/fetch-items-by-tags tags :issue))))))

(defn- count-issues
  [project-name & {:keys [conditions search]}]
  (let [conds (&& (apply && (map eq (convert-condition conditions)))
                  ["project_name = ?" project-name])
        all-conds (if search (&&
                              (|| ["\"desc\" LIKE ?" (<< "%~{search}%")]
                                  ["\"title\" LIKE ?" (<< "%~{search}%")])
                              conds)
                      conds)]
    (-> (exec-query (select "count(*) as count" :issues :where all-conds))
        first :count)))

(defn fetch-issues-by-project
  [project-name & {:keys [limit offset conditions search sort-by tags]}]
  (let [conds (&& (apply && (map eq (convert-condition conditions)))
                  ["project_name = ?" project-name])
        all-conds (if search
                    (&& conds (|| ["\"desc\" ILIKE ?" (<< "%~{search}%")]
                                  ["\"title\" ILIKE ?" (<< "%~{search}%")]))
                    conds)
        got-milestone? (.contains (map first conditions) :milestone_id)
        all-conds (if got-milestone?
                    (&& all-conds ["issues.milestone_id = milestones.id"])
                    all-conds)
        select-targets (if got-milestone?
                         [:issues :milestones]
                         :issues)
        join-milestone (when-not got-milestone?
                         [:milestones
                          :on "issues.milestone_id = milestones.id"])
        sql (if tags
              (select [:issues.* [:milestones.name :as :milestone]]
                      select-targets
                      :where all-conds
                      :left-outer-join join-milestone
                      :sort-by sort-by)
              (select [:issues.* [:milestones.name :as :milestone]]
                      select-targets
                      :where all-conds
                      :left-outer-join join-milestone
                      :sort-by sort-by
                      :limit limit
                      :offset offset))
        issues (let [raw-issues (exec-query sql)]
                 (if tags
                   (let [local-ids (fetch-issues-by-tags tags project-name)
                         relevant-issues (vec (filter #(contains? local-ids
                                                                  (:local_id %))
                                                      raw-issues))]
                     (subvec relevant-issues offset (min (count relevant-issues)
                                                         (+ 1 offset limit))))
                   raw-issues))
        n (if tags
            (count issues)
            (count-issues project-name
                          :conditions conditions
                          :search search))]
    {:data (map val-to-str
                (map #(force-dissoc % :milestone_id)
                     issues))
     :count n}))

(defn fetch-issue [project-name local-id]
  (issue-kvs-for-web
   (first
    (exec-query ["SELECT * FROM issues WHERE project_name = ? AND local_id = ?"
                 project-name local-id]))))

(defn fetch-issues [ids]
  (if (seq ids)
    (exec-query
     (select [:title :status :project_name :local_id :id] :issues
             :where (eq (cons :id ids))))
    []))

(defn insert-issue [issue]
  (when-let [issue (sanitize-issue issue)]
    (let [project-name (:project_name issue)
          new-issue (first
                     (exec-query
                      (insert-sql-params
                       :issues (issue-kvs-for-db issue project-name))))]
      (issue-kvs-for-web new-issue))))

(declare create-update)

(def ^{:private true}
  issue-change-attrs [:title :type :priority
                      :status :assignee_name
                      :desc :milestone])

(defn insert-issue-and-update [issue project-name]
  (let [local-id (get-next-local-id project-name)]
    (when-let [issue (insert-issue
                      (assoc issue
                        :creator_name (:shortname *authenticated-user*)
                        :project_name project-name
                        :local_id local-id))]
      (do (create-update (:id issue) project-name local-id
                         {:type "new_issue"
                          :user *authenticated-user*
                          :changes (map (fn [attr]
                                          [attr nil (issue attr)])
                                        issue-change-attrs)})
          issue))))

(defn create-comment [project-name local-id comment]
  (let [timestamp (server-timestamp)]
    (when-let [issue (return-one
                      (update :issues
                              {:last_update timestamp}
                              :where (&& (eq [:project_name project-name])
                                         (eq [:local_id local-id]))
                              :returning :*))]
      (create-update (:id issue) project-name local-id
                     {:type "comment"
                      :timestamp timestamp
                      :comment_text (:comment_text comment)
                      :user *authenticated-user*}))))

(defhook post-update-issue)

(defn- find-changes [old new]
  (let [pick-if-changed (fn [attr] (let [oldv (attr old)
                                         newv (attr new)]
                                     (when-not (= oldv newv)
                                       [attr oldv newv])))]
    (filter identity (map pick-if-changed issue-change-attrs))))

(defn- adjust-status [issue original]
  (if (and (= "new" (:status issue))
           (not (empty? (:assignee_name issue))))
    (if (= "new" (:status original))
      (assoc issue :status "assigned")
      ; assignee_name must be explicitly set to nil instead of
      ; dissoc-ed, otherwise the attribute won't be updated in database.
      (assoc issue :assignee_name nil))
    issue))

(defn- adjust-assignee [issue original]
  (if (and (not= "new" (:status issue))
           (empty? (:assignee_name issue)))
    (if (empty? (:assignee_name original))
      (assoc issue :assignee_name (:shortname *authenticated-user*))
      (assoc issue :status "new"))  ; This is an unassign.
    issue))

(defn- update-issue* [original updated]
  (let [adjusted (-> updated
                     (force-dissoc :date)
                     (adjust-status original)
                     (adjust-assignee original))
        changes (find-changes original adjusted)
        ;; TODO use sql function now()
        timestamp (server-timestamp)
        project-name (:project_name updated)]
    (if (seq changes)
      (let [new-kvs (into {:last_update timestamp}
                          (map (fn [[k _ v]] [k v]) changes))
            updated-issue (issue-kvs-for-web
                           (return-one
                            (update :issues (issue-kvs-for-db new-kvs
                                                              project-name)
                                    :where (eq [:id (:id original)])
                                    :returning :*)))]
        (when updated-issue
          (invoke-plugins post-update-issue
                          :updated updated-issue, :original original,
                          :changes changes)
          (create-update (:id original)
                         (:project_name original)
                         (:local_id original)
                         {:changes changes
                          :user *authenticated-user*
                          :timestamp timestamp
                          :type "modify"}))
        updated-issue)
      ;; No valid change found, just return the original
      original)))

(defn update-issue [project-name local-id updated]
  {:pre [(number? local-id) (not (empty? project-name))]}
  (when-let [original (fetch-issue project-name local-id)]
    (when-let [updated (sanitize-issue updated)]
      (update-issue* original updated))))

(defn- normalize-issue-diff [issue-diff original]
  (let [p {}                     ; client sent nil if it's an unassign
        status (or (:status issue-diff) (:status original))
        assignee (let [a (:assignee_name issue-diff p)]
                   (cond (nil? a) nil
                         (= a p) (:assignee_name original)
                         :else a))]
    (assoc issue-diff
      :status status
      :assignee_name assignee)))

(defn apply-issue-diff [diff original]
  (let [project-name (:project_name original)
        diff (sanitize-diff diff)]
    ;; no diff, no action
    (when (and original diff)
      (if (seq diff)
        (let [adjusted (-> diff
                           (normalize-issue-diff original)
                           (adjust-status original)
                           (adjust-assignee original)
                           (assoc :last_update (server-timestamp)))]
          (issue-kvs-for-web
           (return-one
            (update :issues (issue-kvs-for-db adjusted
                                              project-name)
                    :where (eq [:id (:id original)])
                    :returning :*))))
        {}))))

(defn fetch-updates-by-issue-id [issue-id & {:as opts}]
  (fetch :trakr_updates
         :where (if-let [since (:since opts)]
                  {:issue_id issue-id
                   :date {:$gt since}}
                  {:issue_id issue-id})
         :limit (:limit opts)
         :skip (:offset opts 0)
         :sort {:date -1}))

(defn fetch-updates [mongo-where]
  (let [updates* (map (fn [x] (update-in x [:issue_id] #(to-int %)))
                      (fetch :trakr_updates
                             :where mongo-where
                             :limit cfg/WATCHED-UPDATES-LIMIT
                             :sort {:date -1}))
        issues (fetch-issues (distinct (map :issue_id updates*)))]
    (for [d updates*, i issues
          :when (= (:issue_id d) (:id i))]
      (assoc d
        :issue_title (:title i)))))

;;; See comments at trakr.handlers.issues/list-updates.
(defn create-update [id project-name local-id update]
  {:pre [(number? id) (not (empty? project-name)) (number? local-id)]}
  (let [timestamp (:timestamp update (server-timestamp))]
    (when (#{"modify" "new_issue" "comment"} (:type update))
      (insert! :trakr_updates (assoc update
                                :date timestamp
                                :issue_id id
                                :project_name project-name
                                :issue_local_id local-id)))))

(defn fetch-stats-by-status [proj-name milestone-id]
  (let [raw-results (exec-query
                     [(str "SELECT status, COUNT(id) FROM issues "
                           "WHERE project_name=? "
                           "AND milestone_id=? "
                           "GROUP BY status")
                      proj-name milestone-id])]
    (merge
     {"new" 0 "assigned" 0 "accepted" 0 "finished" 0 "closed" 0}
     (into {}
           (map #(vector (get (:status num2str-map) (:status %))
                         (:count %))
                raw-results)))))

(defn add-tag [issue tag]
  (let [{:keys [project_name local_id]} issue
        item-id (str project_name ":" local_id)
        extra-data {:project_name project_name :local_id local_id}]
    (when-not (contains? (tag-util/fetch-tags-by-items :issue item-id) tag)
      (tag-util/add-tag-to-item tag :issue item-id extra-data)
      {:id tag :name tag})))

(defn delete-tag [issue tag]
  (let [{:keys [project_name local_id]} issue
        item-id (str project_name ":" local_id)]
    (tag-util/remove-tag-from-item tag :issue item-id)
    {:id tag :name tag}))

(defn list-tags-by-issue [issue]
  (let [{:keys [project_name local_id]} issue
        tags (tag-util/fetch-tags-by-items :issue
                                           (str project_name ":" local_id))]
    (map #(hash-map :id % :name %) tags)))
