(ns de.zalf.berest.web.castra.run-dev-server
  (:require [ring.server.standalone :as ring-server]
            [de.zalf.berest.web.castra.handler :as handler])
  (:gen-class))

(defn -main
  []
  (ring-server/serve handler/castra-service {:port 3000
                                             :open-browser? false
                                             :auto-reload? true
                                             :reload-paths ["/home/berg/GitHub/berest-core/src"
                                                            "/home/berg/GitHub/berest-castra-service/src"
                                                            #_"C:\\Users\\berg\\Documents\\GitHub\\berest-core\\src"
                                                            #_"C:\\Users\\berg\\Documents\\GitHub\\berest-castra-service\\src"]}))

#_(-main)
