(ns border-blur.images.collector
  "Collect street view images uniformly distributed across NYC"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn read-api-token
  "Read Mapillary token from .env file"
  []
  (when (.exists (io/file ".env"))
    (let [env-content (slurp ".env")
          token-line (first (filter #(str/starts-with? % "MAPILLARY_TOKEN=")
                                    (str/split-lines env-content)))]
      (when token-line
        (str/trim (str/replace token-line "MAPILLARY_TOKEN=" ""))))))

(defn mapillary-api-call
  "Make a call to Mapillary API"
  [token bbox & {:keys [limit fields] 
                 :or {limit 50
                      fields "id,thumb_original_url,geometry,captured_at,compass_angle,is_pano,camera_type,width,height,creator_username"}}]
  (try
    (let [response (http/get "https://graph.mapillary.com/images"
                            {:query-params {:access_token token
                                          :bbox (str (:west bbox) "," 
                                                    (:south bbox) "," 
                                                    (:east bbox) "," 
                                                    (:north bbox))
                                          :limit limit
                                          :fields fields}
                             :as :json
                             :socket-timeout 10000
                             :conn-timeout 10000})]
      (if (= 200 (:status response))
        {:success true
         :data (get-in response [:body :data] [])}
        {:success false
         :error (format "API returned status %d" (:status response))}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defn is-quality-image?
  "Filter out low quality images based on multiple criteria"
  [image-data]
  (let [;; Check if panoramic
        is-pano? (or (:is_pano image-data)
                    (= "spherical" (:camera_type image-data)))
        
        ;; Check resolution (filter out very low resolution)
        width (:width image-data)
        height (:height image-data)
        has-good-resolution? (and width height
                                  (>= width 800)
                                  (>= height 600))
        
        ;; Check age (prefer recent images, but accept up to 5 years old)
        captured-at (:captured_at image-data)
        age-ms (when captured-at
                 (- (System/currentTimeMillis) captured-at))
        age-days (when age-ms (/ age-ms 86400000))
        is-recent? (or (nil? age-days)
                      (<= age-days 1825)) ; 5 years
        
        ;; Calculate quality score
        quality-score (cond-> 0
                       (not is-pano?) (+ 40)
                       has-good-resolution? (+ 30)
                       is-recent? (+ 20)
                       (and age-days (<= age-days 365)) (+ 10))] ; Bonus for very recent
    
    ;; Accept if quality score is >= 70 (out of 100)
    (>= quality-score 70)))

(defn create-uniform-grid
  "Create a uniform grid of points across a bounding box"
  [min-lng max-lng min-lat max-lat grid-size]
  (let [lng-step (/ (- max-lng min-lng) (dec grid-size))
        lat-step (/ (- max-lat min-lat) (dec grid-size))]
    (for [i (range grid-size)
          j (range grid-size)]
      {:lng (+ min-lng (* i lng-step))
       :lat (+ min-lat (* j lat-step))})))

(defn fetch-images-at-point
  "Fetch quality images near a specific point"
  [token point radius-deg]
  (let [bbox {:west (- (:lng point) radius-deg)
              :east (+ (:lng point) radius-deg)
              :south (- (:lat point) radius-deg)
              :north (+ (:lat point) radius-deg)}
        response (mapillary-api-call token bbox :limit 20)]
    (if (:success response)
      (->> (:data response)
           (filter is-quality-image?)
           (map (fn [img]
                  {:id (:id img)
                   :url (:thumb_original_url img)
                   :lat (get-in img [:geometry :coordinates 1])
                   :lng (get-in img [:geometry :coordinates 0])
                   :captured-at (:captured_at img)
                   :compass-angle (:compass_angle img)
                   :width (:width img)
                   :height (:height img)
                   :creator (:creator_username img)
                   :grid-point point})))
      [])))

(defn collect-nyc-images
  "Collect images uniformly distributed across NYC"
  [& {:keys [grid-size max-images-per-point]
      :or {grid-size 20
           max-images-per-point 5}}]
  (let [token (read-api-token)
        ;; NYC approximate bounding box
        nyc-bounds {:min-lng -74.26
                   :max-lng -73.70
                   :min-lat 40.49
                   :max-lat 40.92}
        
        grid-points (create-uniform-grid 
                     (:min-lng nyc-bounds)
                     (:max-lng nyc-bounds)
                     (:min-lat nyc-bounds)
                     (:max-lat nyc-bounds)
                     grid-size)
        
        ;; Small radius for each grid point (about 200 meters)
        search-radius 0.002]
    
    (println (format "Collecting images from %d grid points across NYC..." 
                    (count grid-points)))
    (println "Grid configuration:")
    (println (format "  - Grid size: %dx%d" grid-size grid-size))
    (println (format "  - Search radius: ~%.0f meters" (* search-radius 111000)))
    (println (format "  - Max images per point: %d" max-images-per-point))
    
    (let [all-images (atom [])
          progress (atom 0)]
      (doseq [point grid-points]
        (let [images (fetch-images-at-point token point search-radius)]
          (when (seq images)
            (swap! all-images into (take max-images-per-point images)))
          (swap! progress inc)
          (when (zero? (mod @progress 10))
            (println (format "Progress: %d/%d points processed, %d images collected"
                           @progress (count grid-points) (count @all-images))))))
      
      (println (format "\nCollection complete: %d images from %d points"
                      (count @all-images) (count grid-points)))
      @all-images)))

(defn save-collected-images
  "Save collected images to file"
  [images filename]
  (io/make-parents filename)
  (spit filename (json/generate-string images {:pretty true}))
  (println (format "Saved %d images to %s" (count images) filename)))