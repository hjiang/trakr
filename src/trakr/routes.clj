(ns trakr.routes
  (:use [compojure.core :only [defroutes GET PUT POST HEAD DELETE ANY
                               context]]
        [clojure.contrib.def :only [defalias]]
        [onycloud.plugins :only [wrap-plugins]]
        [onycloud.middleware.json :only [JSON]]
        [onycloud.middleware :only [*authenticated-user* wrap-host-check]]
        [onycloud.config :only [domains]]
        [sandbar.stateful-session :only [session-get]]
        [trakr.db.issue :only [post-update-issue]]
        [trakr.plugins.issues :only [send-issue-update-email
                                     send-issue-creation-email
                                     send-comment-email]]
        [trakr.plugins.logging :only [log-listing-issues log-visiting-issue]]
        [trakr.handlers.issues :only [post-create-comment
                                      post-create-issue
                                      post-list-issues
                                      post-visit-issue]]
        [trakr.handlers.projects :only [pre-list-projects]]
        [trakr.plugins.users :only [insert-tutorial]]
        [trakr.handlers.main :only [main]]
        [trakr.handlers.landing :only [landing unsupported-browser]])
  (:require [onycloud.util :as util]
            (trakr.handlers [projects :as project]
                            [issues :as issue]
                            [files :as file]
                            [blockages :as blockage]
                            [users :as user]
                            [memberships :as member]
                            [milestones :as milestone]
                            [misc :as misc])))

(defalias ctx context)

;;; Trakr API Status Code Specification
;;;
;;; Appropriate HTTP Status Code should be used to tell clients what
;;; happened. And optionally a top-level attribute "message" in
;;; response JSON body can be used to describe the detail. The
;;; "message" should be meanful, concise (preferably just one
;;; sentence) and end-user friendly since mostly likely it'll be shown
;;; directly to users.
;;;
;;; 201 : Created. POST (create) request succeeded.
;;; 204 : No Content. The server has fulfilled the request but does
;;;       not need to return a body.
;;; 200 : Fallback for all other 2xx class responses.
;;;
;;; 401 : User has not signed in.
;;; 403 : Permission denied. Current signed in user does not have the
;;;       permission to perform the request.
;;; 404 : Not found.
;;; 430*: Server can't proceed because the request did not provide
;;;       enough data. "message" should tell what's missing.
;;; 431*: Server can't proceed due to the uniqueness constraint
;;;       imposed by server, mostly by postgreSQL. "message" should
;;;       tell which uniqueness constrait.
;;; 400*: Fallback for all other 4xx class errors. "message" MUST be
;;;       set to describe the error.
;;;
;;; 500 : Server should never explictly return 500. Clients observing
;;;       500 indicate bugs which should be fixed.
;;;
;;; [*]: "message" should be present as the top-level attribute in the
;;;      JSON response body.

(defroutes trakr-api-routes
  (ctx "/viewer" []
       (JSON GET "/" [] user/viewer-info)
       (JSON PUT "/" [] user/change-settings)
       (ctx "/project-watchings" []
            (JSON GET "/" [] user/fetch-watched-projects)
            (JSON POST "/" [] user/add-project-watching)
            (JSON DELETE ["/:id" :id #"\d+"] [] user/delete-project-watching)))
  (JSON GET "/recently-visited-issues" [] issue/recent-issues)
  (ctx "/users" []
       (JSON PUT "/:user-shortname" [] user/update-user)
       (JSON GET "/ac" []  member/auto-complete-user-source)
       (JSON GET "/:user-shortname/watched-updates" []
             user/watched-updates))
  (ctx "/projects" []
       (JSON GET "/" [] project/list-projects)
       (JSON POST "/" [] project/create-project)
       (ctx ["/:project-name" :project-name #"[^/]+"] []
            (JSON GET "/" [] project/get-project)
            (JSON DELETE "/" [] project/archive-project)
            (JSON PUT "/" [] project/update-project)
            (JSON POST "/import" [] project/import-other-tracker)
            (JSON GET "/tags" [] project/list-tags-by-project)
            (ctx "/memberships" []
                 (JSON GET "/" [] member/list-memberships)
                 (JSON POST "/" [] member/add-membership)
                 (JSON DELETE "/:membership-id" [] member/delete-membership)
                 (JSON PUT "/:membership-id" [] member/update-role))
            (ctx "/milestones" []
                 (JSON GET "/" [] milestone/list-milestones)
                 (JSON POST "/" [] milestone/create-milestone)
                 (JSON PUT "/:milestone-id" [] milestone/update-milestone)
                 (JSON DELETE "/:milestone-id" [] milestone/delete-milestone)
                 (JSON GET "/:milestone-id" [] milestone/fetch-milestone))
            (ctx "/issues" []
                 (JSON GET "/" [] issue/list-issues)
                 (JSON POST "/" [] issue/create-issue)
                 (JSON GET "/ac" [] issue/auto-complete)
                 (ctx ["/:local-id" :local-id #"\d+"] []
                      (JSON GET "/" [] issue/get-issue)
                      (JSON POST "/diffs" [] issue/apply-issue-diffs)
                      (POST "/files-tmp" [] file/save-tmp-file)
                      (GET ["/files/:file-id/:name" :name #".+"] []
                           file/get-file)
                      (JSON PUT "/" [] issue/update-issue)
                      (JSON GET "/updates" [] issue/list-updates)
                      (JSON POST "/updates" [] issue/create-comment)
                      (JSON GET "/tags/ac" [] issue/auto-complete-tag-source)))))
  (JSON ANY "*" _ (constantly {:status 404
                               :body {:message "resource unavailable"}})))

(defroutes naked-trakr-routes
  (GET "/" [] landing)
  (GET "/unsupported_browser" [] unsupported-browser)
  (GET "/a" [] main)
  (GET "/a/" [] main)
  (ctx "/api" [] (ANY "*" [] trakr-api-routes)))

(def rejected-domains (disj domains "trakrapp.com"))

(def trakr-plugins
  {#'post-update-issue [send-issue-update-email]
   #'post-create-comment [send-comment-email]
   #'post-create-issue [send-issue-creation-email]
   #'post-list-issues [log-listing-issues]
   #'post-visit-issue [log-visiting-issue]
   #'pre-list-projects [insert-tutorial]})

(def trakr-routes (-> naked-trakr-routes
                      (wrap-host-check  rejected-domains)
                      (wrap-plugins trakr-plugins)))
