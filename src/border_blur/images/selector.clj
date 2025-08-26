(ns border-blur.images.selector
  (:require [border-blur.images.fetcher :as fetcher]
            [border-blur.gis.boundaries :as boundaries]
            [border-blur.gis.cities :as cities]
            [geo.jts :as jts]
            [geo.spatial :as spatial]))

;; Cache for border points and images
(def border-cache (atom {}))
(def image-cache (atom {}))

(defn cache-key [city-a-key city-b-key]
  "Generate cache key for city pair"
  (let [sorted (sort [city-a-key city-b-key])]
    (str (first sorted) "-" (second sorted))))

(defn get-cached-border-points [city-a-key city-b-key]
  "Retrieve cached border points for city pair"
  (get @border-cache (cache-key city-a-key city-b-key)))

(defn cache-border-points! [city-a-key city-b-key points]
  "Cache border points for reuse"
  (swap! border-cache assoc (cache-key city-a-key city-b-key) points))

(defn select-diverse-images
  "Select images that are visually diverse for better gameplay"
  [images num-images]
  ;; For now, random selection. Later: add visual similarity scoring
  (take num-images (shuffle images)))

(defn create-fallback-image-pair
  "Create realistic placeholder image pair when real images can't be fetched"
  [city-a city-b difficulty-level answer-type]
  (let [city-a-name (or (:name city-a) (name city-a))
        city-b-name (or (:name city-b) (name city-b))
        ;; Different image styles based on difficulty
        style-a (case difficulty-level
                  :easy "4A90E2/FFFFFF" ; Blue background
                  :medium "F39C12/FFFFFF" ; Orange background  
                  :hard "E74C3C/FFFFFF") ; Red background
        style-b (if (= answer-type :same)
                  style-a ; Same color for same city
                  "2ECC71/FFFFFF")] ; Green for different city
    {:left {:url (str "https://via.placeholder.com/400x300/" style-a "?text="
                      (java.net.URLEncoder/encode (str city-a-name " Street View") "UTF-8"))
            :city city-a-name
            :coords (or (:center city-a) [0 0])}
     :right {:url (str "https://via.placeholder.com/400x300/" style-b "?text="
                       (java.net.URLEncoder/encode (str city-b-name " Street View") "UTF-8"))
             :city city-b-name
             :coords (or (:center city-b) [0 0])}
     :correct-answer answer-type
     :difficulty difficulty-level
     :fallback true}))

(defn prepare-image-pair
  "Prepare an image pair for game presentation"
  [city-a city-b difficulty-level]
  (let [city-a-data (cities/get-city cities/cities city-a)
        city-b-data (cities/get-city cities/cities city-b)]

    (if (and city-a-data city-b-data)
      ;; Try to create real image pair, fallback to placeholder
      (try
        ;; For now, just use fallback since real API integration needs more work
        (create-fallback-image-pair city-a-data city-b-data difficulty-level :different)
        (catch Exception e
          (println "Error creating image pair:" (.getMessage e))
          (create-fallback-image-pair city-a-data city-b-data difficulty-level :different)))

      ;; Cities not found, create basic fallback
      (create-fallback-image-pair
       {:name (name city-a) :center [0 0]}
       {:name (name city-b) :center [0 0]}
       difficulty-level :different))))

(defn prepare-same-city-pair
  "Prepare two images from the same city"
  [city difficulty-level]
  (let [city-data (cities/get-city cities/cities city)]
    (if city-data
      (try
        ;; Create two different views of the same city
        {:left {:url (str "https://via.placeholder.com/400x300/4A90E2/FFFFFF?text="
                          (java.net.URLEncoder/encode (str (:name city-data) " Area A") "UTF-8"))
                :city (:name city-data)
                :coords (or (:center city-data) [0 0])}
         :right {:url (str "https://via.placeholder.com/400x300/4A90E2/FFFFFF?text="
                           (java.net.URLEncoder/encode (str (:name city-data) " Area B") "UTF-8"))
                 :city (:name city-data)
                 :coords (or (:center city-data) [0 0])}
         :correct-answer :same
         :difficulty difficulty-level
         :fallback true}
        (catch Exception e
          (println "Error creating same-city pair:" (.getMessage e))
          (create-fallback-image-pair city-data city-data difficulty-level :same)))

      ;; City not found, create basic fallback
      (let [city-name (name city)]
        {:left {:url (str "https://via.placeholder.com/400x300/4A90E2/FFFFFF?text="
                          (java.net.URLEncoder/encode (str city-name " A") "UTF-8"))
                :city city-name
                :coords [0 0]}
         :right {:url (str "https://via.placeholder.com/400x300/4A90E2/FFFFFF?text="
                           (java.net.URLEncoder/encode (str city-name " B") "UTF-8"))
                 :city city-name
                 :coords [0 0]}
         :correct-answer :same
         :difficulty difficulty-level
         :fallback true}))))

(defn generate-smart-image-pair
  "Generate an image pair based on game state and difficulty"
  [user-city current-stage total-stages]
  (let [;; Progressive difficulty
        difficulty (cond
                     (< current-stage 5) :easy
                     (< current-stage 15) :medium
                     :else :hard)

        ;; Mix of same/different (60% different, 40% same for variety)
        should-be-different? (< (rand) 0.6)

        ;; Prioritize user's city early on
        use-user-city? (and user-city (< current-stage 10) (< (rand) 0.4))

        available-cities (keys cities/cities)
        city-pairs (cities/find-city-pairs cities/cities)]

    (if should-be-different?
      ;; Different cities
      (let [pair (if use-user-city?
                  ;; Find a pair involving user's city
                   (first (filter #(some #{(keyword user-city)} %) city-pairs))
                  ;; Random pair
                   (rand-nth city-pairs))]
        (when pair
          (prepare-image-pair (first pair) (second pair) difficulty)))

      ;; Same city
      (let [city (if use-user-city?
                   (keyword user-city)
                   (rand-nth available-cities))]
        (prepare-same-city-pair city difficulty)))))

(defn preload-images-for-session
  "Preload images for smoother gameplay"
  [user-city num-stages]
  ;; Generate multiple image pairs in advance
  (let [pairs (repeatedly num-stages
                          #(generate-smart-image-pair user-city
                                                      (rand-int 20)
                                                      20))]
    (swap! image-cache assoc user-city pairs)
    pairs))

(defn get-next-image-pair
  "Get the next image pair for the game"
  [session]
  (let [user-city (:user-city session)
        current-stage (:current-stage session)
        total-stages (:total-stages session)

        ;; Check if we have preloaded images
        cached-pairs (get @image-cache user-city)]

    (if (and cached-pairs (< current-stage (count cached-pairs)))
      ;; Use preloaded
      (nth cached-pairs (dec current-stage))
      ;; Generate on demand
      (generate-smart-image-pair user-city current-stage total-stages))))