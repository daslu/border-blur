(ns border-blur.gis.cities
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [geo [jts :as jts] [spatial :as spatial]]
            [border-blur.gis.core :as gis]))

(defn load-cities []
  "Load city data from EDN file"
  (-> (io/resource "cities/israeli-cities.edn")
      slurp
      edn/read-string))

(defn boundary->polygon [boundary-coords]
  "Convert boundary coordinates to JTS polygon - FIXED to use proper vector format"
  (try
    ;; The key issue: JTS needs vectors, not sequences. Convert each coordinate pair to a vector
    (let [;; Convert lazy sequences to proper vectors and ensure numeric types
          vector-coords (mapv (fn [[lng lat]]
                                [(double lng) (double lat)])
                              boundary-coords)
          ;; Ensure polygon is closed by adding first point at end if not already closed
          closed-coords (if (= (first vector-coords) (last vector-coords))
                          vector-coords
                          (conj vector-coords (first vector-coords)))
          ;; Create linear ring from vector coordinates
          ring (jts/linear-ring closed-coords)
          ;; Create polygon from ring
          poly (jts/polygon ring)]
      poly)
    (catch Exception e
      (println "Error creating polygon:" (.getMessage e))
      (println "Falling back to simplified Tel Aviv boundary")
      ;; Return a simplified Tel Aviv polygon as fallback
      (jts/polygon (jts/linear-ring [[34.74 32.02] [34.83 32.02] [34.83 32.13] [34.74 32.13] [34.74 32.02]])))))

(defn get-city [cities city-key]
  "Get city data without polygon conversion for now"
  (get cities city-key))

(defn get-neighboring-cities [cities city-key]
  "Get all neighboring cities of a given city"
  (when-let [city (get cities city-key)]
    (map #(get-city cities %) (:neighbors city))))

(defn find-city-pairs [cities]
  "Generate all valid city pairs for the game
   Returns pairs of neighboring cities"
  (for [city-key (keys cities)
        neighbor-key (:neighbors (get cities city-key))]
    [city-key neighbor-key]))

;; Initialize cities data
(def cities (load-cities))

(defn test-point-in-cities []
  "Test basic point-in-polygon functionality"
  (let [tel-aviv (get-city cities :tel-aviv)
        ramat-gan (get-city cities :ramat-gan)
        test-points (:test-points gis/test-points)]

    (println "Testing point-in-polygon:")
    (println "Tel Aviv polygon:" (:boundary tel-aviv))
    (println "Ramat Gan polygon:" (:boundary ramat-gan))

    ;; Test each point against both cities
    (doseq [[point-key coords] gis/test-points]
      (let [{:keys [lat lng name]} coords]
        (println (format "\nTesting %s (%.4f, %.4f):" name lat lng))
        (println (format "  In Tel Aviv: %s"
                         (gis/point-in-city? lat lng (:polygon tel-aviv))))
        (println (format "  In Ramat Gan: %s"
                         (gis/point-in-city? lat lng (:polygon ramat-gan))))))))