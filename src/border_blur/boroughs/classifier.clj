(ns border-blur.boroughs.classifier
  "Classify points into NYC boroughs"
  (:require [geo.jts :as jts]
            [geo.spatial :as spatial]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn load-borough-data
  "Load borough boundaries from EDN file"
  []
  (when (.exists (io/file "resources/boroughs/nyc-boroughs.edn"))
    (edn/read-string (slurp "resources/boroughs/nyc-boroughs.edn"))))

(defn boundary->polygon
  "Convert boundary coordinates to JTS polygon"
  [boundary-coords]
  (try
    (let [;; Filter out nil coordinates and ensure doubles
          valid-coords (filter (fn [[lng lat]]
                                 (and lng lat
                                      (number? lng)
                                      (number? lat)))
                               boundary-coords)
          ;; Ensure polygon is closed
          closed-coords (if (and (seq valid-coords)
                                 (= (first valid-coords) (last valid-coords)))
                          valid-coords
                          (conj (vec valid-coords) (first valid-coords)))]
      (when (>= (count closed-coords) 4) ; Minimum for a valid polygon
        (let [;; Convert to JTS Coordinate objects
              jts-coords (map (fn [[lng lat]]
                                (jts/coordinate (double lng) (double lat)))
                              closed-coords)
              coord-array (into-array org.locationtech.jts.geom.Coordinate jts-coords)
              ring (jts/linear-ring coord-array)
              poly (jts/polygon ring)]
          poly)))
    (catch Exception e
      (println "Error creating polygon:" (.getMessage e))
      nil)))

(defn point-in-borough?
  "Check if a point is inside a borough polygon"
  [lat lng borough-polygon]
  (try
    ;; Note: boundary data is [lng, lat] but we need to create point as [lat, lng] for spatial operations
    (spatial/intersects? borough-polygon (jts/point lat lng))
    (catch Exception e
      false)))

(defn classify-point
  "Classify a point into a borough"
  [lat lng boroughs]
  (let [boroughs-with-polygons
        (reduce (fn [acc [borough-key borough-data]]
                  (if-let [polygon (boundary->polygon (:simplified-boundary borough-data))]
                    (assoc acc borough-key (assoc borough-data :polygon polygon))
                    acc))
                {}
                boroughs)]

    ;; Find which borough contains the point
    (loop [borough-entries (seq boroughs-with-polygons)]
      (if-let [[borough-key borough-data] (first borough-entries)]
        (if (point-in-borough? lat lng (:polygon borough-data))
          borough-key
          (recur (rest borough-entries)))
        :unknown))))

(defn classify-with-confidence
  "Classify a point with confidence based on distance to boundaries"
  [lat lng boroughs]
  (let [;; Note: boundary data is [lng, lat] but we need to create point as [lat, lng] for spatial operations
        point (jts/point lat lng)
        boroughs-with-polygons
        (reduce (fn [acc [borough-key borough-data]]
                  (if-let [polygon (boundary->polygon (:simplified-boundary borough-data))]
                    (assoc acc borough-key (assoc borough-data :polygon polygon))
                    acc))
                {}
                boroughs)

        ;; Check containment and calculate distances
        classifications
        (for [[borough-key borough-data] boroughs-with-polygons]
          (let [polygon (:polygon borough-data)
                contains? (try
                            (spatial/intersects? polygon point)
                            (catch Exception _ false))
                distance (try
                           (if contains?
                             0.0
                             (spatial/distance polygon point))
                           (catch Exception _ Double/MAX_VALUE))]
            {:borough borough-key
             :contains contains?
             :distance distance}))]

    ;; Sort by distance and containment
    (let [sorted-classifications (sort-by :distance classifications)
          best-match (first sorted-classifications)]

      (cond
        ;; Clear containment
        (:contains best-match)
        {:borough (:borough best-match)
         :confidence :high
         :distance 0}

        ;; Very close to a borough (within ~100 meters)
        (< (:distance best-match) 0.001)
        {:borough (:borough best-match)
         :confidence :medium
         :distance (:distance best-match)}

        ;; Somewhat close (within ~500 meters)
        (< (:distance best-match) 0.005)
        {:borough (:borough best-match)
         :confidence :low
         :distance (:distance best-match)}

        ;; Too far from any borough
        :else
        {:borough :unknown
         :confidence :none
         :distance (:distance best-match)}))))

(defn classify-images
  "Classify a collection of images by their coordinates"
  [images boroughs]
  (println (format "Classifying %d images into boroughs..." (count images)))
  (let [classified
        (map (fn [image]
               (let [classification (classify-with-confidence
                                     (:lat image)
                                     (:lng image)
                                     boroughs)]
                 (assoc image
                        :borough (:borough classification)
                        :classification-confidence (:confidence classification))))
             images)

        ;; Count by borough
        borough-counts (frequencies (map :borough classified))]

    (println "\nClassification results:")
    (doseq [[borough count] (sort-by val > borough-counts)]
      (println (format "  %s: %d images" (name borough) count)))

    classified))

(defn get-borough-stats
  "Get statistics about image distribution across boroughs"
  [classified-images]
  (let [by-borough (group-by :borough classified-images)
        stats (for [[borough images] by-borough]
                (let [confidence-dist (frequencies (map :classification-confidence images))]
                  {:borough borough
                   :count (count images)
                   :high-confidence (get confidence-dist :high 0)
                   :medium-confidence (get confidence-dist :medium 0)
                   :low-confidence (get confidence-dist :low 0)}))]
    (sort-by :count > stats)))