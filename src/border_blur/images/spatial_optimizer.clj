(ns border-blur.images.spatial-optimizer
  "Optimize street view image collection using GIS-based spatial analysis.
   Ensures diverse coverage across entire cities with proper classification."
  (:require [border-blur.gis.cities :as cities]
            [border-blur.gis.core :as gis-core]
            [geo.jts :as jts]
            [geo.spatial :as spatial]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ========================================
;; Image Validation & Reclassification
;; ========================================

(defn parse-coordinates-from-filename
  "Extract lat/lng from filename pattern: lat_lng_*.jpg"
  [filename]
  (when-let [match (re-find #"(\d+\.\d+)_(\d+\.\d+)_" filename)]
    {:lat (Double/parseDouble (nth match 1))
     :lng (Double/parseDouble (nth match 2))}))

(defn classify-image-by-gis
  "Determine actual city using point-in-polygon testing - FIXED VERSION"
  [lat lng city-polygons]
  ;; Simple ray casting algorithm for point-in-polygon test with divide-by-zero protection
  (letfn [(point-in-polygon? [px py polygon-coords]
            (let [n (count polygon-coords)]
              (loop [i 0 j (dec n) inside false]
                (if (>= i n)
                  inside
                  (let [[xi yi] (nth polygon-coords i)
                        [xj yj] (nth polygon-coords j)]
                    (if (and (or (< yi py) (>= yj py))
                             (or (< yj py) (>= yi py))
                             ;; Protect against divide by zero
                             (not= yi yj)
                             (< (+ xi (* (/ (- py yi) (- yj yi)) (- xj xi))) px))
                      (recur (inc i) i (not inside))
                      (recur (inc i) i inside)))))))]
    (first
     (keep (fn [[city-key city-data]]
             (let [boundary (:boundary city-data)]
               (try
                 ;; Use working point-in-polygon algorithm with safety check
                 (when (and boundary (seq boundary) (point-in-polygon? lng lat boundary))
                   city-key)
                 (catch Exception e
                   (println (format "GIS error for city %s: %s" city-key (.getMessage e)))
                   nil))))
           city-polygons))))

(defn validate-single-image
  "Validate and classify a single image using GIS"
  [file-path city-polygons]
  (let [filename (.getName (io/file file-path))
        coords (parse-coordinates-from-filename filename)]
    (when coords
      (let [verified-city (classify-image-by-gis (:lat coords) (:lng coords) city-polygons)
            folder-city (cond
                          (str/includes? file-path "/tel-aviv/") :tel-aviv-yafo
                          (str/includes? file-path "/ramat-gan/") :ramat-gan
                          (str/includes? file-path "/givatayim/") :givatayim
                          (str/includes? file-path "/bnei-brak/") :bnei-brak
                          (str/includes? file-path "/bat-yam/") :bat-yam
                          (str/includes? file-path "/holon/") :holon
                          :else :unknown)]
        {:file-path file-path
         :filename filename
         :coordinates coords
         :gis-verified-city verified-city
         :folder-indicated-city folder-city
         :correctly-classified? (= verified-city folder-city)
         :needs-reclassification? (not= verified-city folder-city)}))))

(defn validate-all-images
  "Validate all images in collection and generate reclassification report"
  []
  (println "\n🔍 VALIDATING ALL IMAGES WITH GIS...")
  (let [base-path "resources/public/images"
        image-files (->> (io/file base-path)
                         file-seq
                         (filter #(.isFile %))
                         (filter #(re-matches #".*\.(jpg|jpeg|png)$" (.getName %))))
        city-polygons cities/cities
        validations (keep #(validate-single-image (.getPath %) city-polygons) image-files)

        total (count validations)
        correct (count (filter :correctly-classified? validations))
        incorrect (filter :needs-reclassification? validations)]

    (println (format "\n📊 VALIDATION RESULTS:"))
    (println (format "  Total images with coordinates: %d" total))
    (println (format "  Correctly classified: %d (%.1f%%)" correct (* 100.0 (/ correct total))))
    (println (format "  Need reclassification: %d (%.1f%%)" (count incorrect) (* 100.0 (/ (count incorrect) total))))

    (when (seq incorrect)
      (println "\n⚠️  MISCLASSIFIED IMAGES:")
      (doseq [{:keys [filename folder-indicated-city gis-verified-city]} incorrect]
        (println (format "  %s: folder says %s but GIS shows %s"
                         filename folder-indicated-city gis-verified-city))))

    {:validations validations
     :summary {:total total
               :correct correct
               :incorrect (count incorrect)
               :accuracy (/ correct total)}
     :reclassification-needed incorrect}))

;; ========================================
;; Spatial Distribution Analysis
;; ========================================

(defn calculate-pairwise-distances
  "Calculate distances between all image pairs in meters"
  [images]
  (for [i (range (count images))
        j (range (inc i) (count images))
        :let [img1 (nth images i)
              img2 (nth images j)
              dist (spatial/distance
                    (spatial/point (get-in img1 [:coordinates :lng])
                                   (get-in img1 [:coordinates :lat]))
                    (spatial/point (get-in img2 [:coordinates :lng])
                                   (get-in img2 [:coordinates :lat])))]]
    {:image1 (:filename img1)
     :image2 (:filename img2)
     :distance-meters dist}))

(defn detect-clustering
  "Find clusters of images that are too close together"
  [images min-distance-meters]
  (let [distances (calculate-pairwise-distances images)]
    (filter #(< (:distance-meters %) min-distance-meters) distances)))

(defn analyze-spatial-distribution
  "Comprehensive spatial distribution analysis"
  [images]
  (println "\n📍 SPATIAL DISTRIBUTION ANALYSIS...")
  (let [by-city (group-by :gis-verified-city images)]
    (doseq [[city city-images] by-city]
      (when city
        (println (format "\n%s (%d images):" (name city) (count city-images)))
        (let [clusters-500m (detect-clustering city-images 500)
              clusters-200m (detect-clustering city-images 200)
              distances (calculate-pairwise-distances city-images)
              avg-distance (if (seq distances)
                             (/ (reduce + (map :distance-meters distances)) (count distances))
                             0)]
          (println (format "  Average pairwise distance: %.0f meters" avg-distance))
          (println (format "  Image pairs <500m apart: %d" (count clusters-500m)))
          (println (format "  Image pairs <200m apart: %d" (count clusters-200m)))
          (when (seq clusters-200m)
            (println "  ⚠️ Highly clustered images detected!")))))))

(defn calculate-coverage-grid
  "Calculate grid-based coverage metrics for a city"
  [city-key images grid-size-meters]
  (let [city-data (get cities/cities city-key)
        boundary (:boundary city-data)
        ;; Create polygon using linear-ring approach
        ring (jts/linear-ring boundary)
        city-polygon (jts/polygon ring)
        envelope (.getEnvelopeInternal city-polygon)

        min-x (.getMinX envelope)
        max-x (.getMaxX envelope)
        min-y (.getMinY envelope)
        max-y (.getMaxY envelope)

        ;; Convert degrees to approximate meters (at this latitude)
        deg-to-meters 111000 ; approximately at 32° latitude
        grid-size-deg (/ grid-size-meters deg-to-meters)

        ;; Create grid cells
        grid-cells (for [x (range min-x max-x grid-size-deg)
                         y (range min-y max-y grid-size-deg)]
                     {:x x :y y
                      :center-lng (+ x (/ grid-size-deg 2))
                      :center-lat (+ y (/ grid-size-deg 2))})

        ;; Filter cells that are inside the city polygon
        cells-in-city (filter #(spatial/intersects?
                                (jts/point (:center-lng %) (:center-lat %))
                                city-polygon)
                              grid-cells)

        ;; Check which cells have images nearby
        cells-with-images (filter (fn [cell]
                                    (some (fn [img]
                                            (< (spatial/distance
                                                (spatial/point (:center-lng cell) (:center-lat cell))
                                                (spatial/point (get-in img [:coordinates :lng])
                                                               (get-in img [:coordinates :lat])))
                                               (/ grid-size-meters 2)))
                                          images))
                                  cells-in-city)]

    {:total-cells (count cells-in-city)
     :covered-cells (count cells-with-images)
     :coverage-ratio (if (pos? (count cells-in-city))
                       (/ (count cells-with-images) (count cells-in-city))
                       0)
     :empty-cells (remove (set cells-with-images) cells-in-city)}))

;; ========================================
;; Optimized Collection Point Generation
;; ========================================

(defn generate-poisson-disk-points
  "Generate well-distributed points using Poisson disk sampling"
  [city-polygon num-points min-distance-meters]
  (let [envelope (.getEnvelopeInternal city-polygon)
        min-x (.getMinX envelope)
        max-x (.getMaxX envelope)
        min-y (.getMinY envelope)
        max-y (.getMaxY envelope)

        ;; Convert min distance to degrees (approximate)
        min-distance-deg (/ min-distance-meters 111000.0)]

    (loop [points []
           candidates []
           attempts 0
           max-attempts (* num-points 100)]
      (if (or (>= (count points) num-points)
              (>= attempts max-attempts))
        points
        (let [;; Generate random candidate point
              lng (+ min-x (* (rand) (- max-x min-x)))
              lat (+ min-y (* (rand) (- max-y min-y)))
              candidate-point (jts/point lng lat)

              ;; Check if point is inside city polygon
              inside? (spatial/intersects? candidate-point city-polygon)

              ;; Check minimum distance to existing points
              too-close? (when inside?
                           (some #(< (spatial/distance
                                      (spatial/point lng lat)
                                      (spatial/point (:lng %) (:lat %)))
                                     min-distance-meters)
                                 points))]
          (if (and inside? (not too-close?))
            (recur (conj points {:lat lat :lng lng})
                   candidates
                   0
                   max-attempts)
            (recur points
                   candidates
                   (inc attempts)
                   max-attempts)))))))

(defn generate-grid-based-points
  "Generate points on a regular grid with jitter for natural distribution"
  [city-polygon grid-size-meters jitter-factor]
  (let [envelope (.getEnvelopeInternal city-polygon)
        min-x (.getMinX envelope)
        max-x (.getMaxX envelope)
        min-y (.getMinY envelope)
        max-y (.getMaxY envelope)

        ;; Convert grid size to degrees
        grid-size-deg (/ grid-size-meters 111000.0)
        jitter-amount (* grid-size-deg jitter-factor)]

    (for [x (range min-x max-x grid-size-deg)
          y (range min-y max-y grid-size-deg)
          :let [;; Add random jitter
                jittered-x (+ x (* (- (rand) 0.5) jitter-amount))
                jittered-y (+ y (* (- (rand) 0.5) jitter-amount))
                point (jts/point jittered-x jittered-y)]
          :when (spatial/intersects? point city-polygon)]
      {:lat jittered-y :lng jittered-x})))

(defn prioritize-underserved-areas
  "Identify areas that need more image coverage"
  [city-key existing-images target-coverage-radius-meters]
  (let [city-data (get cities/cities city-key)
        boundary (:boundary city-data)
        ;; Create polygon using linear-ring approach
        ring (jts/linear-ring boundary)
        city-polygon (jts/polygon ring)

        ;; Generate candidate points
        candidates (generate-grid-based-points city-polygon
                                               target-coverage-radius-meters
                                               0.3)

        ;; Filter out points that are already well-covered
        underserved (filter (fn [candidate]
                              (not (some (fn [img]
                                           (< (spatial/distance
                                               (spatial/point (:lng candidate) (:lat candidate))
                                               (spatial/point (get-in img [:coordinates :lng])
                                                              (get-in img [:coordinates :lat])))
                                              target-coverage-radius-meters))
                                         existing-images)))
                            candidates)]

    (println (format "\n🎯 UNDERSERVED AREAS in %s:" (name city-key)))
    (println (format "  Total candidate points: %d" (count candidates)))
    (println (format "  Already covered: %d" (- (count candidates) (count underserved))))
    (println (format "  Underserved locations: %d" (count underserved)))

    underserved))

;; ========================================
;; Optimized Collection Strategy
;; ========================================

(defn create-optimized-collection-plan
  "Generate comprehensive collection plan for diverse coverage"
  [city-key target-images-per-city]
  (println (format "\n🗺️ CREATING OPTIMIZED COLLECTION PLAN for %s..." (name city-key)))

  (let [;; Get existing images for this city
        all-validations (:validations (validate-all-images))
        city-images (filter #(= (:gis-verified-city %) city-key) all-validations)

        ;; Analyze current distribution
        _ (println (format "  Current images: %d" (count city-images)))

        ;; Calculate coverage
        coverage-500m (calculate-coverage-grid city-key city-images 500)
        _ (println (format "  Coverage (500m grid): %.1f%%"
                           (* 100 (:coverage-ratio coverage-500m))))

        ;; Identify underserved areas
        underserved-points (prioritize-underserved-areas city-key city-images 500)

        ;; Generate optimized collection points
        city-data (get cities/cities city-key)
        boundary (:boundary city-data)
        ;; Create polygon using linear-ring approach
        ring (jts/linear-ring boundary)
        city-polygon (jts/polygon ring)
        needed-images (max 0 (- target-images-per-city (count city-images)))

        ;; Use Poisson disk sampling for well-distributed new points
        new-collection-points (if (pos? needed-images)
                                (generate-poisson-disk-points city-polygon
                                                              needed-images
                                                              400) ; 400m minimum distance
                                [])]

    (println (format "  New collection points needed: %d" needed-images))
    (println (format "  Generated collection points: %d" (count new-collection-points)))

    {:city city-key
     :existing-images (count city-images)
     :target-images target-images-per-city
     :current-coverage (:coverage-ratio coverage-500m)
     :underserved-locations (take needed-images underserved-points)
     :new-collection-points new-collection-points
     :clustering-issues (detect-clustering city-images 200)}))

;; ========================================
;; Diversity Metrics & Reporting
;; ========================================

(defn calculate-diversity-score
  "Calculate comprehensive diversity score for image collection"
  [city-key]
  (let [all-validations (:validations (validate-all-images))
        city-images (filter #(= (:gis-verified-city %) city-key) all-validations)
        city-data (get cities/cities city-key)
        boundary (:boundary city-data)
        ;; Create polygon using linear-ring approach
        ring (jts/linear-ring boundary)
        city-polygon (jts/polygon ring)

        ;; Coverage score (0-1)
        coverage (calculate-coverage-grid city-key city-images 500)
        coverage-score (:coverage-ratio coverage)

        ;; Distribution score based on clustering (0-1)
        clusters-200m (detect-clustering city-images 200)
        distribution-score (max 0 (- 1.0 (/ (count clusters-200m)
                                            (max 1 (count city-images)))))

        ;; Border bias score (0-1, higher is better/less biased)
        border-images (filter (fn [img]
                                (let [point (jts/point (get-in img [:coordinates :lng])
                                                       (get-in img [:coordinates :lat]))
                                      boundary (.getBoundary city-polygon)]
                                  (< (spatial/distance point boundary) 500)))
                              city-images)
        border-ratio (/ (count border-images) (max 1 (count city-images)))
        border-bias-score (- 1.0 border-ratio)]

    {:city city-key
     :total-images (count city-images)
     :coverage-score coverage-score
     :distribution-score distribution-score
     :border-bias-score border-bias-score
     :overall-diversity-score (/ (+ coverage-score distribution-score border-bias-score) 3)
     :details {:coverage-percent (* 100 coverage-score)
               :clustered-pairs (count clusters-200m)
               :border-images (count border-images)
               :interior-images (- (count city-images) (count border-images))}}))

(defn generate-optimization-report
  "Generate comprehensive optimization report for all cities"
  []
  (println "\n" (str/join "" (repeat 50 "=")))
  (println "      IMAGE COLLECTION OPTIMIZATION REPORT")
  (println (str/join "" (repeat 50 "=")))

  ;; Validation summary
  (let [validation-results (validate-all-images)]
    (println "\n1️⃣ IMAGE VALIDATION SUMMARY")
    (println (format "   Total images: %d" (get-in validation-results [:summary :total])))
    (println (format "   Correctly classified: %.1f%%" (* 100 (get-in validation-results [:summary :accuracy]))))
    (println (format "   Need reclassification: %d" (get-in validation-results [:summary :incorrect])))

    ;; Spatial distribution analysis
    (println "\n2️⃣ SPATIAL DISTRIBUTION ANALYSIS")
    (analyze-spatial-distribution (:validations validation-results))

    ;; Diversity scores
    (println "\n3️⃣ DIVERSITY SCORES BY CITY")
    (doseq [city-key [:tel-aviv-yafo :ramat-gan :givatayim :bnei-brak :bat-yam :holon]]
      (let [score (calculate-diversity-score city-key)]
        (when (pos? (:total-images score))
          (println (format "\n%s:" (name city-key)))
          (println (format "  Images: %d" (:total-images score)))
          (println (format "  Coverage: %.1f%%" (get-in score [:details :coverage-percent])))
          (println (format "  Distribution score: %.2f" (:distribution-score score)))
          (println (format "  Border bias score: %.2f" (:border-bias-score score)))
          (println (format "  Overall diversity: %.2f ⭐" (:overall-diversity-score score))))))

    ;; Optimization recommendations
    (println "\n4️⃣ OPTIMIZATION RECOMMENDATIONS")
    (doseq [city-key [:tel-aviv-yafo :ramat-gan :givatayim :bnei-brak :bat-yam :holon]]
      (let [plan (create-optimized-collection-plan city-key 50)]
        (when (pos? (:existing-images plan))
          (println (format "\n%s needs %d more images for target of %d"
                           (name city-key)
                           (- (:target-images plan) (:existing-images plan))
                           (:target-images plan))))))

    (println "\n" (str/join "" (repeat 50 "=")))
    (println "    END OF OPTIMIZATION REPORT")
    (println (str/join "" (repeat 50 "=")))))

;; ========================================
;; Main Optimization Pipeline
;; ========================================

(defn run-optimization-pipeline
  "Execute complete optimization pipeline"
  []
  (println "\n🚀 STARTING STREET VIEW IMAGE OPTIMIZATION PIPELINE...")
  (generate-optimization-report)
  (println "\n✅ OPTIMIZATION PIPELINE COMPLETE"))