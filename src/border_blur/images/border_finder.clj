(ns border-blur.images.border-finder
  "Find optimal coordinates for collecting street-view images at city borders.
   Combines algorithmic border detection with real POI discovery."
  (:require [border-blur.gis.boundaries :as boundaries]
            [border-blur.gis.cities :as cities]
            [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [clojure.string :as str]))

;; POI Discovery via Overpass API

(defn border-overpass-query
  "Generate Overpass API query for POIs near a border point"
  [lat lng radius]
  (format "[out:json][timeout:25];
           (node[\"amenity\"](around:%d,%.6f,%.6f);
            node[\"shop\"](around:%d,%.6f,%.6f);
            node[\"highway\"~\"bus_stop|traffic_signals\"](around:%d,%.6f,%.6f);
            node[\"tourism\"](around:%d,%.6f,%.6f););
           out geom;"
          radius lat lng
          radius lat lng
          radius lat lng
          radius lat lng))

(defn query-overpass-api
  "Execute Overpass API query"
  [query]
  (let [result (shell/sh "curl" "-s" "-X" "POST"
                         "https://overpass-api.de/api/interpreter"
                         "-d" query)]
    (when (= 0 (:exit result))
      (try
        (json/read-str (:out result) :key-fn keyword)
        (catch Exception e
          (println "Error parsing Overpass response:" (.getMessage e))
          nil)))))

(defn extract-pois
  "Extract POI information from Overpass response"
  [overpass-response]
  (when-let [elements (get overpass-response :elements)]
    (for [element elements
          :when (and (:lat element) (:lon element))
          :let [tags (:tags element)
                name (or (:name tags)
                         (:shop tags)
                         (:amenity tags)
                         (:tourism tags)
                         "unnamed")
                type (or (:amenity tags)
                         (:shop tags)
                         (:tourism tags)
                         (when (= "bus_stop" (:highway tags)) "bus_stop")
                         "place")]]
      {:lat (:lat element)
       :lng (:lon element)
       :name (str/replace name #"[^a-zA-Z0-9א-ת -]" "")
       :type type
       :source "overpass"
       :osm-id (:id element)})))

(defn discover-border-pois
  "Find POIs near border points"
  [border-points radius]
  (println (format "🔍 Discovering POIs within %dm of border points..." radius))
  (doall
   (mapcat (fn [point]
             (let [query (border-overpass-query (:lat point) (:lng point) radius)
                   response (query-overpass-api query)
                   pois (extract-pois response)]
               (println (format "  Found %d POIs near %.4f,%.4f"
                                (count pois) (:lat point) (:lng point)))
               (map #(assoc %
                            :border-point point
                            :distance-from-border (:distance point))
                    pois)))
           border-points)))

;; Spatial Diversity

(defn haversine-distance
  "Calculate distance between two points in meters"
  [point1 point2]
  (let [R 6371000 ; Earth radius in meters
        lat1 (Math/toRadians (:lat point1))
        lat2 (Math/toRadians (:lat point2))
        dlat (Math/toRadians (- (:lat point2) (:lat point1)))
        dlon (Math/toRadians (- (:lng point2) (:lng point1)))
        a (+ (* (Math/sin (/ dlat 2)) (Math/sin (/ dlat 2)))
             (* (Math/cos lat1) (Math/cos lat2)
                (Math/sin (/ dlon 2)) (Math/sin (/ dlon 2))))
        c (* 2 (Math/atan2 (Math/sqrt a) (Math/sqrt (- 1 a))))]
    (* R c)))

(defn ensure-spatial-diversity
  "Filter points to ensure minimum separation"
  [points min-distance-m]
  (loop [selected []
         remaining (sort-by :priority > points)]
    (if (empty? remaining)
      selected
      (let [candidate (first remaining)
            far-enough? (every? #(>= (haversine-distance candidate %) min-distance-m)
                                selected)]
        (if far-enough?
          (recur (conj selected candidate) (rest remaining))
          (recur selected (rest remaining)))))))

;; Border Point Generation

(defn get-border-search-points
  "Generate search points along city borders grouped by difficulty"
  [city-a-name city-b-name]
  (let [city-a (cities/get-city cities/cities city-a-name)
        city-b (cities/get-city cities/cities city-b-name)
        _ (println (format "\n📍 Finding border points between %s and %s..."
                           (:name city-a) (:name city-b)))

        ;; Get algorithmic border points
        border-points (boundaries/find-game-worthy-points city-a city-b 500)
        _ (println (format "  Found %d border hotspots" (count border-points)))

        ;; Group by difficulty
        by-difficulty (group-by :difficulty border-points)]

    {:easy (vec (take 10 (:easy by-difficulty)))
     :medium (vec (take 15 (:medium by-difficulty)))
     :hard (vec (take 10 (:hard by-difficulty)))
     :all border-points}))

(defn enrich-border-points
  "Combine border detection with POI discovery"
  [city-a-name city-b-name & {:keys [poi-radius min-separation]
                              :or {poi-radius 200
                                   min-separation 150}}]
  (let [search-points (get-border-search-points city-a-name city-b-name)

        ;; Discover POIs near borders
        all-border-points (:all search-points)
        pois (discover-border-pois all-border-points poi-radius)
        _ (println (format "\n✅ Found %d POIs near border" (count pois)))

        ;; Ensure spatial diversity
        diverse-pois (ensure-spatial-diversity pois min-separation)
        _ (println (format "  After diversity filter: %d points" (count diverse-pois)))

        ;; Classify POIs by difficulty based on their border point
        classified-pois (map (fn [poi]
                               (assoc poi :difficulty
                                      (get-in poi [:border-point :difficulty] :medium)))
                             diverse-pois)

        ;; Group enriched points by difficulty
        by-difficulty (group-by :difficulty classified-pois)]

    {:easy (vec (take 20 (:easy by-difficulty)))
     :medium (vec (take 30 (:medium by-difficulty)))
     :hard (vec (take 20 (:hard by-difficulty)))
     :stats {:total-pois (count pois)
             :after-diversity (count diverse-pois)
             :border-points (count all-border-points)
             :cities [city-a-name city-b-name]}}))

;; Testing functions

(defn test-tel-aviv-ramat-gan
  "Test border finding between Tel Aviv and Ramat Gan"
  []
  (println "\n🧪 Testing Tel Aviv - Ramat Gan border...")
  (let [result (enrich-border-points :tel-aviv :ramat-gan)]
    (println "\nResults:")
    (println (format "  Easy points: %d" (count (:easy result))))
    (println (format "  Medium points: %d" (count (:medium result))))
    (println (format "  Hard points: %d" (count (:hard result))))
    (println "\nStats:" (:stats result))
    (println "\nSample easy POI:" (first (:easy result)))
    result))

(defn calculate-bbox
  "Calculate bounding box for Mapillary API"
  [lat lng radius-m]
  (let [lat-offset (/ radius-m 111320.0) ; meters to degrees latitude
        lng-offset (/ radius-m (* 111320.0 (Math/cos (Math/toRadians lat))))]
    {:west (- lng lng-offset)
     :south (- lat lat-offset)
     :east (+ lng lng-offset)
     :north (+ lat lat-offset)}))

(defn format-for-mapillary
  "Format enriched points for Mapillary API queries"
  [enriched-points]
  (map (fn [point]
         (assoc point
                :bbox (calculate-bbox (:lat point) (:lng point) 100)
                :search-radius 100))
       (apply concat (vals (select-keys enriched-points [:easy :medium :hard])))))

;; Main workflow

(defn find-border-images
  "Complete workflow to find image locations at city borders"
  [city-a-name city-b-name]
  (println (format "\n🎯 Finding image locations for %s - %s border game"
                   city-a-name city-b-name))
  (let [enriched (enrich-border-points city-a-name city-b-name)
        mapillary-ready (format-for-mapillary enriched)]

    (println (format "\n📋 Ready for Mapillary API:"))
    (println (format "  %d total search points" (count mapillary-ready)))
    (println (format "  Each with 100m search radius"))
    (println "\n💡 Next step: Use these points with Mapillary fetcher")

    {:search-points mapillary-ready
     :by-difficulty (select-keys enriched [:easy :medium :hard])
     :stats (:stats enriched)}))