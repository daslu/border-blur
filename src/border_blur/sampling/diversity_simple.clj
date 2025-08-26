(ns border-blur.sampling.diversity-simple
  "Simplified spatial sampling algorithms using built-in Clojure random generation"
  (:require [border-blur.gis.cities :as cities]
            [border-blur.gis.core :as gis]))

;; Haversine distance calculation
(defn haversine-distance [p1 p2]
  "Calculate distance between two points in meters using Haversine formula"
  (let [lat1 (Math/toRadians (:lat p1))
        lon1 (Math/toRadians (:lng p1))
        lat2 (Math/toRadians (:lat p2))
        lon2 (Math/toRadians (:lng p2))
        dlat (- lat2 lat1)
        dlon (- lon2 lon1)
        a (+ (* (Math/sin (/ dlat 2)) (Math/sin (/ dlat 2)))
             (* (Math/cos lat1) (Math/cos lat2)
                (Math/sin (/ dlon 2)) (Math/sin (/ dlon 2))))
        c (* 2 (Math/atan2 (Math/sqrt a) (Math/sqrt (- 1 a))))]
    (* 6371000 c))) ; Earth radius in meters

;; Poisson disk sampling (simplified)
(defn poisson-disk-sampling [bounds min-distance-m max-points]
  "Generate points with minimum distance separation using simplified Poisson disk sampling"
  (let [result-points (atom [])
        max-attempts 10000]
    (loop [attempts 0 successful 0]
      (when (and (< attempts max-attempts) (< successful max-points))
        (let [candidate {:lat (+ (:min-lat bounds)
                                 (* (rand) (- (:max-lat bounds) (:min-lat bounds))))
                         :lng (+ (:min-lng bounds)
                                 (* (rand) (- (:max-lng bounds) (:min-lng bounds))))
                         :method :poisson-disk}
              valid? (every? #(>= (haversine-distance candidate %) min-distance-m)
                             @result-points)]
          (if valid?
            (do (swap! result-points conj candidate)
                (recur (inc attempts) (inc successful)))
            (recur (inc attempts) successful)))))
    @result-points))

;; Stratified grid sampling
(defn stratified-grid-sampling [bounds grid-size max-points]
  "Generate points using stratified grid with random jitter"
  (let [lat-range (- (:max-lat bounds) (:min-lat bounds))
        lng-range (- (:max-lng bounds) (:min-lng bounds))
        cell-lat (/ lat-range grid-size)
        cell-lng (/ lng-range grid-size)
        points-per-cell (Math/ceil (/ max-points (* grid-size grid-size)))]

    (for [i (range grid-size)
          j (range grid-size)
          _ (range (min points-per-cell
                        (- max-points (* (+ (* i grid-size) j) points-per-cell))))]
      (let [base-lat (+ (:min-lat bounds) (* i cell-lat))
            base-lng (+ (:min-lng bounds) (* j cell-lng))
            jitter-lat (* (rand) cell-lat)
            jitter-lng (* (rand) cell-lng)]
        {:lat (+ base-lat jitter-lat)
         :lng (+ base-lng jitter-lng)
         :method :stratified-grid}))))

;; Calculate diversity metrics
(defn calculate-diversity-metrics [points]
  "Calculate spatial diversity metrics for point distribution"
  (when (seq points)
    (let [distances (for [i (range (count points))
                          j (range (inc i) (count points))]
                      (haversine-distance (nth points i) (nth points j)))
          min-distance (if (seq distances) (apply min distances) 0)
          avg-distance (if (seq distances) (/ (reduce + distances) (count distances)) 0)

          ;; Calculate coverage area (convex hull approximation)
          lats (map :lat points)
          lngs (map :lng points)
          lat-span (if (> (count lats) 1) (- (apply max lats) (apply min lats)) 0)
          lng-span (if (> (count lngs) 1) (- (apply max lngs) (apply min lngs)) 0)
          coverage-area (* lat-span lng-span 111000 111000) ; Rough m² conversion

          ;; Uniformity score (inverse coefficient of variation of distances)
          distance-std (if (> (count distances) 1)
                         (Math/sqrt (/ (reduce + (map #(* (- % avg-distance)
                                                          (- % avg-distance)) distances))
                                       (count distances)))
                         0)
          uniformity (if (> avg-distance 0) (- 1 (/ distance-std avg-distance)) 0)]

      {:point-count (count points)
       :min-distance-m min-distance
       :avg-distance-m avg-distance
       :coverage-area-m2 coverage-area
       :uniformity-score uniformity})))

;; Helper function for city bounds
(defn city->bounds [city]
  "Convert city boundary to coordinate bounds"
  (let [boundary (:boundary city)
        lngs (map first boundary)
        lats (map second boundary)]
    {:min-lat (apply min lats)
     :max-lat (apply max lats)
     :min-lng (apply min lngs)
     :max-lng (apply max lngs)}))

;; Test function
(defn test-diversity-comparison []
  "Test different sampling methods and compare diversity metrics"
  (let [test-bounds {:min-lat 32.050 :max-lat 32.070
                     :min-lng 34.780 :max-lng 34.800}

        ;; Random sampling (baseline)
        random-points (repeatedly 20 #(hash-map :lat (+ (:min-lat test-bounds)
                                                        (* (rand) (- (:max-lat test-bounds)
                                                                     (:min-lat test-bounds))))
                                                :lng (+ (:min-lng test-bounds)
                                                        (* (rand) (- (:max-lng test-bounds)
                                                                     (:min-lng test-bounds))))
                                                :method :random))

        ;; Poisson disk sampling
        poisson-points (poisson-disk-sampling test-bounds 200 20)

        ;; Stratified grid sampling
        grid-points (take 20 (stratified-grid-sampling test-bounds 5 20))]

    (println "🔬 Diversity Sampling Comparison")
    (println "================================")

    (doseq [[name points] [["Random Sampling" random-points]
                           ["Poisson Disk" poisson-points]
                           ["Stratified Grid" grid-points]]]
      (let [metrics (calculate-diversity-metrics points)]
        (println (format "\n%s:" name))
        (println (format "  Points: %d" (:point-count metrics)))
        (println (format "  Min Distance: %.1f m" (:min-distance-m metrics)))
        (println (format "  Avg Distance: %.1f m" (:avg-distance-m metrics)))
        (println (format "  Coverage: %.0f m²" (:coverage-area-m2 metrics)))
        (println (format "  Uniformity: %.3f" (:uniformity-score metrics)))))

    {:random random-points
     :poisson poisson-points
     :grid grid-points}))

;; Integration with existing image collection system
(defn generate-diverse-border-points
  "Generate diverse points along city borders for image collection"
  ([city-a city-b] (generate-diverse-border-points city-a city-b {}))
  ([city-a city-b {:keys [algorithm point-count min-distance-m]
                   :or {algorithm :poisson-disk
                        point-count 15
                        min-distance-m 300}}]
   (let [;; Calculate combined bounds from both cities
         bounds-a (city->bounds city-a)
         bounds-b (city->bounds city-b)
         combined-bounds {:min-lat (min (:min-lat bounds-a) (:min-lat bounds-b))
                          :max-lat (max (:max-lat bounds-a) (:max-lat bounds-b))
                          :min-lng (min (:min-lng bounds-a) (:min-lng bounds-b))
                          :max-lng (max (:max-lng bounds-a) (:max-lng bounds-b))}

         ;; Generate points using selected algorithm
         points (case algorithm
                  :poisson-disk (poisson-disk-sampling combined-bounds min-distance-m point-count)
                  :stratified-grid (take point-count
                                         (stratified-grid-sampling combined-bounds 4 point-count))
                  :random (repeatedly point-count
                                      #(hash-map :lat (+ (:min-lat combined-bounds)
                                                         (* (rand) (- (:max-lat combined-bounds)
                                                                      (:min-lat combined-bounds))))
                                                 :lng (+ (:min-lng combined-bounds)
                                                         (* (rand) (- (:max-lng combined-bounds)
                                                                      (:min-lng combined-bounds))))
                                                 :method :random)))

         ;; Add metadata for image collection
         enhanced-points (map-indexed
                          (fn [i point]
                            (assoc point
                                   :id i
                                   :name (format "border-point-%d" i)
                                   :type "border-sample"
                                   :cities [(:name city-a) (:name city-b)]
                                   :radius 100)) ; Search radius for images
                          points)]

     {:points enhanced-points
      :algorithm algorithm
      :cities [(:name city-a) (:name city-b)]
      :metrics (calculate-diversity-metrics points)
      :bounds combined-bounds})))

;; Test function for Tel Aviv-Ramat Gan border
(defn test-tel-aviv-ramat-gan-diversity []
  "Test diverse border point generation between Tel Aviv and Ramat Gan"
  (let [tel-aviv {:name "Tel Aviv" :boundary [[34.75 32.05] [34.82 32.05] [34.82 32.12] [34.75 32.12] [34.75 32.05]]}
        ramat-gan {:name "Ramat Gan" :boundary [[34.82 32.05] [34.87 32.05] [34.87 32.12] [34.82 32.12] [34.82 32.05]]}

        results (generate-diverse-border-points tel-aviv ramat-gan
                                                {:algorithm :poisson-disk
                                                 :point-count 12
                                                 :min-distance-m 400})]

    (println "🎯 Tel Aviv ⟷ Ramat Gan Border Points")
    (println "=====================================")
    (println (format "Algorithm: %s" (:algorithm results)))
    (println (format "Cities: %s" (clojure.string/join " ⟷ " (:cities results))))
    (println (format "Generated %d diverse border points" (count (:points results))))

    (let [metrics (:metrics results)]
      (println (format "\nDiversity Metrics:"))
      (println (format "  Min Distance: %.1f m" (:min-distance-m metrics)))
      (println (format "  Avg Distance: %.1f m" (:avg-distance-m metrics)))
      (println (format "  Coverage: %.0f m²" (:coverage-area-m2 metrics)))
      (println (format "  Uniformity: %.3f" (:uniformity-score metrics))))

    (println (format "\nSample Points:"))
    (doseq [point (take 5 (:points results))]
      (println (format "  • %s: (%.4f, %.4f) radius=%dm"
                       (:name point) (:lat point) (:lng point) (:radius point))))

    results))