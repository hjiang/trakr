(ns trakr.import.util
  (:import java.text.SimpleDateFormat
           java.sql.Timestamp))

(defn parse-date [str]
  (let [formater (SimpleDateFormat. "yyyy/MM/dd HH:mm:ss zzz")]
    (Timestamp. (.. formater (parse str) getTime))))
