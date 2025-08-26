(ns border-blur.gis.boundaries
  (:require [geo.jts :as jts]
            [geo.spatial :as spatial]
            [border-blur.gis.cities :as cities]))

(defn polygon-boundary-points
  "Extract boundary points from a polygon at regular intervals"
  [polygon num-points]
  (let [boundary (.getBoundary polygon)
        coords (.getCoordinates boundary)
        total-coords (dec (count coords)) ; Remove duplicate closing point
        step (/ total-coords num-points)]
    (for [i (range num-points)]
      (let [coord (nth coords (int (* i step)))
            lng (.x coord)
            lat (.y coord)]
        {:lng lng :lat lat}))))

(defn find-closest-approach
  "Find points where two city boundaries are closest"
  [city-a-polygon city-b-polygon num-samples]
  (let [a-points (polygon-boundary-points city-a-polygon num-samples)
        b-points (polygon-boundary-points city-b-polygon num-samples)]
    (for [a-point a-points
          b-point b-points
          :let [dist (spatial/distance
                      (jts/point (:lng a-point) (:lat a-point))
                      (jts/point (:lng b-point) (:lat b-point)))]]
      {:point-a a-point
       :point-b b-point
       :distance dist
       :midpoint {:lng (/ (+ (:lng a-point) (:lng b-point)) 2)
                  :lat (/ (+ (:lat a-point) (:lat b-point)) 2)}})))

(defn find-border-hotspots
  "Find the best locations near city borders for the game
   Returns points sorted by proximity to border"
  [city-a-polygon city-b-polygon max-distance-m num-candidates]
  (let [approach-points (find-closest-approach city-a-polygon city-b-polygon 20)
        closest-points (sort-by :distance approach-points)
        border-candidates (take num-candidates closest-points)]
    (for [{:keys [midpoint distance]} border-candidates
          :when (< distance max-distance-m)]
      {:coords [(:lng midpoint) (:lat midpoint)]
       :border-distance distance
       :point (jts/point (:lng midpoint) (:lat midpoint))})))

(defn generate-search-grid
  "Generate a grid of points around a border location for image searching"
  [center-lng center-lat radius-m grid-size]
  (let [meters-per-degree 111000 ; Approximate at this latitude
        degree-offset (/ radius-m meters-per-degree)
        step (/ (* 2 degree-offset) (dec grid-size))]
    (for [i (range grid-size)
          j (range grid-size)]
      {:lng (+ center-lng (- degree-offset) (* i step))
       :lat (+ center-lat (- degree-offset) (* j step))})))

(defn classify-border-difficulty
  "Classify how difficult it is to distinguish between two cities at a border point"
  [point city-a-polygon city-b-polygon city-a-data city-b-data]
  (let [dist-to-a (spatial/distance point city-a-polygon)
        dist-to-b (spatial/distance point city-b-polygon)
        min-dist (min dist-to-a dist-to-b)

        ;; Difficulty factors
        same-country? (= (:country city-a-data) (:country city-b-data))
        very-close? (< min-dist 500) ; Within 500m of border
        urban-area? true ; TODO: Check urban density

        difficulty-score (cond-> 0
                           same-country? (+ 2)
                           very-close? (+ 3)
                           urban-area? (+ 1))]
    (cond
      (>= difficulty-score 5) :hard
      (>= difficulty-score 3) :medium
      :else :easy)))

(defn find-game-worthy-points
  "Find points that make for interesting game challenges"
  [city-a city-b max-distance-m]
  (let [city-a-poly (cities/boundary->polygon (:boundary city-a))
        city-b-poly (cities/boundary->polygon (:boundary city-b))
        hotspots (find-border-hotspots city-a-poly city-b-poly max-distance-m 10)]
    (map (fn [hotspot]
           (assoc hotspot
                  :city-a (:name city-a)
                  :city-b (:name city-b)
                  :difficulty (classify-border-difficulty
                               (:point hotspot)
                               city-a-poly city-b-poly
                               city-a city-b)))
         hotspots)))