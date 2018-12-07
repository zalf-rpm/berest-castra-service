(defproject
  de.zalf.berest/berest-castra-service "0.2.3"

  :description "BEREST CASTRA service"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]

                 [de.zalf.berest/berest-core "0.2.3"]

                 [compojure "1.4.0"]
                 [hoplon/castra "3.0.0-alpha3"]

                 [com.datomic/datomic-free "0.9.5697"]

                 [clojurewerkz/quartzite "2.0.0"]

                 #_[org.zeromq/jeromq "0.3.5"]
                 #_[org.zeromq/cljzmq "0.1.4" :exclusions [org.zeromq/jzmq]]

                 [ring "1.4.0"]
                 [ring-server "0.4.0"]
                 [jumblerg/ring.middleware.cors "1.0.1"]
                 [fogus/ring-edn "0.3.0"]

                 [simple-time "0.2.1"]
                 [clj-time "0.11.0"]

                 [clojure-csv "2.0.1"]
                 [org.clojure/core.match "0.2.2"]]

  :jvm-opts ["-Xmx1g"
             "-Dberest.datomic.url=datomic:free://localhost:4334/berest/"
             "-Dimport.ftp.dwd.url=ftp://anonymous@tran.zalf.de/pub/net/wetter"]

  :plugins [[lein-ring "0.12.0"]]

  :min-lein-version "2.0.0"

  :source-paths ["src"]
  :resource-paths ["resources"]

  :profiles {:dev {:dependencies []
                   :resource-paths []}}

  :ring {:handler de.zalf.berest.web.castra.handler/castra-service
         :init de.zalf.berest.core.import.dwd-data/start-import-scheduler
         :destroy de.zalf.berest.core.import.dwd-data/stop-import-scheduler}


  :main de.zalf.berest.web.castra.run-dev-server)













