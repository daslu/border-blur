(ns border-blur.images.even-distribution-collector
  "Even distribution image collector ensuring uniform coverage across Tel Aviv and nearby cities"
  (:require [border-blur.images.fetcher :as fetcher]
            [border-blur.gis.cities :as cities]
            [border-blur.gis.core :as gis-core]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [geo [jts :as jts] [spatial :as spatial]]
            [cheshire.core :as json])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(def distribution-config
  "Configuration for even distribution collection"
  {:tel-aviv-yafo {:grid-size-meters 800
                   :target-images 30
                   :min-distance-meters 400}
   :ramat-gan {:grid-size-meters 800
               :target-images 25
               :min-distance-meters 400}
   :givatayim {:grid-size-meters 600
               :target-images 15
               :min-distance-meters 300}
   :bnei-brak {:grid-size-meters 600
               :target-images 15
               :min-distance-meters 300}
   :bat-yam {:grid-size-meters 600
             :target-images 15
             :min-distance-meters 300}
   :holon {:grid-size-meters 700
           :target-images 20
           :min-distance-meters 350}})

(defn calculate-city-area
  "Calculate approximate area of city in square kilometers"
  [city-data]
  (let [boundary (:boundary city-data)
        [min-lng max-lng min-lat max-lat]
        (reduce (fn [[min-lng max-lng min-lat max-lat] [lng lat]]
                  [(min min-lng lng) (max max-lng lng)
                   (min min-lat lat) (max max-lat lat)])
                [180 -180 90 -90]
                boundary)
        width-km (* (- max-lng min-lng) 111.0 (Math/cos (Math/toRadians (/ (+ min-lat max-lat) 2))))
        height-km (* (- max-lat min-lat) 111.0)]
    (* width-km height-km)))

(defn create-uniform-grid
  "Create a uniform grid of collection points ensuring even distribution"
  [city-key city-data config]
  (let [boundary (:boundary city-data)
        grid-size-meters (get-in config [city-key :grid-size-meters] 700)
        grid-size-deg (/ grid-size-meters 111000.0)

        [min-lng max-lng min-lat max-lat]
        (reduce (fn [[min-lng max-lng min-lat max-lat] [lng lat]]
                  [(min min-lng lng) (max max-lng lng)
                   (min min-lat lat) (max max-lat lat)])
                [180 -180 90 -90]
                boundary)

        ;; Calculate optimal grid spacing for uniform coverage
        lng-steps (int (Math/ceil (/ (- max-lng min-lng) grid-size-deg)))
        lat-steps (int (Math/ceil (/ (- max-lat min-lat) grid-size-deg)))

        ;; Adjust grid size to fit exactly
        adjusted-lng-step (/ (- max-lng min-lng) lng-steps)
        adjusted-lat-step (/ (- max-lat min-lat) lat-steps)

        ;; Generate uniform grid points
        grid-points
        (for [lng-idx (range lng-steps)
              lat-idx (range lat-steps)
              :let [;; Center points in each grid cell
                    lng (+ min-lng (* (+ lng-idx 0.5) adjusted-lng-step))
                    lat (+ min-lat (* (+ lat-idx 0.5) adjusted-lat-step))]
              ;; Verify point is within city boundaries
              :when (= (gis-core/classify-point-by-city lat lng cities/cities) city-key)]
          {:lat lat
           :lng lng
           :grid-index {:lng-idx lng-idx :lat-idx lat-idx}
           :priority 1.0})]

    (println (str "Created uniform grid for " (name city-key) ":"))
    (println (str "  Grid dimensions: " lng-steps " x " lat-steps))
    (println (str "  Cell size: ~" grid-size-meters "m"))
    (println (str "  Total grid points in city: " (count grid-points)))
    grid-points))

(defn select-evenly-distributed-points
  "Select subset of points ensuring maximum coverage with even distribution"
  [grid-points target-count min-distance-meters]
  (let [selected (atom [])
        candidates (atom (vec grid-points))
        min-distance-deg (/ min-distance-meters 111000.0)]

    ;; Start with corner points for maximum initial coverage
    (let [sorted-by-lng (sort-by :lng grid-points)
          sorted-by-lat (sort-by :lat grid-points)
          corner-points [(first sorted-by-lng)
                         (last sorted-by-lng)
                         (first sorted-by-lat)
                         (last sorted-by-lat)]]
      (doseq [point (distinct corner-points)]
        (when point
          (swap! selected conj point)
          (swap! candidates #(remove (fn [p] (= p point)) %)))))

    ;; Fill in remaining points using maximum distance strategy
    (while (and (< (count @selected) target-count)
                (seq @candidates))
      (let [;; Find candidate furthest from all selected points
            best-candidate
            (apply max-key
                   (fn [candidate]
                     (if (empty? @selected)
                       1.0
                       (apply min
                              (map (fn [selected-point]
                                     (Math/sqrt
                                      (+ (Math/pow (- (:lat candidate) (:lat selected-point)) 2)
                                         (Math/pow (- (:lng candidate) (:lng selected-point)) 2))))
                                   @selected))))
                   @candidates)]

        ;; Check minimum distance constraint
        (if (or (empty? @selected)
                (every? (fn [selected-point]
                          (let [dist (Math/sqrt
                                      (+ (Math/pow (- (:lat best-candidate) (:lat selected-point)) 2)
                                         (Math/pow (- (:lng best-candidate) (:lng selected-point)) 2)))]
                            (>= dist min-distance-deg)))
                        @selected))
          ;; Add the point
          (do
            (swap! selected conj best-candidate)
            (swap! candidates #(remove (fn [p] (= p best-candidate)) %)))
          ;; Remove candidate if too close
          (swap! candidates #(remove (fn [p] (= p best-candidate)) %)))))

    @selected))

(defn collect-evenly-distributed-images
  "Collect images with perfectly even distribution across the city"
  [city-key]
  (let [city-data (get cities/cities city-key)
        config distribution-config
        target-count (get-in config [city-key :target-images] 20)
        min-distance (get-in config [city-key :min-distance-meters] 400)]

    (if-not city-data
      {:error (str "City not found: " city-key)}

      (let [;; Generate uniform grid
            grid-points (create-uniform-grid city-key city-data config)

            ;; Select evenly distributed subset
            selected-points (select-evenly-distributed-points
                             grid-points target-count min-distance)

            collected-images (atom [])
            rejected-count (atom 0)
            api-calls (atom 0)]

        (println (str "\n🎯 Even distribution collection for " (name city-key)))
        (println (str "   Target: " target-count " images"))
        (println (str "   Min spacing: " min-distance "m"))
        (println (str "   Selected " (count selected-points) " collection points"))

        ;; Collect from each selected point
        (doseq [[idx point] (map-indexed vector selected-points)]
          (print (str "Collecting " (inc idx) "/" (count selected-points) "..."))
          (swap! api-calls inc)

          (let [api-result (fetcher/fetch-from-multiple-sources point 150)]
            (if (and (:success api-result) (seq (:images api-result)))
              (let [best-image (first (:images api-result))
                    metadata {:city city-key
                              :grid-position (:grid-index point)
                              :collection-method "even-distribution"
                              :coordinates {:lat (:lat best-image)
                                            :lng (:lng best-image)}
                              :timestamp (.format (LocalDateTime/now)
                                                  DateTimeFormatter/ISO_LOCAL_DATE_TIME)}]
                (swap! collected-images conj (assoc best-image :metadata metadata))
                (println " ✓"))
              (do
                (swap! rejected-count inc)
                (println " ✗")))))

        (let [coverage-percent (* 100.0 (/ (count @collected-images) target-count))]
          (println (str "\n✅ Collection complete for " (name city-key)))
          (println (str "   Collected: " (count @collected-images) "/" target-count
                        " (" (format "%.1f" coverage-percent) "% coverage)"))
          (println (str "   Failed points: " @rejected-count))
          (println (str "   API calls: " @api-calls))

          {:success true
           :city city-key
           :collected (count @collected-images)
           :target target-count
           :coverage-percent coverage-percent
           :images @collected-images
           :api-calls @api-calls})))))

(defn collect-all-cities-evenly
  "Collect images for all cities with even distribution"
  []
  (println "\n" (str/join "" (repeat 60 "=")) "\n")
  (println "STARTING EVEN DISTRIBUTION COLLECTION FOR ALL CITIES")
  (println (str/join "" (repeat 60 "=")) "\n")

  (let [city-order [:tel-aviv-yafo :ramat-gan :givatayim :bnei-brak :bat-yam :holon]
        results (atom [])]

    (doseq [city-key city-order]
      (let [result (collect-evenly-distributed-images city-key)]
        (swap! results conj result)
        (Thread/sleep 2000))) ;; Brief pause between cities

    (println "\n" (str/join "" (repeat 60 "=")) "\n")
    (println "COLLECTION SUMMARY")
    (println (str/join "" (repeat 60 "=")) "\n")

    (let [total-collected (reduce + (map :collected @results))
          total-target (reduce + (map :target @results))
          total-api-calls (reduce + (map :api-calls @results))]

      (doseq [result @results]
        (println (str (name (:city result)) ": "
                      (:collected result) "/" (:target result) " images "
                      "(" (format "%.1f" (:coverage-percent result)) "%)")))

      (println (str "\nTotal: " total-collected "/" total-target " images"))
      (println (str "Total API calls: " total-api-calls))
      (println (str "Overall coverage: " (format "%.1f" (* 100.0 (/ total-collected total-target))) "%"))

      {:total-collected total-collected
       :total-target total-target
       :city-results @results})))

(defn visualize-distribution
  "Generate visualization data for image distribution"
  [city-key collected-images]
  (let [city-data (get cities/cities city-key)
        boundary (:boundary city-data)]

    {:city city-key
     :boundary boundary
     :images (map (fn [img]
                    {:lat (get-in img [:metadata :coordinates :lat])
                     :lng (get-in img [:metadata :coordinates :lng])
                     :id (:id img)})
                  collected-images)
     :stats {:total (count collected-images)
             :area-km2 (calculate-city-area city-data)
             :density-per-km2 (/ (count collected-images)
                                 (calculate-city-area city-data))}}))

(defn save-collection-results
  "Save collection results to file for analysis"
  [results filename]
  (let [output-path (str "resources/public/images/even-distribution/" filename)]
    (io/make-parents output-path)
    (spit output-path (json/generate-string results {:pretty true}))
    (println (str "Results saved to " output-path))))