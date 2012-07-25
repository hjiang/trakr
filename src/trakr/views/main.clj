(ns trakr.views.main
  (:use [net.cgrand.enlive-html :only [deftemplate attr-has substitute
                                       content append html-snippet]]
        [onycloud.config :only [in-prod?]]
        [onycloud.handlers.util :only [serialize-to-js]]))

(deftemplate main-tpl "templates/trakr/index.html" [data]
  [(attr-has :onycloud-env "development")] (if (in-prod?)
                                             (substitute "")
                                             identity)
  [(attr-has :onycloud-env "production")] (if (in-prod?)
                                            identity
                                            (substitute ""))
  [:#fortune] (content (:fortune data))
  [:head] (append (html-snippet (serialize-to-js data))))

(defn main [data] (apply str (main-tpl data)))
