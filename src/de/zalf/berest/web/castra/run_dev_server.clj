(ns de.zalf.berest.web.castra.run-dev-server
  (:gen-class)
  (require [ring.server.standalone :as ring-server]
           [de.zalf.berest.web.castra.handler :as handler]))

(defn -main
  []
  (ring-server/serve handler/castra-service {:port 3000
                                             :open-browser? false
                                             :auto-reload? true
                                             :reload-paths [#_"/home/mib/development/github/zalf-lsa/berest-core/src"
                                                            #_"/home/mib/development/github/zalf-lsa/berest-castra-service/src"
                                                            "C:\\Users\\berg\\Documents\\GitHub\\berest-core\\src"
                                                            "C:\\Users\\berg\\Documents\\GitHub\\berest-castra-service\\src"]}))

#_(-main)