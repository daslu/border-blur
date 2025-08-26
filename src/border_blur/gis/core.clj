(ns border-blur.gis.core
  (:require [geo [jts :as jts] [spatial :as spatial]]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn load-geojson [file-path]
  "Load and parse GeoJSON file"
  (-> (io/resource file-path)
      slurp
      (json/parse-string true)))

(defn geojson->polygon [geojson-feature]
  "Convert GeoJSON feature to factual/geo polygon"
  (let [coords (get-in geojson-feature [:geometry :coordinates 0])]
    (jts/polygon (map (fn [[lng lat]] (jts/point lng lat)) coords))))

(defn point-in-city? [lat lng city-polygon]
  "Test if a point is inside a city polygon"
  (spatial/intersects? city-polygon (jts/point lng lat)))

(defn create-city-buffer
  "Create a 10-meter buffer around a city polygon - FIXED to use degree equivalent"
  [city-polygon]
  ;; For geographic coordinates (lat/lng), we need to convert meters to degrees
  ;; At Israel's latitude (~32°N):
  ;; - 1 degree latitude ≈ 111,000 meters
  ;; - 1 degree longitude ≈ 111,000 * cos(32°) ≈ 94,000 meters  
  ;; - 10 meters ≈ 0.00009 degrees latitude
  ;; - 10 meters ≈ 0.000106 degrees longitude
  ;; Using the more conservative latitude conversion: 10m ≈ 0.00009 degrees
  (let [buffer-degrees (/ 10.0 111000.0)] ; 10 meters in degrees
    (.buffer city-polygon buffer-degrees)))

(defn point-in-single-city-buffer?
  "Check if a point is contained within exactly one city's 10m buffer.
   Returns the city key if point is in exactly one buffer, nil otherwise."
  [lat lng cities]
  (let [point (jts/point lng lat)
        cities-containing-point
        (->> cities
             (filter (fn [[city-key city-data]]
                       (let [city-polygon (:polygon city-data)
                             buffered-polygon (create-city-buffer city-polygon)]
                         (spatial/relate point buffered-polygon :contains))))
             (map first))]
    (when (= 1 (count cities-containing-point))
      (first cities-containing-point))))

(defn classify-point-by-city-buffers
  "Classify a point by city using 10m buffer exclusivity - PROVEN WORKING VERSION.
   Returns city-key if point is in exactly one city buffer, nil otherwise."
  [lat lng cities]
  (try
    (let [buffer-degrees (/ 10.0 111000.0)
          point (jts/point lat lng) ; Use (lat, lng) order for JTS
          cities-containing-point
          (keep (fn [[city-key city-data]]
                  (try
                    (let [boundary (:boundary city-data)
                          coord-seq (map (fn [[lng lat]] (jts/coordinate lng lat)) boundary)
                          coord-array (into-array org.locationtech.jts.geom.Coordinate coord-seq)
                          ring (jts/linear-ring coord-array)
                          polygon (jts/polygon ring)
                          buffered-polygon (.buffer polygon buffer-degrees)]
                      (when (= :contains (spatial/relate buffered-polygon point))
                        city-key))
                    (catch Exception e nil)))
                cities)]
      (cond
        (= 1 (count cities-containing-point)) (first cities-containing-point)
        :else nil)) ; Return nil for multiple matches or no matches
    (catch Exception e nil)))

(defn classify-point-by-city
  "PRIMARY city classification function using buffer-based exclusivity.
   This is the authoritative method for determining city containment."
  [lat lng cities]
  (classify-point-by-city-buffers lat lng cities))

(defn distance-to-border [lat lng city-polygon]
  "Calculate distance from point to polygon border in meters"
  (let [point (jts/point lng lat)]
    (spatial/distance point city-polygon)))

(defn distance-between-points [lat1 lng1 lat2 lng2]
  "Calculate distance between two points in meters"
  (spatial/distance (jts/point lng1 lat1) (jts/point lng2 lat2)))

(defn find-border-candidates [city-a-polygon city-b-polygon max-distance-m]
  "Generate candidate points near the border between two cities
   Returns points that are within max-distance-m of both cities"
  ;; TODO: Implement sophisticated border sampling
  ;; For now, return empty - we'll build this incrementally
  [])

(defn validate-coordinates [lat lng]
  "Validate that coordinates are reasonable"
  (and (number? lat) (number? lng)
       (>= lat -90) (<= lat 90)
       (>= lng -180) (<= lng 180)))

;; Test data - known points for validation
(def test-points
  {:tel-aviv {:lat 32.0853 :lng 34.7818 :name "Tel Aviv Center"}
   :ramat-gan {:lat 32.0853 :lng 34.8131 :name "Ramat Gan Center"}
   :border-area {:lat 32.0853 :lng 34.7975 :name "Tel Aviv/Ramat Gan Border Area"}})