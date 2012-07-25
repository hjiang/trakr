(ns trakr.handlers.misc
  (:use [clojure.string :only [join]])
  (:require [trakr.config :as cfg]))

(defonce fortune-db (atom nil))

(defn- get-fortune-seq []
  (if (nil? @fortune-db)
    (reset! fortune-db (with-open [rdr (-> cfg/FORTUNE-PATH
                                           java.io.FileReader.
                                           java.io.BufferedReader.)]
                         (let [seq (line-seq rdr)]
                           (doall
                            (map (partial join "\n")
                                 (filter #(not= (first %) "%")
                                         (partition-by #(= "%" %) seq )))))))
    @fortune-db))

(defn fetch-fortune []
  {:fortune (rand-nth (get-fortune-seq))})
