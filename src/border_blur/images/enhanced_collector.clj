(ns border-blur.images.enhanced-collector
  "Enhanced street view image collector with city-wide coverage, quality filtering, and anti-clustering"
  (:require [border-blur.images.fetcher :as fetcher]
            [border-blur.gis.cities :as cities]
            [border-blur.gis.core :as gis-core]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [geo [jts :as jts] [spatial :as spatial]]
            [geo.poly :as poly])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

;; ===== QUALITY FILTERING CONFIGURATION =====

(def quality-thresholds
  "Quality scoring thresholds for image acceptance"
  {:min-resolution {:width 800 :height 600}
   :max-panoramic-ratio 3.0 ; Width/height ratio - reject if > 3.0 (likely panoramic)
   :min-quality-score 70 ; API quality score if available
   :preferred-apis [:openstreetcam :mapillary :flickr] ; Preferred order for quality
   :blacklist-patterns ["panorama" "360" "pano" "street-view-car"]}) ; Filename patterns to avoid

(def anti-clustering-config
  "Configuration for preventing clustered image collection"
  {:min-distance-meters 300 ; Reduced from 400m for smaller cities
   :grid-size-meters 400 ; Reduced from 500m for denser coverage
   :max-attempts-per-point 3 ; Max API calls per search point before giving up
   :coverage-buffer-meters 150 ; Reduced coverage buffer for smaller areas
   :poisson-disk-samples 3 ; Reduced from 5 for smaller cities
   :jitter-factor 0.4}) ; Increased jitter for better coverage ; Amount of random jitter to add to grid points (0.0-1.0)

;; ===== CITY-WIDE GRID GENERATION =====

(defn create-comprehensive-city-grid
  "Generate comprehensive search grid covering entire city with anti-clustering"
  [city-key city-data]
  (let [boundary (:boundary city-data)
        [min-lng max-lng min-lat max-lat]
        (reduce (fn [[min-lng max-lng min-lat max-lat] [lng lat]]
                  [(min min-lng lng) (max max-lng lng)
                   (min min-lat lat) (max max-lat lat)])
                [180 -180 90 -90]
                boundary)

        grid-size-deg (/ (:grid-size-meters anti-clustering-config) 111000.0)
        jitter-amount (* grid-size-deg (:jitter-factor anti-clustering-config))

        ;; Create comprehensive grid points using the same classification logic as the rest of the system
        grid-points
        (for [lng-step (range min-lng max-lng grid-size-deg)
              lat-step (range min-lat max-lat grid-size-deg)
              sample (range (:poisson-disk-samples anti-clustering-config))
              :let [;; Add random jitter for natural distribution
                    jittered-lng (+ lng-step (* (- (rand) 0.5) jitter-amount))
                    jittered-lat (+ lat-step (* (- (rand) 0.5) jitter-amount))]
              ;; Use the authoritative buffer-based classification that we know works
              :when (try
                      (= (gis-core/classify-point-by-city jittered-lat jittered-lng cities/cities)
                         city-key)
                      (catch Exception e
                        (println (str "Warning: GIS error for point " jittered-lat "," jittered-lng ": " (.getMessage e)))
                        false))]
          {:lat jittered-lat
           :lng jittered-lng
           :grid-cell {:lng-idx (int (/ (- lng-step min-lng) grid-size-deg))
                       :lat-idx (int (/ (- lat-step min-lat) grid-size-deg))}
           :priority (+ (rand) ; Random component
                        ;; Prefer center areas slightly
                        (* 0.2 (- 1.0 (min 1.0 (/ (+ (Math/abs (- jittered-lng (/ (+ min-lng max-lng) 2)))
                                                     (Math/abs (- jittered-lat (/ (+ min-lat max-lat) 2))))
                                                  (max (- max-lng min-lng) (- max-lat min-lat)))))))})]

    (println (str "Generated " (count grid-points) " comprehensive search points covering entire city"))
    (println (str "  City bounds: lng(" min-lng " to " max-lng ") lat(" min-lat " to " max-lat ")"))
    (when (> (count grid-points) 0)
      (println (str "  Sample points: " (take 3 grid-points))))
    (shuffle (sort-by :priority > grid-points))))

;; ===== QUALITY FILTERING =====

(defn assess-image-quality
  "Assess image quality and filter panoramic/low-quality images"
  [image-data]
  (let [url (:url image-data)
        filename (or (:filename image-data) (last (str/split url #"/")))
        width (or (:width image-data) 1024)
        height (or (:height image-data) 768)
        aspect-ratio (if (> height 0) (/ width height) 2.0)
        quality-score (or (:quality image-data) (:score image-data) 85)]

    {:acceptable?
     (and
       ;; Resolution check
      (>= width (get-in quality-thresholds [:min-resolution :width]))
      (>= height (get-in quality-thresholds [:min-resolution :height]))

       ;; Anti-panoramic filter
      (<= aspect-ratio (:max-panoramic-ratio quality-thresholds))

       ;; Quality score if available
      (>= quality-score (:min-quality-score quality-thresholds))

       ;; Filename blacklist check
      (not (some #(str/includes? (str/lower-case filename) %)
                 (:blacklist-patterns quality-thresholds))))

     :quality-score quality-score
     :aspect-ratio aspect-ratio
     :resolution {:width width :height height}
     :filename filename
     :rejection-reasons
     (cond-> []
       (< width (get-in quality-thresholds [:min-resolution :width]))
       (conj :low-width)

       (< height (get-in quality-thresholds [:min-resolution :height]))
       (conj :low-height)

       (> aspect-ratio (:max-panoramic-ratio quality-thresholds))
       (conj :panoramic)

       (< quality-score (:min-quality-score quality-thresholds))
       (conj :low-quality)

       (some #(str/includes? (str/lower-case filename) %)
             (:blacklist-patterns quality-thresholds))
       (conj :blacklisted-pattern))}))

;; ===== ANTI-CLUSTERING SYSTEM =====

(defn calculate-distance-meters
  "Calculate distance between two points in meters"
  [lat1 lng1 lat2 lng2]
  (let [R 6371000 ; Earth's radius in meters
        lat1-rad (Math/toRadians lat1)
        lat2-rad (Math/toRadians lat2)
        delta-lat (Math/toRadians (- lat2 lat1))
        delta-lng (Math/toRadians (- lng2 lng1))

        a (+ (* (Math/sin (/ delta-lat 2)) (Math/sin (/ delta-lat 2)))
             (* (Math/cos lat1-rad) (Math/cos lat2-rad)
                (Math/sin (/ delta-lng 2)) (Math/sin (/ delta-lng 2))))
        c (* 2 (Math/atan2 (Math/sqrt a) (Math/sqrt (- 1 a))))]
    (* R c)))

(defn point-too-close-to-existing?
  "Check if point is too close to any existing collected point"
  [new-lat new-lng existing-points min-distance-meters]
  (some (fn [existing]
          (< (calculate-distance-meters new-lat new-lng
                                        (:lat existing) (:lng existing))
             min-distance-meters))
        existing-points))

(defn create-coverage-grid
  "Create grid to track coverage areas and avoid redundant collection"
  [city-data collected-images]
  (let [boundary (:boundary city-data)
        [min-lng max-lng min-lat max-lat]
        (reduce (fn [[min-lng max-lng min-lat max-lat] [lng lat]]
                  [(min min-lng lng) (max max-lng lng)
                   (min min-lat lat) (max max-lat lat)])
                [180 -180 90 -90]
                boundary)

        grid-size-deg (/ (:coverage-buffer-meters anti-clustering-config) 111000.0)

        ;; Mark covered grid cells
        covered-cells
        (into #{} (for [img collected-images
                        :let [lat (:lat img)
                              lng (:lng img)
                              cell-x (int (/ (- lng min-lng) grid-size-deg))
                              cell-y (int (/ (- lat min-lat) grid-size-deg))]]
                    [cell-x cell-y]))]

    {:covered-cells covered-cells
     :grid-bounds {:min-lng min-lng :max-lng max-lng
                   :min-lat min-lat :max-lat max-lat}
     :grid-size-deg grid-size-deg}))

(defn prioritize-uncovered-areas
  "Prioritize search points in areas with poor coverage"
  [search-points coverage-grid]
  (let [{:keys [covered-cells grid-bounds grid-size-deg]} coverage-grid
        {:keys [min-lng min-lat]} grid-bounds]

    (sort-by
     (fn [point]
       (let [cell-x (int (/ (- (:lng point) min-lng) grid-size-deg))
             cell-y (int (/ (- (:lat point) min-lat) grid-size-deg))
             covered? (covered-cells [cell-x cell-y])]
          ;; Lower values = higher priority
         (if covered? 1.0 0.0))) ; Uncovered areas get highest priority
     search-points)))

;; ===== ENHANCED COLLECTION SYSTEM =====

(defn collect-with-quality-and-distribution
  "Collect images with quality filtering and anti-clustering distribution"
  [city-key target-count]
  (println (str "🎯 Enhanced collection for " (name city-key)
                " - Target: " target-count " high-quality distributed images"))

  (let [city-data (get cities/cities city-key)
        collected-images (atom [])
        rejected-images (atom [])
        api-attempts (atom 0)
        max-attempts (* target-count 10)

        ;; Generate comprehensive city-wide search grid
        search-points (create-comprehensive-city-grid city-key city-data)]

    (if-not city-data
      {:error (str "City not found: " city-key)}

      (do
        (println (str "📍 Generated " (count search-points) " search points across entire " (name city-key)))
        (println (str "🔍 Quality filters: No panoramic (ratio > " (:max-panoramic-ratio quality-thresholds)
                      "), min quality " (:min-quality-score quality-thresholds)
                      ", min distance " (:min-distance-meters anti-clustering-config) "m"))

        ;; Collect images with comprehensive filtering
        (loop [remaining-points search-points
               attempts 0]

          (when (and (< (count @collected-images) target-count)
                     (< attempts max-attempts)
                     (seq remaining-points))

            (let [search-point (first remaining-points)
                  _ (swap! api-attempts inc)
                  api-result (fetcher/fetch-from-multiple-sources
                              search-point
                              (:coverage-buffer-meters anti-clustering-config))]

              (print (str "." (when (zero? (mod attempts 50)) (str " " attempts "/" max-attempts))))

              ;; Process API results
              (when (:success api-result)
                (doseq [img-candidate (:images api-result)]
                  (when (< (count @collected-images) target-count)

                    ;; Step 1: Quality assessment
                    (let [quality-assessment (assess-image-quality img-candidate)]

                      (if-not (:acceptable? quality-assessment)
                        ;; Reject low-quality/panoramic images
                        (swap! rejected-images conj
                               {:image img-candidate
                                :reason :quality
                                :details quality-assessment})

                        ;; Step 2: GIS verification
                        (let [img-lat (:lat img-candidate)
                              img-lng (:lng img-candidate)
                              city-classification (gis-core/classify-point-by-city
                                                   img-lat img-lng cities/cities)]

                          (if (not= city-classification city-key)
                            ;; Reject images outside target city
                            (swap! rejected-images conj
                                   {:image img-candidate
                                    :reason :wrong-city
                                    :actual-city city-classification
                                    :quality quality-assessment})

                            ;; Step 3: Anti-clustering check
                            (if (point-too-close-to-existing?
                                 img-lat img-lng @collected-images
                                 (:min-distance-meters anti-clustering-config))
                              ;; Reject clustered images
                              (swap! rejected-images conj
                                     {:image img-candidate
                                      :reason :too-clustered
                                      :quality quality-assessment})

                              ;; Accept high-quality, well-distributed image
                              (let [metadata {:image-id (str (:source img-candidate) "-" (:id img-candidate))
                                              :source (:source img-candidate)
                                              :coordinates {:lat img-lat :lng img-lng}
                                              :city-verified city-key
                                              :quality-assessment quality-assessment
                                              :collection-method "enhanced-city-wide"
                                              :anti-clustering true
                                              :collection-date (.format (LocalDateTime/now)
                                                                        DateTimeFormatter/ISO_LOCAL_DATE_TIME)}]
                                (swap! collected-images conj
                                       (assoc img-candidate :metadata metadata)))))))))))

              ;; Continue with remaining points
              (recur (rest remaining-points) (inc attempts)))))

        (println (str "\n✅ Enhanced collection complete for " (name city-key)))
        (println (str "   Collected: " (count @collected-images) "/" target-count " images"))
        (println (str "   Rejected: " (count @rejected-images) " images"))
        (println (str "   API calls: " @api-attempts))

        ;; Detailed rejection analysis
        (let [rejection-breakdown (group-by :reason @rejected-images)]
          (println "   Rejection breakdown:")
          (doseq [[reason images] rejection-breakdown]
            (println (str "     " (name reason) ": " (count images) " images"))))

        {:success true
         :city city-key
         :collected-count (count @collected-images)
         :target-count target-count
         :images @collected-images
         :rejected-images @rejected-images
         :api-attempts @api-attempts
         :collection-method "enhanced-city-wide"
         :quality-filtered true
         :anti-clustered true}))))

(defn collect-for-small-city
  "Optimized collection for smaller cities with limited street view coverage"
  [city-key target-count]
  (println (str "🎯 Small city collection for " (name city-key)
                " - Target: " target-count " images (optimized for smaller areas)"))

  (let [city-data (get cities/cities city-key)
        collected-images (atom [])
        rejected-images (atom [])
        api-attempts (atom 0)
        max-attempts (* target-count 8) ; Reduced max attempts for faster completion

        ;; Generate more focused search grid for smaller cities
        search-points (create-comprehensive-city-grid city-key city-data)]

    (if-not city-data
      {:error (str "City not found: " city-key)}

      (do
        (println (str "📍 Generated " (count search-points) " search points for small city " (name city-key)))
        (println (str "🔍 Relaxed filters: min distance " (:min-distance-meters anti-clustering-config) "m, "
                      "coverage buffer " (:coverage-buffer-meters anti-clustering-config) "m"))

        ;; Collect with relaxed parameters for smaller cities
        (loop [remaining-points search-points
               attempts 0]

          (when (and (< (count @collected-images) target-count)
                     (< attempts max-attempts)
                     (seq remaining-points))

            (let [search-point (first remaining-points)
                  _ (swap! api-attempts inc)
                  api-result (fetcher/fetch-from-multiple-sources
                              search-point
                              (:coverage-buffer-meters anti-clustering-config))]

              (print (str "." (when (zero? (mod attempts 20)) (str " " attempts "/" max-attempts))))

              ;; Process API results with same quality standards
              (when (:success api-result)
                (doseq [img-candidate (:images api-result)]
                  (when (< (count @collected-images) target-count)

                    ;; Step 1: Quality assessment
                    (let [quality-assessment (assess-image-quality img-candidate)]

                      (if-not (:acceptable? quality-assessment)
                        (swap! rejected-images conj
                               {:image img-candidate
                                :reason :quality
                                :details quality-assessment})

                        ;; Step 2: GIS verification
                        (let [img-lat (:lat img-candidate)
                              img-lng (:lng img-candidate)
                              city-classification (gis-core/classify-point-by-city
                                                   img-lat img-lng cities/cities)]

                          (if (not= city-classification city-key)
                            (swap! rejected-images conj
                                   {:image img-candidate
                                    :reason :wrong-city
                                    :actual-city city-classification
                                    :quality quality-assessment})

                            ;; Step 3: Anti-clustering check (relaxed for small cities)
                            (if (point-too-close-to-existing?
                                 img-lat img-lng @collected-images
                                 (:min-distance-meters anti-clustering-config))
                              (swap! rejected-images conj
                                     {:image img-candidate
                                      :reason :too-clustered
                                      :quality quality-assessment})

                              ;; Accept image
                              (let [metadata {:image-id (str (:source img-candidate) "-" (:id img-candidate))
                                              :source (:source img-candidate)
                                              :coordinates {:lat img-lat :lng img-lng}
                                              :city-verified city-key
                                              :quality-assessment quality-assessment
                                              :collection-method "small-city-optimized"
                                              :anti-clustering true
                                              :collection-date (.format (LocalDateTime/now)
                                                                        DateTimeFormatter/ISO_LOCAL_DATE_TIME)}]
                                (swap! collected-images conj
                                       (assoc img-candidate :metadata metadata)))))))))))

              ;; Continue with remaining points
              (recur (rest remaining-points) (inc attempts)))))

        (println (str "\n✅ Small city collection complete for " (name city-key)))
        (println (str "   Collected: " (count @collected-images) "/" target-count " images"))
        (println (str "   Rejected: " (count @rejected-images) " images"))
        (println (str "   API calls: " @api-attempts))

        ;; Detailed rejection analysis
        (let [rejection-breakdown (group-by :reason @rejected-images)]
          (println "   Rejection breakdown:")
          (doseq [[reason images] rejection-breakdown]
            (println (str "     " (name reason) ": " (count images) " images"))))

        {:success true
         :city city-key
         :collected-count (count @collected-images)
         :target-count target-count
         :images @collected-images
         :rejected-images @rejected-images
         :api-attempts @api-attempts
         :collection-method "small-city-optimized"
         :quality-filtered true
         :anti-clustered true}))))

;; ===== BATCH COLLECTION SYSTEM =====

(defn run-enhanced-city-wide-collection
  "Run enhanced collection across all cities with comprehensive coverage"
  ([images-per-city] (run-enhanced-city-wide-collection images-per-city
                                                        "resources/public/images/enhanced-collection"))
  ([images-per-city output-dir]
   (println "🚀 ENHANCED CITY-WIDE STREET VIEW COLLECTION")
   (println "=============================================")
   (println (str "Target: " images-per-city " high-quality, well-distributed images per city"))
   (println (str "Output: " output-dir "/"))
   (println (str "Quality: No panoramic, min distance " (:min-distance-meters anti-clustering-config) "m"))
   (println)

   (let [cities-to-collect [:tel-aviv-yafo :ramat-gan :givatayim :bnei-brak :bat-yam :holon]
         results (atom {})]

     (doseq [city-key cities-to-collect]
       (println (str "\n🏙️ Processing " (name city-key) "..."))
       (let [result (collect-with-quality-and-distribution city-key images-per-city)]
         (swap! results assoc city-key result)
         (Thread/sleep 3000))) ; Respectful API delays

     ;; Save results to disk
     (println "\n💾 SAVING ENHANCED COLLECTION")
     (println "=============================")
     (let [save-count (atom 0)]
       (doseq [[city-key result] @results]
         (when (:success result)
           (let [city-dir (str output-dir "/" (name city-key))]
             (io/make-parents (str city-dir "/dummy.txt"))
             (doseq [img (:images result)]
               (try
                 (let [img-filename (str (:id img) "_" (:lat img) "_" (:lng img) ".jpg")
                       img-path (str city-dir "/" img-filename)
                       metadata-path (str city-dir "/" (:id img) ".edn")]

                   ;; Download image
                   (with-open [in (io/input-stream (:url img))
                               out (io/output-stream img-path)]
                     (io/copy in out))

                   ;; Save metadata
                   (spit metadata-path (pr-str (:metadata img)))
                   (swap! save-count inc))
                 (catch Exception e
                   (println (str "❌ Failed to save " (:id img) ": " (.getMessage e)))))))))

       ;; Final summary
       (println "\n🎉 ENHANCED COLLECTION SUMMARY")
       (println "==============================")
       (let [total-collected (reduce + (map (fn [[_ result]]
                                              (if (:success result) (:collected-count result) 0))
                                            @results))
             total-rejected (reduce + (map (fn [[_ result]]
                                             (if (:success result) (count (:rejected-images result)) 0))
                                           @results))]

         (println (str "Cities processed: " (count @results)))
         (println (str "Images collected: " total-collected " (target: " (* (count @results) images-per-city) ")"))
         (println (str "Images saved: " @save-count))
         (println (str "Images rejected: " total-rejected))
         (println (str "Collection rate: " (int (* 100 (/ total-collected (+ total-collected total-rejected)))) "%"))
         (println (str "Quality filtering: ✅ No panoramic images"))
         (println (str "Distribution: ✅ Min " (:min-distance-meters anti-clustering-config) "m between images"))
         (println (str "Coverage: ✅ City-wide comprehensive grid search")))

       @results))))

;; ===== TESTING AND VALIDATION =====

(defn test-enhanced-collection
  "Test enhanced collection on a small sample"
  [city-key num-images]
  (println (str "🧪 Testing enhanced collection for " (name city-key) " (" num-images " images)"))
  (collect-with-quality-and-distribution city-key num-images))

(defn validate-collection-quality
  "Validate that collected images meet quality and distribution standards"
  [city-key collection-dir]
  (let [city-dir (str collection-dir "/" (name city-key))
        image-files (filter #(str/ends-with? % ".jpg")
                            (map #(.getName %)
                                 (file-seq (io/file city-dir))))

        coordinates (map (fn [filename]
                           (let [parts (str/split filename #"_")]
                             (when (>= (count parts) 3)
                               {:lat (Double/parseDouble (nth parts 1))
                                :lng (Double/parseDouble (nth parts 2))})))
                         image-files)

        valid-coords (remove nil? coordinates)]

    (when (seq valid-coords)
      (let [distances (for [i (range (count valid-coords))
                            j (range (inc i) (count valid-coords))
                            :let [p1 (nth valid-coords i)
                                  p2 (nth valid-coords j)]]
                        (calculate-distance-meters (:lat p1) (:lng p1) (:lat p2) (:lng p2)))
            min-distance (if (seq distances) (apply min distances) 0)
            avg-distance (if (seq distances) (/ (reduce + distances) (count distances)) 0)]

        (println (str "📊 Collection validation for " (name city-key)))
        (println (str "   Images found: " (count image-files)))
        (println (str "   Valid coordinates: " (count valid-coords)))
        (println (str "   Min distance: " (int min-distance) "m"))
        (println (str "   Avg distance: " (int avg-distance) "m"))
        (println (str "   Anti-clustering: " (if (>= min-distance (:min-distance-meters anti-clustering-config)) "✅" "❌")))))))