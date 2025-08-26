(ns border-blur.images.pair-curator
  "Curate image pairs for Border Blur gameplay with difficulty matching
   and same/different city logic."
  (:require [border-blur.images.mapillary-fetcher :as fetcher]
            [border-blur.images.border-finder :as border-finder]
            [clojure.string :as str]))

;; Pair Generation Logic

(defn calculate-visual-similarity
  "Calculate visual similarity score between two images based on location"
  [img1 img2]
  (let [distance (border-finder/haversine-distance
                  {:lat (:lat img1) :lng (:lng img1)}
                  {:lat (:lat img2) :lng (:lng img2)})]
    (cond
      (< distance 100) 0.9 ; Very similar if < 100m apart
      (< distance 300) 0.7 ; Similar if < 300m
      (< distance 500) 0.5 ; Somewhat similar
      (< distance 1000) 0.3 ; Different
      :else 0.1))) ; Very different

(defn group-images-by-city
  "Group images by their origin city based on border point metadata"
  [images]
  ;; Images from border-finder have city information in their border-point
  (group-by (fn [img]
              ;; Extract city from border point metadata
              (or (get-in img [:border-point :city-a])
                  (get-in img [:border-point :city-b])
                  :unknown))
            images))

(defn create-same-city-pair
  "Create a pair of images from the same city"
  [city-images difficulty]
  (when (>= (count city-images) 2)
    (let [;; Filter by difficulty
          difficulty-images (filter #(= (:difficulty %) difficulty) city-images)]
      (when (>= (count difficulty-images) 2)
        (let [shuffled (shuffle difficulty-images)
              img1 (first shuffled)
              img2 (second shuffled)]
          {:type :same
           :difficulty difficulty
           :left-image img1
           :right-image img2
           :similarity (calculate-visual-similarity img1 img2)
           :cities {:left (get-in img1 [:border-point :city-a])
                    :right (get-in img2 [:border-point :city-a])}})))))

(defn create-different-city-pair
  "Create a pair of images from different cities near the border"
  [images-by-city difficulty]
  (let [city-pairs (keys images-by-city)]
    (when (>= (count city-pairs) 2)
      (let [city1 (first city-pairs)
            city2 (second city-pairs)
            city1-images (filter #(= (:difficulty %) difficulty) (get images-by-city city1))
            city2-images (filter #(= (:difficulty %) difficulty) (get images-by-city city2))]
        (when (and (seq city1-images) (seq city2-images))
          (let [img1 (rand-nth city1-images)
                img2 (rand-nth city2-images)]
            {:type :different
             :difficulty difficulty
             :left-image img1
             :right-image img2
             :similarity (calculate-visual-similarity img1 img2)
             :cities {:left city1 :right city2}}))))))

(defn generate-game-pairs
  "Generate pairs for gameplay based on stage and difficulty"
  [images stage-number total-stages]
  (let [difficulty (cond
                     (< stage-number 5) :easy
                     (< stage-number 15) :medium
                     :else :hard)

        ;; Group images by city
        images-by-city (group-images-by-city images)

        ;; 60% different cities, 40% same city (from game requirements)
        pair-type (if (< (rand) 0.6) :different :same)]

    (if (= pair-type :different)
      (create-different-city-pair images-by-city difficulty)
      (let [;; For same city, pick a random city with enough images
            cities-with-enough (filter #(>= (count (val %)) 2) images-by-city)
            city (rand-nth (keys cities-with-enough))]
        (create-same-city-pair (get images-by-city city) difficulty)))))

;; Curation and Quality Control

(defn score-pair-quality
  "Score a pair based on gameplay quality factors"
  [pair]
  (let [base-score 1.0

        ;; Bonus for appropriate difficulty
        difficulty-bonus (case (:difficulty pair)
                           :easy (if (< (:similarity pair) 0.3) 0.5 0) ; Easy should be distinct
                           :medium (if (and (> (:similarity pair) 0.3)
                                            (< (:similarity pair) 0.7)) 0.5 0)
                           :hard (if (> (:similarity pair) 0.7) 0.5 0)) ; Hard should be similar

        ;; Bonus for having POI names
        poi-bonus (if (and (get-in pair [:left-image :poi-name])
                           (get-in pair [:right-image :poi-name]))
                    0.3 0)

        ;; Penalty for missing data
        data-penalty (if (or (nil? (get-in pair [:left-image :url]))
                             (nil? (get-in pair [:right-image :url])))
                       -1.0 0)]

    (+ base-score difficulty-bonus poi-bonus data-penalty)))

(defn curate-best-pairs
  "Select the best pairs from available options"
  [all-pairs target-count]
  (->> all-pairs
       (map #(assoc % :quality-score (score-pair-quality %)))
       (sort-by :quality-score >)
       (take target-count)))

;; Integration with fetched images

(defn prepare-game-session
  "Prepare a complete 20-stage game session with curated pairs"
  [fetched-images]
  (println "\n🎮 Preparing game session with curated pairs...")

  (let [;; Generate pairs for each stage
        stage-pairs (doall
                     (for [stage (range 1 21)]
                       (let [pair (generate-game-pairs fetched-images stage 20)]
                         (assoc pair :stage stage))))

        ;; Filter out nil pairs
        valid-pairs (filter some? stage-pairs)

        ;; Group by difficulty for stats
        by-difficulty (group-by :difficulty valid-pairs)]

    (println (format "  Generated %d valid pairs" (count valid-pairs)))
    (println (format "  Easy: %d | Medium: %d | Hard: %d"
                     (count (:easy by-difficulty))
                     (count (:medium by-difficulty))
                     (count (:hard by-difficulty))))

    {:pairs valid-pairs
     :stats {:total (count valid-pairs)
             :by-difficulty (update-vals by-difficulty count)
             :by-type (frequencies (map :type valid-pairs))}}))

;; Export for game integration

(defn format-for-game
  "Format curated pairs for game engine consumption"
  [pair]
  {:stage (:stage pair)
   :difficulty (:difficulty pair)
   :answer-type (:type pair) ; :same or :different
   :images {:left {:url (get-in pair [:left-image :url])
                   :coords {:lat (get-in pair [:left-image :lat])
                            :lng (get-in pair [:left-image :lng])}
                   :metadata {:poi-name (get-in pair [:left-image :poi-name])
                              :poi-type (get-in pair [:left-image :poi-type])}}
            :right {:url (get-in pair [:right-image :url])
                    :coords {:lat (get-in pair [:right-image :lat])
                             :lng (get-in pair [:right-image :lng])}
                    :metadata {:poi-name (get-in pair [:right-image :poi-name])
                               :poi-type (get-in pair [:right-image :poi-type])}}}
   :correct-answer (:type pair)})

(defn save-curated-pairs
  "Save curated pairs to EDN for later use"
  [session-data filepath]
  (spit filepath (pr-str session-data))
  (println (format "💾 Saved %d curated pairs to %s"
                   (count (:pairs session-data)) filepath)))

;; Complete workflow

(defn curate-border-game-session
  "Complete workflow: fetch images and curate game pairs"
  [city-a-name city-b-name]
  (println (format "\n🎯 Curating game session for %s - %s border"
                   city-a-name city-b-name))

  ;; Check for cached images first
  (let [cache-file (format "resources/public/images/border-cache/%s-%s/pairs.edn"
                           city-a-name city-b-name)]
    (if (.exists (clojure.java.io/file cache-file))
      (do
        (println "  Using cached pairs from previous session")
        (read-string (slurp cache-file)))

      ;; Fetch fresh images if no cache
      (let [fetched-data (fetcher/collect-border-images city-a-name city-b-name)]
        (if fetched-data
          (let [;; Combine all difficulty levels
                all-images (concat (:easy fetched-data)
                                   (:medium fetched-data)
                                   (:hard fetched-data))

                ;; Prepare game session
                session (prepare-game-session all-images)

                ;; Format for game
                formatted-pairs (map format-for-game (:pairs session))]

            ;; Save for future use
            (save-curated-pairs
             (assoc session :formatted-pairs formatted-pairs)
             cache-file)

            session)
          (println "❌ Failed to fetch images. Check Mapillary token."))))))

;; Testing functions

(defn test-pair-generation
  "Test pair generation with mock data"
  []
  (let [mock-images [{:lat 32.068 :lng 34.824 :difficulty :easy
                      :url "http://example.com/1.jpg"
                      :border-point {:city-a "Tel Aviv"}}
                     {:lat 32.069 :lng 34.825 :difficulty :easy
                      :url "http://example.com/2.jpg"
                      :border-point {:city-a "Tel Aviv"}}
                     {:lat 32.067 :lng 34.826 :difficulty :medium
                      :url "http://example.com/3.jpg"
                      :border-point {:city-a "Ramat Gan"}}
                     {:lat 32.070 :lng 34.823 :difficulty :hard
                      :url "http://example.com/4.jpg"
                      :border-point {:city-a "Ramat Gan"}}]

        easy-pair (generate-game-pairs mock-images 3 20)
        medium-pair (generate-game-pairs mock-images 10 20)
        hard-pair (generate-game-pairs mock-images 18 20)]

    (println "\n🧪 Test pair generation:")
    (println "  Easy pair:" easy-pair)
    (println "  Medium pair:" medium-pair)
    (println "  Hard pair:" hard-pair)))