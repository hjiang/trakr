(ns trakr.handlers.landing
  (:use [onycloud.middleware :only [*authenticated-user*]]
        [ring.util.response :only [redirect]])
  (:require [trakr.views.landing :as views]))

(defn landing [_]
  (if *authenticated-user*
    (redirect "/trakr/a")
    (views/landing)))

(defn unsupported-browser [_]
  (views/unsupported-browser))
