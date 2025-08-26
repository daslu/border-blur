(ns border-blur.handlers
  (:require [ring.util.response :as response]
            [border-blur.game :as game]
            [border-blur.views :as views]))

(defn home-page [request]
  "Landing page where users enter their known city"
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (views/home-page)})

(defn start-game [request]
  "Process city input and create new game session"
  (let [params (:params request)
        user-city (get params "city" "Unknown")
        game-session (game/new-game user-city)
        session-id (:session-id game-session)]

    ;; Save the session
    (game/save-game-session! game-session)

    ;; Redirect to game page with session ID in URL
    (response/redirect (str "/game/" session-id))))

(defn game-page [session-id request]
  "Main game interface showing single image with Tel Aviv question"
  (if-let [game-session (game/get-game-session session-id)]
    (if (>= (:current-stage game-session) (:total-stages game-session))
      ;; Game is over, redirect to results
      (response/redirect (str "/game/" session-id "/results"))
      ;; Show current stage
      (let [current-image (game/get-or-generate-current-image session-id)
            updated-session (game/get-game-session session-id)] ; Get fresh session after image generation
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body (views/game-page updated-session current-image)}))
    ;; Session not found
    {:status 404
     :headers {"Content-Type" "text/html"}
     :body (views/error-page "Game session not found. Please start a new game.")}))

(defn submit-answer [session-id request]
  "Process user's Tel Aviv Yes/No answer and show reveal"
  (let [params (:params request)
        user-answer (= (get params "answer") "yes")] ; Convert to boolean for Tel Aviv

    (if-let [result (game/process-answer session-id user-answer)]
      ;; Show reveal screen with results
      (let [game-session (game/get-game-session session-id)]
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body (views/reveal-page game-session result)})
      ;; Error processing answer
      {:status 400
       :headers {"Content-Type" "text/html"}
       :body (views/error-page "Error processing your answer.")})))

(defn next-image [session-id request]
  "Advance to next image after reveal screen"
  (if-let [result (game/advance-to-next-image session-id)]
    (if (:game-over? result)
      ;; Game finished, redirect to results
      (response/redirect (str "/game/" session-id "/results"))
      ;; Continue to next stage
      (response/redirect (str "/game/" session-id)))
    ;; Error advancing to next image
    {:status 400
     :headers {"Content-Type" "text/html"}
     :body (views/error-page "Error advancing to next image.")}))

(defn results-page [session-id request]
  "Show final game results"
  (if-let [game-session (game/get-game-session session-id)]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (views/results-page game-session)}
    ;; Session not found
    {:status 404
     :headers {"Content-Type" "text/html"}
     :body (views/error-page "Game session not found.")}))

(defn boundaries-page [request]
  "Display all city boundaries on a Leaflet map for visualization and testing"
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (views/boundaries-page)})

(defn image-locations-page [request]
  "Display all collected images on a Leaflet map with city boundaries"
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (views/image-locations-page)})