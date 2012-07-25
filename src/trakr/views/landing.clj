(ns trakr.views.landing
  (:use [net.cgrand.enlive-html :only [deftemplate attr-has substitute]]
        [onycloud.config :only [in-prod?]]))

(deftemplate landing-tpl "templates/trakr/landing.html" []
  [(attr-has :onycloud-env "development")] (if (in-prod?)
                                             (substitute "")
                                             identity)
  [(attr-has :onycloud-env "production")] (if (in-prod?)
                                            identity
                                            (substitute "")))

(deftemplate unsupported-browser-tpl "templates/trakr/unsupported_browser.html"
  [])

(defn landing [] (apply str (landing-tpl)))

(defn unsupported-browser [] (apply str (unsupported-browser-tpl)))
