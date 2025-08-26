(ns border-blur.core
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.resource :refer [wrap-resource]]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [border-blur.handlers :as handlers])
  (:gen-class))

(defroutes app-routes
  (GET "/" [] handlers/home-page)
  (POST "/start-game" [] handlers/start-game)
  (GET "/game/:session-id" [session-id :as request] (handlers/game-page session-id request))
  (POST "/game/:session-id/answer" [session-id :as request] (handlers/submit-answer session-id request))
  (POST "/game/:session-id/next" [session-id :as request] (handlers/next-image session-id request))
  (GET "/game/:session-id/results" [session-id :as request] (handlers/results-page session-id request))
  (GET "/boundaries" [] handlers/boundaries-page) ; New boundaries visualization endpoint
  (GET "/image-locations" [] handlers/image-locations-page) ; Image locations map endpoint
  (route/resources "/")
  (route/not-found "Page not found"))

(def app
  (-> app-routes
      (wrap-defaults (-> site-defaults
                         (assoc-in [:security :anti-forgery] false)
                         (assoc-in [:session] false))) ; No cookies/sessions
      (wrap-resource "public")))

(defn start-server
  "Start the server on given port"
  [port]
  (println (str "Starting Border Blur server on port " port))
  (jetty/run-jetty app {:port port :join? false}))

(defn -main [& [port]]
  "Main entry point with port configuration"
  (try
    (let [port-num (if port
                     (Integer/parseInt port)
                     3000)]
      (when (or (< port-num 1024) (> port-num 65535))
        (println "Warning: Port should be between 1024-65535")
        (println "Usage: clojure -M:run [PORT]")
        (System/exit 1))
      (println (str "🌆 Border Blur - Tel Aviv Geography Game"))
      (println (str "Starting server on port " port-num))
      (println (str "Visit: http://localhost:" port-num))
      (start-server port-num))
    (catch NumberFormatException e
      (println "Error: Port must be a number")
      (println "Usage: clojure -M:run [PORT]")
      (println "Example: clojure -M:run 3001")
      (System/exit 1))
    (catch Exception e
      (println "Error starting server:" (.getMessage e))
      (System/exit 1))))