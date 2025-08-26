(ns border-blur.sampling.spatial-diversity
  "Advanced spatial sampling algorithms for diverse image collection points.
   Uses Fastmath3 for high-quality random generation and mathematical operations."
  (:require [fastmath.core :as fm]
            [fastmath.random :as fr]
            [fastmath.vector :as fv]
            [fastmath.stats :as fs]
            [border-blur.gis.cities :as cities]
            [border-blur.gis.core :as gis]))

;; Configuration constants
(def default-config
  {:min-distance-m 800 ; Minimum 800m between points
   :max-attempts 30 ; Maximum attempts per point generation
   :grid-spacing-m 1500 ; Base grid spacing for stratified sampling
   :jitter-radius-m 300 ; Random jitter radius
   :border-weight-decay 500 ; Distance decay for border weighting (meters)
   :quality-threshold 0.7}) ; Minimum quality score for point sets

;; Utility functions

(defn degrees-to-meters
  "Convert degree differences to approximate meters (at latitude ~32)"
  [lat-diff lng-diff lat-center]
  (let [lat-m (* lat-diff 111320.0) ; meters per degree latitude
        lng-m (* lng-diff (* 111320.0 (Math/cos (Math/toRadians lat-center))))]
    {:lat-m lat-m :lng-m lng-m}))

(defn meters-to-degrees
  "Convert meters to approximate degree differences (at latitude ~32)"
  [meters-lat meters-lng lat-center]
  (let [lat-deg (/ meters-lat 111320.0)
        lng-deg (/ meters-lng (* 111320.0 (Math/cos (Math/toRadians lat-center))))]
    {:lat-deg lat-deg :lng-deg lng-deg}))

(defn haversine-distance
  "Calculate distance between two points in meters using Haversine formula"
  [p1 p2]
  (let [R 6371000.0 ; Earth radius in meters
        lat1 (Math/toRadians (:lat p1))
        lat2 (Math/toRadians (:lat p2))
        dlat (Math/toRadians (- (:lat p2) (:lat p1)))
        dlon (Math/toRadians (- (:lng p2) (:lng p1)))
        a (+ (* (Math/sin (/ dlat 2)) (Math/sin (/ dlat 2)))
             (* (Math/cos lat1) (Math/cos lat2)
                (Math/sin (/ dlon 2)) (Math/sin (/ dlon 2))))
        c (* 2 (Math/atan2 (Math/sqrt a) (Math/sqrt (- 1 a))))]
    (* R c)))

(defn point-in-bounds?
  "Check if point is within bounding box"
  [point bounds]
  (and (>= (:lat point) (:min-lat bounds))
       (<= (:lat point) (:max-lat bounds))
       (>= (:lng point) (:min-lng bounds))
       (<= (:lng point) (:max-lng bounds))))

(defn calculate-bounds
  "Calculate bounding box from city polygon or point collection"
  [city-data]
  (let [boundary (:boundary city-data)
        coords (if (map? boundary)
                 (:coordinates boundary) ; GeoJSON format
                 boundary) ; Raw coordinates
        lats (map second coords)
        lngs (map first coords)]
    {:min-lat (apply min lats)
     :max-lat (apply max lats)
     :min-lng (apply min lngs)
     :max-lng (apply max lngs)
     :center-lat (/ (+ (apply min lats) (apply max lats)) 2)
     :center-lng (/ (+ (apply min lngs) (apply max lngs)) 2)}))

;; Spatial sampling algorithms

(defn poisson-disk-sampling
  "Generate evenly distributed points using Poisson disk sampling.
   Ensures minimum distance between all points for uniform coverage."
  [bounds config]
  (let [rng (fr/rng :mersenne)
        min-distance (:min-distance-m config)
        max-attempts (:max-attempts config)

        ;; Convert to working coordinates (approximate meters)
        center-lat (:center-lat bounds)
        width-m (* (- (:max-lng bounds) (:min-lng bounds))
                   111320.0 (Math/cos (Math/toRadians center-lat)))
        height-m (* (- (:max-lat bounds) (:min-lat bounds)) 111320.0)

        result-points (atom [])]

    (println (format "🎯 Poisson disk sampling in %.1f×%.1f km area"
                     (/ width-m 1000) (/ height-m 1000)))

    ;; Dart throwing algorithm
    (loop [attempts 0 successful 0]
      (when (and (< attempts 10000) (< successful 50)) ; Max 50 points
        (let [candidate {:lat (+ (:min-lat bounds)
                                 (* (fr/drandom rng)
                                    (- (:max-lat bounds) (:min-lat bounds))))
                         :lng (+ (:min-lng bounds)
                                 (* (fr/drandom rng)
                                    (- (:max-lng bounds) (:min-lng bounds))))
                         :method :poisson-disk}

              ;; Check minimum distance to all existing points
              valid? (every? #(>= (haversine-distance candidate %) min-distance)
                             @result-points)]

          (if valid?
            (do
              (swap! result-points conj candidate)
              (recur (inc attempts) (inc successful)))
            (recur (inc attempts) successful)))))

    (println (format "  Generated %d points (%d attempts)"
                     (count @result-points) (count @result-points)))
    @result-points))

(defn stratified-grid-sampling
  "Generate points on a regular grid with random jitter.
   Provides systematic coverage with controlled randomness."
  [bounds config]
  (let [rng (fr/rng :mersenne)
        grid-spacing-m (:grid-spacing-m config)
        jitter-radius-m (:jitter-radius-m config)

        center-lat (:center-lat bounds)

        ;; Convert grid spacing to degrees
        conversions (meters-to-degrees grid-spacing-m grid-spacing-m center-lat)
        grid-lat-step (:lat-deg conversions)
        grid-lng-step (:lng-deg conversions)

        ;; Convert jitter to degrees
        jitter-conversions (meters-to-degrees jitter-radius-m jitter-radius-m center-lat)
        jitter-lat (:lat-deg jitter-conversions)
        jitter-lng (:lng-deg jitter-conversions)

        result-points (atom [])]

    (println (format "🗓️  Stratified grid sampling with %.0fm spacing, ±%.0fm jitter"
                     grid-spacing-m jitter-radius-m))

    ;; Generate grid points with jitter
    (loop [lat (:min-lat bounds)]
      (when (<= lat (:max-lat bounds))
        (loop [lng (:min-lng bounds)]
          (when (<= lng (:max-lng bounds))
            (let [jittered-lat (+ lat (* (- (fr/drandom rng) 0.5) 2 jitter-lat))
                  jittered-lng (+ lng (* (- (fr/drandom rng) 0.5) 2 jitter-lng))
                  point {:lat jittered-lat
                         :lng jittered-lng
                         :method :stratified-grid
                         :grid-base {:lat lat :lng lng}}]

              ;; Only add if within bounds
              (when (point-in-bounds? point bounds)
                (swap! result-points conj point)))

            (recur (+ lng grid-lng-step))))
        (recur (+ lat grid-lat-step))))

    (println (format "  Generated %d grid points" (count @result-points)))
    @result-points))

(defn distance-weighted-sampling
  "Generate points weighted by distance to city borders.
   Focuses sampling on border areas important for the game."
  [city-a city-b config]
  (let [rng (fr/rng :mersenne)
        bounds-a (calculate-bounds city-a)
        bounds-b (calculate-bounds city-b)

        ;; Expand bounds to cover border areas
        combined-bounds {:min-lat (min (:min-lat bounds-a) (:min-lat bounds-b))
                         :max-lat (max (:max-lat bounds-a) (:max-lat bounds-b))
                         :min-lng (min (:min-lng bounds-a) (:min-lng bounds-b))
                         :max-lng (max (:max-lng bounds-a) (:max-lng bounds-b))
                         :center-lat (/ (+ (:center-lat bounds-a) (:center-lat bounds-b)) 2)
                         :center-lng (/ (+ (:center-lng bounds-a) (:center-lng bounds-b)) 2)}

        result-points (atom [])]

    (println (format "⚖️  Distance-weighted border sampling between %s and %s"
                     (:name city-a) (:name city-b)))

    ;; Generate candidates and weight by border proximity
    (loop [attempts 0 accepted 0]
      (when (and (< attempts 5000) (< accepted 30)) ; Max 30 border points
        (let [candidate {:lat (+ (:min-lat combined-bounds)
                                 (* (fr/drandom rng)
                                    (- (:max-lat combined-bounds) (:min-lat combined-bounds))))
                         :lng (+ (:min-lng combined-bounds)
                                 (* (fr/drandom rng)
                                    (- (:max-lng combined-bounds) (:min-lng combined-bounds))))
                         :method :border-weighted}

              ;; Calculate distances to both city centers (proxy for border distance)
              dist-a (haversine-distance candidate
                                         {:lat (:center-lat bounds-a)
                                          :lng (:center-lng bounds-a)})
              dist-b (haversine-distance candidate
                                         {:lat (:center-lat bounds-b)
                                          :lng (:center-lng bounds-b)})

              ;; Weight function: prefer points between cities
              border-score (/ 1.0 (+ 1.0 (Math/abs (- dist-a dist-b))))
              accept-probability (* 0.1 border-score) ; Base 10% acceptance

              ;; Check minimum distance to existing points
              min-dist-ok? (every? #(>= (haversine-distance candidate %)
                                        (:min-distance-m config))
                                   @result-points)]

          (if (and min-dist-ok?
                   (< (fr/drandom rng) accept-probability))
            (do
              (swap! result-points conj (assoc candidate
                                               :border-score border-score
                                               :dist-a dist-a
                                               :dist-b dist-b))
              (recur (inc attempts) (inc accepted)))
            (recur (inc attempts) accepted)))))

    (println (format "  Generated %d border-weighted points" (count @result-points)))
    @result-points))

;; Quality assessment functions

(defn calculate-diversity-metrics
  "Calculate comprehensive diversity metrics for a point set"
  [points]
  (when (> (count points) 1)
    (let [distances (for [i (range (count points))
                          j (range (inc i) (count points))]
                      (haversine-distance (nth points i) (nth points j)))
          min-distance (apply min distances)
          max-distance (apply max distances)
          mean-distance (fs/mean distances)
          std-distance (fs/stddev distances)

          ;; Calculate coverage area (bounding box)
          lats (map :lat points)
          lngs (map :lng points)
          lat-range (- (apply max lats) (apply min lats))
          lng-range (- (apply max lngs) (apply min lngs))
          coverage-area (* lat-range lng-range)]

      {:count (count points)
       :min-distance min-distance
       :max-distance max-distance
       :mean-distance mean-distance
       :std-distance std-distance
       :uniformity-score (if (> mean-distance 0) (/ std-distance mean-distance) 0)
       :coverage-area coverage-area
       :density (/ (count points) coverage-area)})))

(defn diversity-quality-score
  "Calculate overall quality score (0-100) for point diversity"
  [points config]
  (let [metrics (calculate-diversity-metrics points)]
    (when metrics
      (let [;; Distance compliance (40 points max)
            min-dist-score (min 40.0
                                (* 40.0 (/ (:min-distance metrics)
                                           (:min-distance-m config))))

            ;; Uniformity score (30 points max) - lower uniformity is better
            uniformity-score (max 0.0 (- 30.0 (* 30.0 (:uniformity-score metrics))))

            ;; Coverage area (20 points max) - normalized to reasonable area
            coverage-score (min 20.0 (* 20.0 (Math/sqrt (:coverage-area metrics))))

            ;; Point count bonus (10 points max)  
            count-score (min 10.0 (/ (:count metrics) 5.0))

            total-score (+ min-dist-score uniformity-score coverage-score count-score)]

        (max 0.0 (min 100.0 total-score))))))

;; Main API functions

(defn generate-diverse-points
  "Generate diverse sampling points using specified algorithm"
  [city-data algorithm & {:keys [config] :or {config default-config}}]
  (let [bounds (calculate-bounds city-data)]
    (case algorithm
      :poisson-disk (poisson-disk-sampling bounds config)
      :stratified-grid (stratified-grid-sampling bounds config)
      :combined (concat (poisson-disk-sampling bounds (assoc config :min-distance-m 1200))
                        (stratified-grid-sampling bounds config))
      ;; Default to poisson disk
      (poisson-disk-sampling bounds config))))

(defn generate-border-focused-points
  "Generate points focused on border areas between two cities"
  [city-a city-b & {:keys [config] :or {config default-config}}]
  (distance-weighted-sampling city-a city-b config))

(defn optimize-point-diversity
  "Optimize an existing point set for better diversity using hill climbing"
  [initial-points bounds config iterations]
  (let [rng (fr/rng :mersenne)]

    (loop [current-points initial-points
           best-score (diversity-quality-score initial-points config)
           iteration 0]

      (if (>= iteration iterations)
        current-points

        (let [;; Create candidate by perturbing a random point
              point-idx (fr/irand rng (count current-points))
              old-point (nth current-points point-idx)

              ;; Small perturbation (±200m)
              perturbation (meters-to-degrees 200 200 (:center-lat bounds))
              new-point {:lat (+ (:lat old-point)
                                 (* (- (fr/drandom rng) 0.5) 2 (:lat-deg perturbation)))
                         :lng (+ (:lng old-point)
                                 (* (- (fr/drandom rng) 0.5) 2 (:lng-deg perturbation)))
                         :method (:method old-point)}

              candidate-points (assoc (vec current-points) point-idx new-point)
              candidate-score (diversity-quality-score candidate-points config)]

          ;; Accept if better or within bounds
          (if (and (> candidate-score best-score)
                   (point-in-bounds? new-point bounds))
            (recur candidate-points candidate-score (inc iteration))
            (recur current-points best-score (inc iteration))))))))

;; Testing and validation functions

(defn test-diversity-algorithms
  "Test and compare different diversity algorithms"
  [city-name]
  (let [city (cities/get-city cities/cities city-name)
        config default-config

        algorithms {:poisson-disk #(generate-diverse-points city :poisson-disk :config config)
                    :stratified-grid #(generate-diverse-points city :stratified-grid :config config)
                    :combined #(generate-diverse-points city :combined :config config)}]

    (println (format "\n🧪 TESTING DIVERSITY ALGORITHMS FOR %s" (clojure.string/upper-case (name city-name))))
    (println "=" (apply str (repeat 60 "=")))

    (doseq [[algorithm-name algorithm-fn] algorithms]
      (println (format "\n📊 %s:" (clojure.string/upper-case (name algorithm-name))))
      (let [points (algorithm-fn)
            metrics (calculate-diversity-metrics points)
            quality (diversity-quality-score points config)]

        (println (format "  Points generated: %d" (count points)))
        (when metrics
          (println (format "  Min distance: %.0fm" (:min-distance metrics)))
          (println (format "  Mean distance: %.0fm" (:mean-distance metrics)))
          (println (format "  Uniformity: %.3f (lower = better)" (:uniformity-score metrics)))
          (println (format "  Coverage area: %.2f km²" (* (:coverage-area metrics) 111.32 111.32)))
          (println (format "  Quality score: %.1f/100" quality)))

        ;; Return results for comparison
        {:algorithm algorithm-name
         :points points
         :metrics metrics
         :quality quality}))))

(comment
  ;; Test usage examples

  ;; Test with Tel Aviv
  (test-diversity-algorithms :tel-aviv)

  ;; Generate border-focused points
  (let [ta (cities/get-city cities/cities :tel-aviv)
        rg (cities/get-city cities/cities :ramat-gan)]
    (generate-border-focused-points ta rg))

  ;; Generate and optimize points
  (let [city (cities/get-city cities/cities :tel-aviv)
        initial (generate-diverse-points city :poisson-disk)
        bounds (calculate-bounds city)
        optimized (optimize-point-diversity initial bounds default-config 100)]
    (println "Before:" (diversity-quality-score initial default-config))
    (println "After:" (diversity-quality-score optimized default-config))))