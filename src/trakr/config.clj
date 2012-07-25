(ns trakr.config
  (:use [clj-time.core :only [date-time now within? interval]]
        [clojure.contrib.seq-utils :only [find-first]])
  (:import [java.sql Timestamp]))

(defonce WATCHED-UPDATES-LIMIT 50)
(defonce FORTUNE-PATH "resources/fortune")
