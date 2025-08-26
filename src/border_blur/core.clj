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
  (GET "/game/:session-id/results" [session-id :as request] (handlers/results-page session-id request))
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
  (let [port (Integer/parseInt (or port "3000"))]
    (start-server port)))