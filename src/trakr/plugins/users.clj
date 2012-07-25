(ns trakr.plugins.users
  (:use [somnium.congomongo :only [update!]]
        [onycloud.middleware :only [*authenticated-user*]]
        [trakr.db.user :only [new-to-trakr?]]
        [trakr.db.project :only [insert-project]]
        [trakr.db.membership :only [insert-membership]]
        [trakr.db.issue :only [insert-issue-and-update]]))

(def tasks
  [{:title "Lesson one: Edit this issue"
    :desc "
1. Click on the content you intend to edit, the editable content has a yellow background when hovered on.
2. Play with this issue in edit mode.
3. Click **Save** button to save the change."
    :priority "medium"
    :status "new"
    :type "chore"}
   {:title "Lesson two: Create a new issue"
    :desc "
1. Click on the **tutorial-for-<your name>** link just below Trakr logo on the top-left part of the page.
2. Find and click the **Add new issue** button on the issue listing page you just arrived.
3. Write an issue subject for your new issue, press **Enter** when done."
    :priority "medium"
    :status "new"
    :type "chore"}
   {:title "Lesson three: Create a new project!"
    :desc "
1. Click the Trakr logo on the top-left part of this page to go to dashboard page.
2. Find and click on the **Create new project** button in the sidebar on the right.
3. Give your project a name."
    :priority "medium"
    :status "new"
    :type "chore"}
   {:title "Lesson four: Create a new milestone!"
    :desc "
1. Open the **milestone** drop down menu on the top-right part of the issue listing page.
2. Select **-- add new --**
3. Give your milestone a name."
    :priority "medium"
    :status "new"
    :type "chore"}
   {:title "Lesson five: Create more issues"
    :desc "You probably already know how to do it :)"
    :priority "medium"
    :status "new"
    :type "chore"}
   {:title "Lesson six: Sort issues"
    :desc "
1. Go to the issue listing page by clicking the project name on the top-left part of this page.
2. Click on the head of any column to sort issues by that column, click it again to reverse the order."
    :priority "medium"
    :status "new"
    :type "chore"}
   {:title "Lesson seven: got too many issues? filter them!"
    :desc "
1. Go to the issue listing page.
2. play with the **filter links** above the issue table, you can filter issues by assignee, issue type, status and priority. Of course you can search your issues using the search box."
    :priority "medium"
    :status "new"
    :type "chore"}
   ])

(defn- get-tutorial []
  {:name (str "tutorial-for-" (:shortname *authenticated-user*))
   :creator_name (:shortname *authenticated-user*)
   :max_issue_id 0})

(defn- insert-tasks [p]
  (doseq [t tasks] (insert-issue-and-update t (:name p))))

(defn insert-tutorial []
  (let [user-id (:_id *authenticated-user*)]
    (when (and (new-to-trakr? :user-id user-id)
               (:shortname *authenticated-user*))
      (let [proj (insert-project (get-tutorial))]
        (insert-membership {:user_id (str user-id)
                            :project_id (:id proj)
                            :role "admin"})
        (insert-tasks proj)
        (update! :users {:_id user-id}
                 {:$set {:trakr_tutorial_inserted true}})))))

