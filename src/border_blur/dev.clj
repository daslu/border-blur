(ns border-blur.dev
  "Development utilities for hot reloading"
  (:require [clojure.tools.namespace.repl :as repl]
            [border-blur.core :as core]))

(def server (atom nil))

(defn start-server
  "Start the server on given port (default 3001)"
  ([] (start-server 3001))
  ([port]
   (when-let [s @server]
     (.stop s)
     (println "Stopped existing server"))
   (let [s (core/start-server port)]
     (reset! server s)
     (println (str "Server started on port " port))
     s)))

(defn stop-server
  "Stop the development server"
  []
  (when-let [s @server]
    (.stop s)
    (reset! server nil)
    (println "Server stopped")))

(defn restart-server
  "Restart the server (useful after code changes)"
  ([] (restart-server 3001))
  ([port]
   (stop-server)
   (start-server port)))

(defn reload
  "Reload changed namespaces"
  []
  (repl/refresh))

(defn reload-and-restart
  "Reload code and restart server"
  ([] (reload-and-restart 3001))
  ([port]
   (stop-server)
   (repl/refresh :after `(start-server ~port))))

;; Set refresh dirs to only track src directory
(repl/set-refresh-dirs "src")

(defn reset
  "Reset the entire system - reload all code and restart server"
  []
  (stop-server)
  (repl/refresh-all :after `(start-server 3001)))

;; Convenience aliases
(def r reload)
(def rr reload-and-restart)
(def start start-server)
(def stop stop-server)
(def restart restart-server)