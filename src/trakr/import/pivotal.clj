(ns trakr.import.pivotal
  (:use [onycloud.util.http :only [http-get]]
        [clojure.contrib.strint :only [<<]])
  (:require (clojure [zip :as zip]
                     [xml :as xml]
                     [string :as str])
            [onycloud.handlers.accounts :as account]
            [trakr.handlers.memberships :as membership]
            (trakr.db [issue :as issuedb]
                      [project :as projectdb])
            [clojure.contrib.zip-filter.xml :as f])
  (:import java.text.SimpleDateFormat
           java.sql.Timestamp))

(defn- gen-header [guid]
  {:headers
   {"X-TrackerToken" guid}})

(defn parse-xml [s]
  (xml/parse
   (new org.xml.sax.InputSource
        (new java.io.StringReader s))))

(defn- parse-str [s]
  (zip/xml-zip
   (parse-xml s)))

(defn- value [a]
  (cond (empty? a) nil
        (or (seq? a) (vector? a)) (if (= 1 (count a))
                                    (value (first a)) a)
        :else a))

(defn- path [x tag]
  (let [r (map :content (filter #(= tag (:tag %)) x))]
    (value r)))

(defn get-projects [guid]
  ;; TODO: handle errors
  (let [uri "http://www.pivotaltracker.com/services/v2/projects"
        header (gen-header guid)
        resp (http-get uri header)
        projects (-> resp :body parse-str)]
    (map (fn [id name] {:id (Integer/parseInt id)
                       :name name})
         (f/xml-> projects :project :id f/text)
         (f/xml-> projects :project :name f/text))))

(defn get-stories [project-id guid]
  ;; TODO: handle errors.
  (let [uri (format (str "http://www.pivotaltracker.com/services"
                         "/v2/projects/%d/stories") project-id)
        header (gen-header guid)
        formater (SimpleDateFormat. "yyyy/MM/dd HH:mm:ss zzz")
        parse-date (fn [str]
                     (Timestamp. (.. formater (parse str) getTime)))
        resp (http-get uri header)
        stories (-> resp :body parse-str)]
    (apply map (fn [id title status type creator-name
                   assignee-name desc date last-update]
                 {:id id
                  :title title
                  :status ({"unscheduled" "new"
                            "unstarted" "assigned"
                            "started" "accepted"
                            "rejected" "assigned"
                            "delivered" "finished"
                            "finished" "finished"
                            } status "new")
                  :type (get #{"bug" "feature" "chore"} type "bug")
                  :creator_name creator-name
                  :assignee_name assignee-name
                  :desc desc
                  :date (parse-date date)
                  :last_update (parse-date last-update)})
           (map (fn [domain]
                  (f/xml-> stories :story domain f/text))
                [:id :name :current_state :story_type
                 :requested_by :owned_by :description
                 :created_at :updated_at]))))

(defn- normalize [list]
  (let [max (apply max (map count list))]
    (map (fn [l]
           (if (= max (count l))
             l
             (concat l (repeat (- max (count l)) nil)))) list)))

;;; get membership from pivotaltraker, return a trakr understandable
;;; format
(defn get-memberships [project-id guid]
  (try
    (let [uri (format (str "http://www.pivotaltracker.com/services"
                           "/v2/projects/%d/memberships")
                      project-id)
          header (gen-header guid)
          resp (http-get uri header)
          memberships (-> resp :body parse-xml)]
      (map (fn [m]
             (let [person (path m :person)
                   role (path m :role)]
               {:email (path person :email)
                :name (path person :name)
                :shortname (path person :initials)
                :user_id (path m :id)
                :role (if (= role "Owner") "admin" "member")}))
           (path (:content memberships) :membership)))
    (catch Exception e)))

(defn- import-membership [project pivotal-user]
  (let [user (account/register-if-not-exists pivotal-user)]
    (if user
      ;; add-membership will take care of duplicate
      (membership/add-membership*
       (:id project) user (:role pivotal-user))
      {(:name pivotal-user) (:shortname user)})
    {nil nil}))

(defn- import-story [story project name-map default-name]
  (let [local-id (projectdb/get-next-local-id (:name project))
        issue {:local_id local-id
               :title (:title story)
               :desc (:desc story)
               :type (:type story)
               :priority "medium"
               :date (:date story)
               :last_update (:last_update story)
               :status (:status story)
               :project_name (:name project)
               :creator_name (name-map (:creator_name story) default-name)
               :assignee_name (name-map (:assignee_name story default-name))}]
    (issuedb/insert-issue issue)))

(defn do-import [project options]
  (let [{:keys [guid id-inpivotal importor-name]} options
        people (get-memberships id-inpivotal guid)
        name-map (apply merge
                        (map #(import-membership project %)
                             people))
        stories (get-stories id-inpivotal guid)]
    (doall
     (map #(import-story % project name-map importor-name) stories))))

(defn import-proj [{:keys [guid project-name] :as params} target-project user]
  (let [guid (and guid (str/trim guid))
        project-name (and project-name (str/trim project-name))]
    (try
      (if-let [project (first (filter #(= project-name (:name %))
                                      (get-projects guid)))]
        (let [issues (do-import target-project
                                {:guid guid
                                 :id-inpivotal (:id project)
                                 :importor-id (str (:_id user))
                                 :importor-name (:shortname user)
                                 :importor-email (:email user)})]
          {:message (<< "Imported ~(count issues) issues." )})
        {:message
         (<< "Cannot find project ~{project-name} under the given account.")})
      (catch Exception e
        {:message (str "Error occured while importing, "
                       "Have you provide the right Auth key?")}))))
