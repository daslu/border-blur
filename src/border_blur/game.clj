(ns border-blur.game
  (:require [clojure.string :as str]
            [border-blur.images.selector :as selector]))

;; Game sessions stored in memory (no cookies)
(def game-sessions (atom {}))

(defn generate-session-id []
  "Generate a unique session ID"
  (str (java.util.UUID/randomUUID)))

(defn new-game [user-city]
  "Create a new game session"
  (let [session-id (generate-session-id)]
    {:session-id session-id
     :user-city user-city
     :current-stage 1
     :total-stages 20
     :score 0
     :streak 0
     :start-time (System/currentTimeMillis)
     :stages []}))

(defn get-game-session [session-id]
  "Retrieve game session by ID"
  (get @game-sessions session-id))

(defn update-game-session! [session-id update-fn]
  "Update a game session atomically"
  (swap! game-sessions update session-id update-fn))

(defn save-game-session! [game-session]
  "Save game session to memory"
  (swap! game-sessions assoc (:session-id game-session) game-session)
  game-session)

(defn generate-image-pair [user-city current-stage total-stages]
  "Generate a pair of images for the current stage using the smart selector"
  (selector/get-next-image-pair {:user-city user-city
                                 :current-stage current-stage
                                 :total-stages total-stages}))

(defn calculate-score [user-answer correct-answer streak]
  "Calculate points for this stage"
  (let [base-points (if (= user-answer correct-answer) 10 0)
        streak-bonus (min (* streak 2) 10)]
    (+ base-points streak-bonus)))

(defn process-answer [session-id user-answer]
  "Process user's answer and update game state"
  (when-let [game-session (get-game-session session-id)]
    (let [;; Get the current image pair from session or generate new one
          current-pair (or (:current-pair game-session)
                           (generate-image-pair (:user-city game-session)
                                                (:current-stage game-session)
                                                (:total-stages game-session)))
          correct? (= user-answer (:correct-answer current-pair))
          new-streak (if correct? (inc (:streak game-session)) 0)
          points (calculate-score user-answer (:correct-answer current-pair) (:streak game-session))

          updated-session (-> game-session
                              (update :current-stage inc)
                              (update :score + points)
                              (assoc :streak new-streak)
                              (dissoc :current-pair) ; Clear current pair after processing
                              (update :stages conj {:stage (:current-stage game-session)
                                                    :user-answer user-answer
                                                    :correct-answer (:correct-answer current-pair)
                                                    :correct? correct?
                                                    :points points
                                                    :images current-pair
                                                    :timestamp (System/currentTimeMillis)}))]

      (save-game-session! updated-session)
      {:correct? correct?
       :points points
       :streak new-streak
       :game-over? (>= (:current-stage updated-session) (:total-stages updated-session))
       :current-stage (:current-stage updated-session)
       :total-stages (:total-stages updated-session)})))