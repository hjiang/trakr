(defproject trakr "0.1"
  :description "A light-weight bug tracker"
  :dependencies [[compojure "0.6.5"]
                 [clojure "1.2.1"]
                 [onycloud-middleware "0.0.1-SNAPSHOT"]
                 [org.clojure/java.jdbc "0.0.6"]
                 [org.clojure/data.json "0.1.1"]
                 [org.clojure/tools.logging "0.2.0"]
                 [congomongo "0.1.4-SNAPSHOT"]
                 [enlive "1.0.0"]
                 [postgresql/postgresql "9.0-801.jdbc4"]
                 [commons-dbcp/commons-dbcp "1.4"]
                 [hiccup "0.3.5"]
                 [ring/ring-core "0.3.11"]
                 [ring/ring-devel "0.3.11"]
                 [ring/ring-jetty-adapter "0.3.11"]
                 [sandbar/sandbar "0.4.0-SNAPSHOT"]
                 [clj-http "0.1.3"]
                 [clj-time "0.3.0"]
                 [commons-io/commons-io "2.0"]
                 [org.apache.commons/commons-email "1.2"]
                 [org.pegdown/pegdown "0.9.2"]
                 [org.expressme/JOpenId "1.08"]
                 [dk.ative/docjure "1.5.0-SNAPSHOT"]
                 [org.clojars.ghoseb/cron4j "2.2.1"]]
  :test-selectors {:unit (fn [t] (not (:integration t)))
                   :integration :integration
                   :default (constantly true)}
  :jvm-opts ["-XX:+UseCompressedOops"
             "-XX:+TieredCompilation"
             "-XX:MaxPermSize=156m"]
  :repositories {"scala-tools" "http://scala-tools.org/repo-releases/"
                 "java-dot-net" "http://download.java.net/maven/2"}
  :dev-dependencies [[clj-webdriver "0.2.9"]
                     [commons-httpclient/commons-httpclient "3.1"]
                     [criterium "0.1.0"]
                     [org.clojars.onycloud/lein-control "0.1.1-SNAPSHOT"]
                     [lein-difftest "1.3.3"]
                     [lein-marginalia "0.6.0"]
                     [robert/hooke "1.1.2"]
                     [swank-clojure "1.4.0-SNAPSHOT"]]
  :hooks [leiningen.hooks.difftest])
