(ns border-blur.borough-game
  "NYC Borough identification game - 'Which Borough is it?'"
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

;; Game sessions stored in memory
(def game-sessions (atom {}))

;; Available boroughs for the game
(def boroughs [:manhattan :brooklyn :queens :bronx :staten-island])

(def borough-display-names
  {:manhattan "Manhattan"
   :brooklyn "Brooklyn"
   :queens "Queens"
   :bronx "Bronx"
   :staten-island "Staten Island"})

(def borough-colors
  {:manhattan "#DC2626" ; Dark red
   :brooklyn "#059669" ; Dark green
   :queens "#1D4ED8" ; Dark blue
   :bronx "#7C2D12" ; Dark brown
   :staten-island "#A16207" ; Dark amber
   :unclassified "#4B5563"}) ; Dark grey

(defn generate-session-id []
  "Generate a unique session ID"
  (str (java.util.UUID/randomUUID)))

(defn sanitize-input
  "Sanitize user input to prevent XSS"
  [input]
  (when input
    (let [str-input (str input)]
      (-> str-input
          (str/replace #"<" "&lt;")
          (str/replace #">" "&gt;")
          (str/replace #"\"" "&quot;")
          (str/replace #"'" "&#x27;")
          (str/replace #"/" "&#x2F;")
          (as-> s (subs s 0 (min 200 (count s))))))))

(defn load-image-data
  "Load NYC street view images from JSON file"
  []
  (let [random-file "data/random-classified-images.json"
        grid-file "data/nyc-images.json"]
    (cond
      (.exists (io/file random-file))
      (try
        (json/parse-string (slurp random-file) true)
        (catch Exception e
          (println "Error loading random image data:" (.getMessage e))
          []))

      (.exists (io/file grid-file))
      (try
        (json/parse-string (slurp grid-file) true)
        (catch Exception e
          (println "Error loading grid image data:" (.getMessage e))
          []))

      :else [])))

(defn get-difficulty-for-stage
  "Determine difficulty level based on current stage"
  [stage total-stages]
  (cond
    (<= stage 5) :easy
    (<= stage 10) :medium
    :else :hard))

(defn select-image-for-difficulty
  "Select an appropriate image based on difficulty level"
  [difficulty images]
  (let [available-images (shuffle images)]
    ;; For POC, just return random image - can enhance with border proximity logic later
    (first available-images)))

(defn generate-current-image
  "Generate current image for the game stage"
  [stage total-stages images]
  (let [difficulty (get-difficulty-for-stage stage total-stages)
        selected-image (select-image-for-difficulty difficulty images)]
    (when selected-image
      (assoc selected-image
             :difficulty difficulty
             :correct-borough (keyword (:borough selected-image))))))

(defn new-game
  "Create a new borough game session"
  [user-familiarity]
  (let [session-id (generate-session-id)
        sanitized-familiarity (sanitize-input user-familiarity)
        validated-familiarity (if (str/blank? sanitized-familiarity)
                                "Unknown"
                                sanitized-familiarity)
        images (load-image-data)
        total-stages 15]
    {:session-id session-id
     :user-familiarity validated-familiarity
     :current-stage 1
     :total-stages total-stages
     :score 0
     :streak 0
     :current-image (generate-current-image 1 total-stages images)
     :answer-revealed false
     :start-time (System/currentTimeMillis)
     :stages []
     :images images}))

(defn get-game-session [session-id]
  "Retrieve game session by ID"
  (get @game-sessions session-id))

(defn save-game-session! [game-session]
  "Save game session to memory"
  (swap! game-sessions assoc (:session-id game-session) game-session)
  game-session)

(defn calculate-score
  "Calculate points for borough answer"
  [correct? difficulty streak]
  (let [base-points (if correct? 15 0)
        difficulty-bonus (case difficulty
                           :easy 0
                           :medium 10
                           :hard 20)
        streak-multiplier (min 2.5 (+ 1.0 (* streak 0.15)))]
    (int (* (+ base-points difficulty-bonus) streak-multiplier))))

(defn process-answer
  "Process user's borough selection and reveal result"
  [session-id user-answer]
  (when-let [game-session (get-game-session session-id)]
    (when-let [current-image (:current-image game-session)]
      (let [correct-borough (:correct-borough current-image)
            user-borough (keyword user-answer)
            correct? (= user-borough correct-borough)
            difficulty (:difficulty current-image)
            new-streak (if correct? (inc (:streak game-session)) 0)
            points (calculate-score correct? difficulty (:streak game-session))

            updated-session (-> game-session
                                (assoc :answer-revealed true)
                                (update :score + points)
                                (assoc :streak new-streak)
                                (update :stages conj {:stage (:current-stage game-session)
                                                      :user-answer user-borough
                                                      :correct-answer correct-borough
                                                      :correct? correct?
                                                      :points points
                                                      :image current-image
                                                      :timestamp (System/currentTimeMillis)}))]

        (save-game-session! updated-session)
        {:correct? correct?
         :points points
         :streak new-streak
         :current-image current-image
         :correct-borough correct-borough
         :user-borough user-borough
         :answer-revealed true}))))

(defn advance-to-next-stage
  "Advance to next stage after answer reveal"
  [session-id]
  (when-let [game-session (get-game-session session-id)]
    (let [next-stage (inc (:current-stage game-session))
          game-over? (> next-stage (:total-stages game-session))
          images (:images game-session)

          updated-session (if game-over?
                            (-> game-session
                                (assoc :answer-revealed false)
                                (assoc :current-image nil))
                            (-> game-session
                                (assoc :current-stage next-stage)
                                (assoc :answer-revealed false)
                                (assoc :current-image
                                       (generate-current-image next-stage
                                                               (:total-stages game-session)
                                                               images))))]

      (save-game-session! updated-session)
      {:game-over? game-over?
       :current-stage (:current-stage updated-session)
       :total-stages (:total-stages updated-session)
       :current-image (:current-image updated-session)
       :score (:score updated-session)})))

(defn cleanup-old-sessions!
  "Remove sessions older than 2 hours"
  []
  (let [two-hours-ago (- (System/currentTimeMillis) (* 2 60 60 1000))
        old-sessions (filter (fn [[id session]]
                               (< (:start-time session) two-hours-ago))
                             @game-sessions)]
    (doseq [[id _] old-sessions]
      (swap! game-sessions dissoc id))
    (count old-sessions)))