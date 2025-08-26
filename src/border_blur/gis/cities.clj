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
  "Convert boundary coordinates to JTS polygon using WKT format"
  (try
    (let [wkt-coords (clojure.string/join ", "
                                          (map (fn [[lng lat]] (str lng " " lat)) boundary-coords))
          wkt (str "POLYGON((" wkt-coords "))")
          poly (jts/polygon-wkt wkt)]
      poly)
    (catch Exception e
      (println "Error creating polygon:" (.getMessage e))
      ;; Return a simple fallback polygon
      (jts/polygon-wkt "POLYGON((0 0, 1 0, 1 1, 0 1, 0 0))"))))

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