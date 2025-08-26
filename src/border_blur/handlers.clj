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
  "Main game interface showing two images"
  (if-let [game-session (game/get-game-session session-id)]
    (if (>= (:current-stage game-session) (:total-stages game-session))
      ;; Game is over, redirect to results
      (response/redirect (str "/game/" session-id "/results"))
      ;; Show current stage
      (let [;; Generate and store image pair in session
            image-pair (game/generate-image-pair (:user-city game-session)
                                                 (:current-stage game-session)
                                                 (:total-stages game-session))
            ;; Update session with current pair
            updated-session (assoc game-session :current-pair image-pair)]
        (game/save-game-session! updated-session)
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body (views/game-page updated-session image-pair)}))
    ;; Session not found
    {:status 404
     :headers {"Content-Type" "text/html"}
     :body (views/error-page "Game session not found. Please start a new game.")}))

(defn submit-answer [session-id request]
  "Process user's answer to current stage"
  (let [params (:params request)
        user-answer (keyword (get params "answer"))] ; :same or :different

    (if-let [result (game/process-answer session-id user-answer)]
      (if (:game-over? result)
        ;; Game finished, redirect to results
        (response/redirect (str "/game/" session-id "/results"))
        ;; Continue to next stage
        (response/redirect (str "/game/" session-id)))
      ;; Error processing answer
      {:status 400
       :headers {"Content-Type" "text/html"}
       :body (views/error-page "Error processing your answer.")})))

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