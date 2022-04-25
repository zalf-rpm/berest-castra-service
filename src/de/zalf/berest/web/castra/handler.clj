(ns de.zalf.berest.web.castra.handler
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.file-info :refer [wrap-file-info]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [castra.middleware :as cm]
            [compojure.core :as c]
            [compojure.route :as route]
            [ring.util.response :as ring-resp]))

(def server (atom nil))

#_(defn wrap-access-control-allow-*
  [handler]
  (fn [request]
    (when-let [response (handler request)]
      (-> response
          (ring-resp/header ,,, "Access-Control-Allow-Origin" "*")
          #_(ring-resp/header ,,, "Access-Control-Allow-Headers" "origin, x-auth-token, x-csrf-token, content-type, accept")
          #_(ring-resp/header ,,, "X-Dev-Mode" "true")
          #_(#(do (println %) %))))))

(defn wrap-access-control-allow-*
  [handler]
  (fn [request]
    (when-let [response (handler request)]
      (-> response
          (ring-resp/header ,,, "Access-Control-Allow-Credentials" "true")
          #_(ring-resp/header ,,, "Access-Control-Allow-Headers" "origin, x-auth-token, x-csrf-token, content-type, accept")
          #_(ring-resp/header ,,, "X-Dev-Mode" "true")
          #_(#(do (println %) %))))))

(defn print**
  [handler]
  (fn [request]
    (println "request: " (pr-str request))
    (let [response (handler request)]
      (println "response: " (pr-str response))
      response)))

(c/defroutes
  app-routes
  #_(c/GET "/" req (ring-resp/content-type (ring-resp/resource-response "index.html") "text/html"))
  (route/resources "/" {:root ""}))

(def castra-service
  (-> app-routes
      (#(print** %))
      (cm/wrap-castra ,,, 'de.zalf.berest.web.castra.api)
      (#(print** %))
      (wrap-session ,,, {:store (cookie-store {:key "a 16-byte secret"})})
      wrap-content-type
      #_(wrap-file "../berest-hoplon-client/target")
      (wrap-file "../berest-hoplon-client/assets")
      wrap-access-control-allow-*
      wrap-not-modified
      #_(#(print** 3 %))))

#_(-> app-routes
      (cm/wrap-castra ,,, 'de.zalf.berest.web.castra.api)
      #_(cm/wrap-castra-session ,,, "a 16-byte secret")
      (wrap-session ,,, {:store (cookie-store {:key "a 16-byte secret"})})
      #_(wrap-file "resources/public")
      (wrap-file "../berest-hoplon-client/target")
      (wrap-resource ,,, "public")
      (wrap-resource ,,, "website")
      (wrap-cors ,,, #".*")
      #_print**
      #_(wrap-cors ,,, :access-control-allow-origin [#".*"]
                     :access-control-allow-methods [:post])
      wrap-access-control-allow-*
      #_print**
      wrap-not-modified
      wrap-content-type)

(defn app [port public-path]
  (-> app-routes
      (cm/wrap-castra ,,, 'de.zalf.berest.web.castra.api)
      #_(cm/wrap-castra-session ,,, "a 16-byte secret")
      (wrap-session ,,, {:store (cookie-store {:key "a 16-byte secret"})})
      (wrap-file ,,, public-path)
      (wrap-file-info)
      (run-jetty {:join? false
                  :port port})))

(defn start-server
  "Start castra demo server (port 33333)."
  [port public-path]
  (swap! server #(or % (app port public-path))))

(defn run-task
  [port public-path]
  (.mkdirs (java.io.File. public-path))
  (start-server port public-path)
  (fn [continue]
    (fn [event]
      (continue event))))

(defn -main
  [& args])

