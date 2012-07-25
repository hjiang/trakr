(ns trakr.handlers.main
  (:require [trakr.views.main :as views]
            [trakr.handlers.misc :as misc]
            [trakr.handlers.users :as user]
            [trakr.config :as cfg]))

(defn main [req]
  (views/main
   (assoc (misc/fetch-fortune)
     :viewer (user/viewer-info {}))))
