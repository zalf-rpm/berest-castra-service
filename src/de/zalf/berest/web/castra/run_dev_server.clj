(ns de.zalf.berest.web.castra.run-dev-server
  (:gen-class)
  (require [ring.server.standalone :as ring-server]
           [de.zalf.berest.web.castra.handler :as handler]))

(defn -main
  []
  (ring-server/serve handler/castra-service {:port 3000
                                             :open-browser? false
                                             :auto-reload? true
                                             :reload-paths ["~/development/berest-core/src"
                                                            "~/development/berest-service/castra/src"
                                                            #_"D:\\git-repos\\github\\bergm\\berest-core\\src"
                                                            #_"D:\\git-repos\\github\\bergm\\berest-service\\castra\\src"]}))

#_(-main)