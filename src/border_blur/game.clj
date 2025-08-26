(ns border-blur.game
  (:require [clojure.string :as str]
            [border-blur.images.selector :as selector]))

;; Game sessions stored in memory (no cookies)
(def game-sessions (atom {}))

(defn generate-session-id []
  "Generate a unique session ID"
  (str (java.util.UUID/randomUUID)))

(defn sanitize-input
  "Sanitize user input to prevent XSS and injection attacks"
  [input]
  (when input
    (let [str-input (str input)]
      (-> str-input
          (str/replace #"<" "&lt;") ; HTML encode
          (str/replace #">" "&gt;")
          (str/replace #"\"" "&quot;")
          (str/replace #"'" "&#x27;")
          (str/replace #"/" "&#x2F;")
          (as-> s (subs s 0 (min 200 (count s)))))))) ; Limit to 200 chars ; Limit to 200 chars

(defn new-game [user-city]
  "Create a new game session for Tel Aviv geography game with input validation"
  (let [session-id (generate-session-id)
        ;; Sanitize and validate user input
        sanitized-city (sanitize-input user-city)
        validated-city (if (str/blank? sanitized-city)
                         "Unknown User"
                         sanitized-city)]
    {:session-id session-id
     :user-city validated-city ; Sanitized and validated
     :current-stage 1
     :total-stages 20 ; Full 20-stage game
     :score 0
     :streak 0
     :current-image nil ; Will hold single image + Tel Aviv answer
     :answer-revealed false ; New state for reveal screen
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

(def max-sessions-per-ip 10)
(def max-total-sessions 10000)

(defn cleanup-old-sessions!
  "Remove sessions older than 2 hours to prevent memory exhaustion"
  []
  (let [two-hours-ago (- (System/currentTimeMillis) (* 2 60 60 1000))
        old-sessions (filter (fn [[id session]]
                               (< (:start-time session) two-hours-ago))
                             @game-sessions)]
    (doseq [[id _] old-sessions]
      (swap! game-sessions dissoc id))
    (count old-sessions)))

(defn enforce-session-limits!
  "Enforce limits on total sessions to prevent DoS"
  []
  (when (> (count @game-sessions) max-total-sessions)
    ;; Remove oldest sessions
    (let [sorted-sessions (sort-by (fn [[_ session]] (:start-time session)) @game-sessions)
          to-remove (take (- (count @game-sessions) (* max-total-sessions 0.8)) sorted-sessions)]
      (doseq [[id _] to-remove]
        (swap! game-sessions dissoc id)))))

(defn generate-single-image [user-city current-stage total-stages]
  "Generate a single image for Tel Aviv geography game"
  (selector/get-single-tel-aviv-image {:user-city user-city
                                       :current-stage current-stage
                                       :total-stages total-stages}))

(defn calculate-score [correct? difficulty streak]
  "Calculate points for Tel Aviv binary choice with difficulty bonus"
  (let [base-points (if correct? 10 0)
        difficulty-bonus (case difficulty
                           :easy 0
                           :medium 5
                           :hard 10)
        streak-multiplier (min 2.0 (+ 1.0 (* streak 0.2)))]
    (int (* (+ base-points difficulty-bonus) streak-multiplier))))

(defn process-answer [session-id user-answer]
  "Process user's binary Tel Aviv answer and reveal result"
  (when-let [game-session (get-game-session session-id)]
    (when-let [current-image (:current-image game-session)]
      (let [correct? (= user-answer (:is-in-tel-aviv current-image))
            difficulty (:difficulty current-image)
            new-streak (if correct? (inc (:streak game-session)) 0)
            points (calculate-score correct? difficulty (:streak game-session))

            updated-session (-> game-session
                                (assoc :answer-revealed true)
                                (update :score + points)
                                (assoc :streak new-streak)
                                (update :stages conj {:stage (:current-stage game-session)
                                                      :user-answer user-answer
                                                      :correct-answer (:is-in-tel-aviv current-image)
                                                      :correct? correct?
                                                      :points points
                                                      :image current-image
                                                      :timestamp (System/currentTimeMillis)}))]

        (save-game-session! updated-session)
        {:correct? correct?
         :points points
         :streak new-streak
         :current-image current-image
         :answer-revealed true}))))

(defn advance-to-next-image [session-id]
  "Advance to next stage after answer reveal"
  (when-let [game-session (get-game-session session-id)]
    (let [next-stage (inc (:current-stage game-session))
          game-over? (> next-stage (:total-stages game-session))

          updated-session (if game-over?
                            (-> game-session
                                (assoc :answer-revealed false)
                                (assoc :current-image nil))
                            (-> game-session
                                (assoc :current-stage next-stage)
                                (assoc :answer-revealed false)
                                (assoc :current-image
                                       (generate-single-image (:user-city game-session)
                                                              next-stage
                                                              (:total-stages game-session)))))]

      (save-game-session! updated-session)
      {:game-over? game-over?
       :current-stage (:current-stage updated-session)
       :total-stages (:total-stages updated-session)
       :current-image (:current-image updated-session)
       :score (:score updated-session)})))

(defn get-or-generate-current-image [session-id]
  "Get current image or generate new one for the current stage"
  (when-let [game-session (get-game-session session-id)]
    (if (:current-image game-session)
      (:current-image game-session)
      (let [new-image (generate-single-image (:user-city game-session)
                                             (:current-stage game-session)
                                             (:total-stages game-session))
            updated-session (assoc game-session :current-image new-image)]
        (save-game-session! updated-session)
        new-image))))