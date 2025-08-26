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