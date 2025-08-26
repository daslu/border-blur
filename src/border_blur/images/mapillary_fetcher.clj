(ns border-blur.images.mapillary-fetcher
  "Mapillary API integration optimized for border area image collection.
   Based on proven approaches from places1 project."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [border-blur.images.border-finder :as border-finder]))

;; Configuration

(defn read-api-token
  "Read Mapillary token from various sources"
  []
  (or
   ;; Try environment variable
   (System/getenv "MAPILLARY_TOKEN")
   ;; Try .env file
   (when (.exists (io/file ".env"))
     (let [env-content (slurp ".env")
           token-line (first (filter #(str/starts-with? % "MAPILLARY_TOKEN=")
                                     (str/split-lines env-content)))]
       (when token-line
         (str/replace token-line "MAPILLARY_TOKEN=" ""))))
   ;; Try api-keys.edn
   (when (.exists (io/file "resources/api-keys.edn"))
     (let [config (read-string (slurp "resources/api-keys.edn"))]
       (get-in config [:mapillary :token])))))

;; Core API functions

(defn mapillary-api-call
  "Make a call to Mapillary API with proper error handling"
  [token bbox limit]
  (try
    (let [response (http/get "https://graph.mapillary.com/images"
                             {:query-params {:access_token token
                                             :bbox (format "%.6f,%.6f,%.6f,%.6f"
                                                           (:west bbox)
                                                           (:south bbox)
                                                           (:east bbox)
                                                           (:north bbox))
                                             :limit limit
                                             ;; Request fields needed for panoramic detection
                                             :fields "id,thumb_original_url,geometry,captured_at,compass_angle,is_pano,camera_type,width,height"}
                              :as :json
                              :throw-exceptions false})]
      (if (= 200 (:status response))
        {:success true
         :data (get-in response [:body :data] [])}
        {:success false
         :error (format "API returned status %d" (:status response))}))
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(defn fetch-border-images
  "Fetch images for a single border point"
  [border-point token & {:keys [limit] :or {limit 5}}]
  (let [bbox (:bbox border-point)]
    (when-let [response (mapillary-api-call token bbox limit)]
      (if (:success response)
        (map (fn [img]
               {:id (:id img)
                :url (:thumb_original_url img)
                :lat (get-in img [:geometry :coordinates 1])
                :lng (get-in img [:geometry :coordinates 0])
                :captured-at (:captured_at img)
                :compass-angle (:compass_angle img)
                :source :mapillary
                ;; Preserve metadata from border point
                :border-point border-point
                :difficulty (:difficulty border-point)
                :poi-name (:name border-point)
                :poi-type (:type border-point)})
             (:data response))
        []))))

(defn is-panoramic?
  "Advanced panoramic detection using multiple methods"
  [image-data]
  (let [;; Method 1: Direct API field (most reliable when available)
        api-pano (:is_pano image-data)

        ;; Method 2: Camera type detection  
        camera-type (:camera_type image-data)
        camera-pano (= camera-type "spherical")

        ;; Method 3: Aspect ratio analysis
        width (:width image-data)
        height (:height image-data)
        aspect-ratio (when (and width height (> height 0))
                       (/ (double width) (double height)))
        ratio-pano (when aspect-ratio
                     (or (>= aspect-ratio 2.0) ; Wide panoramic (2:1 or wider)
                         (<= aspect-ratio 0.5))) ; Tall panoramic (1:2 or taller)

        ;; Method 4: Dimension analysis (panoramic images are typically very large)
        dimension-pano (when (and width height)
                         (and (or (>= width 10000) (>= height 6000)) ; Very large images
                              (> (max width height) 5000))) ; At least one dimension > 5000

        ;; Method 5: URL pattern analysis (backup method)
        url (:thumb_original_url image-data)
        url-pano (when url
                   (or (str/includes? url "pano")
                       (str/includes? url "360")
                       (str/includes? url "spherical")))]

    ;; Return true if ANY method detects panoramic
    (or api-pano camera-pano ratio-pano dimension-pano url-pano)))

(defn calculate-image-quality-score
  "Calculate comprehensive image quality score (0-100)"
  [image-data]
  (let [width (:width image-data)
        height (:height image-data)
        captured-at (:captured_at image-data)
        camera-type (:camera_type image-data)

        ;; Derived metrics
        resolution (when (and width height) (* width height))
        megapixels (when resolution (/ resolution 1000000.0))
        age-days (when captured-at
                   (/ (- (System/currentTimeMillis) captured-at) 86400000.0))

        ;; Resolution scoring (0-35 points)
        resolution-score (cond
                           (nil? resolution) 0
                           (>= megapixels 8.0) 35 ; 8MP+ = excellent
                           (>= megapixels 4.0) 30 ; 4MP+ = very good
                           (>= megapixels 2.0) 25 ; 2MP+ = good
                           (>= megapixels 1.0) 15 ; 1MP+ = acceptable
                           (>= megapixels 0.5) 8 ; 0.5MP+ = poor
                           :else 0) ; <0.5MP = garbage

        ;; Recency scoring (0-25 points)  
        recency-score (cond
                        (nil? age-days) 0
                        (<= age-days 365) 25 ; Within 1 year
                        (<= age-days 730) 22 ; Within 2 years
                        (<= age-days 1095) 18 ; Within 3 years
                        (<= age-days 1825) 14 ; Within 5 years
                        (<= age-days 2555) 10 ; Within 7 years
                        (<= age-days 3650) 6 ; Within 10 years
                        :else 0) ; Older than 10 years

        ;; Camera type scoring (0-20 points)
        camera-score (case camera-type
                       "perspective" 20 ; Good camera
                       "spherical" 0 ; Panoramic = bad
                       nil 15 ; Unknown but likely OK
                       10) ; Other types

        ;; Aspect ratio scoring (0-10 points) - prefer normal ratios
        aspect-ratio (when (and width height (> height 0))
                       (/ (double width) (double height)))
        aspect-score (cond
                       (nil? aspect-ratio) 5
                      ;; Normal photo ratios
                       (and (>= aspect-ratio 1.2) (<= aspect-ratio 1.8)) 10
                      ;; Acceptable ratios  
                       (and (>= aspect-ratio 1.0) (<= aspect-ratio 2.0)) 8
                      ;; Wide but not panoramic
                       (and (>= aspect-ratio 0.8) (<= aspect-ratio 2.2)) 6
                      ;; Extreme ratios (likely problematic)
                       :else 2)

        ;; Dimension quality (0-10 points) - bonus for common good resolutions
        dimension-score (cond
                         ;; Excellent common resolutions
                          (and (= width 4032) (= height 3024)) 10 ; 4:3 12MP
                          (and (= width 3840) (= height 2160)) 10 ; 4K
                         ;; Good resolutions
                          (and (= width 1920) (= height 1080)) 8 ; 1080p
                          (and (= width 2560) (= height 1440)) 8 ; 1440p
                         ;; Acceptable
                          (and (= width 1280) (= height 720)) 6 ; 720p
                         ;; Very small (likely poor)
                          (and width height (< (* width height) 300000)) 1
                         ;; Reasonable size
                          (and width height (> (* width height) 1000000)) 6
                          :else 3)

        total-score (+ resolution-score recency-score camera-score aspect-score dimension-score)]

    total-score))

(defn filter-quality-images
  "Filter out panoramic and low-quality images"
  [images & {:keys [min-quality-score] :or {min-quality-score 70}}]
  (filter (fn [img]
            (and
             ;; Has valid URL (check both possible fields)
             (or (not (str/blank? (:url img)))
                 (not (str/blank? (:thumb_original_url img))))
             ;; Not panoramic
             (not (is-panoramic? img))
             ;; Meets quality threshold
             (>= (calculate-image-quality-score img) min-quality-score)))
          images))

;; Batch processing

(defn fetch-all-border-images
  "Fetch images for all border points with progress reporting"
  [search-points token & {:keys [images-per-point]
                          :or {images-per-point 3}}]
  (let [total (count search-points)]
    (println (format "\n📷 Fetching images from %d border points..." total))

    (doall
     (map-indexed
      (fn [idx point]
        (println (format "  [%d/%d] Fetching near %s (%.4f,%.4f)..."
                         (inc idx) total
                         (:name point "unnamed")
                         (:lat point) (:lng point)))
        (let [images (fetch-border-images point token :limit images-per-point)
              filtered (filter-quality-images images)]
          (println (format "    Found %d images (%d after quality filter)"
                           (count images) (count filtered)))
          filtered))
      search-points))))

;; Image organization

(defn organize-by-difficulty
  "Organize fetched images by difficulty level"
  [all-images]
  (let [flattened (apply concat all-images)
        by-difficulty (group-by :difficulty flattened)]
    {:easy (vec (:easy by-difficulty))
     :medium (vec (:medium by-difficulty))
     :hard (vec (:hard by-difficulty))
     :total (count flattened)
     :stats {:easy-count (count (:easy by-difficulty))
             :medium-count (count (:medium by-difficulty))
             :hard-count (count (:hard by-difficulty))}}))

(defn download-image
  "Download a single image to local filesystem"
  [image-url filepath]
  (try
    (let [response (http/get image-url {:as :byte-array})]
      (io/copy (:body response) (io/file filepath))
      true)
    (catch Exception e
      (println (format "    Failed to download: %s" (.getMessage e)))
      false)))

(defn save-border-images
  "Save images to appropriate directories based on difficulty"
  [organized-images output-dir city-pair]
  (let [base-dir (io/file output-dir "border-cache" city-pair)]

    ;; Create directories
    (doseq [difficulty ["easy" "medium" "hard"]]
      (.mkdirs (io/file base-dir difficulty)))

    (println (format "\n💾 Saving images to %s..." base-dir))

    ;; Save images by difficulty
    (doseq [[difficulty images] [["easy" (:easy organized-images)]
                                 ["medium" (:medium organized-images)]
                                 ["hard" (:hard organized-images)]]]
      (println (format "\n  %s images (%d):" (str/capitalize difficulty) (count images)))
      (doseq [[idx img] (map-indexed vector images)]
        (let [filename (format "%s-%03d-%s.jpg"
                               difficulty idx
                               (str/replace (:poi-name img "unnamed") #"[^a-zA-Z0-9-]" ""))
              filepath (io/file base-dir difficulty filename)]
          (when (download-image (:url img) filepath)
            (println (format "    ✓ %s" filename))))))))

;; Complete workflow

(defn collect-border-images
  "Complete workflow: find border points, fetch images, save locally"
  [city-a-name city-b-name]
  (let [token (read-api-token)]
    (if (nil? token)
      (do
        (println "\n❌ No Mapillary token found!")
        (println "Please set MAPILLARY_TOKEN environment variable or create .env file")
        (println "Get your token at: https://www.mapillary.com/dashboard/developers")
        nil)

      (let [;; Find border points with POIs
            _ (println (format "\n🎯 Starting image collection for %s - %s border"
                               city-a-name city-b-name))
            border-data (border-finder/find-border-images city-a-name city-b-name)
            search-points (:search-points border-data)

            ;; Fetch images from Mapillary
            all-images (fetch-all-border-images search-points token)

            ;; Organize by difficulty
            organized (organize-by-difficulty all-images)

            ;; Save to disk
            city-pair (format "%s-%s" city-a-name city-b-name)]

        (save-border-images organized "resources/public/images" city-pair)

        (println "\n✅ Image collection complete!")
        (println (format "  Total images: %d" (:total organized)))
        (println (format "  Easy: %d | Medium: %d | Hard: %d"
                         (get-in organized [:stats :easy-count])
                         (get-in organized [:stats :medium-count])
                         (get-in organized [:stats :hard-count])))

        ;; Return organized data
        organized))))

;; Testing functions

(defn test-mapillary-connection
  "Test if Mapillary API is accessible with current token"
  []
  (let [token (read-api-token)]
    (if (nil? token)
      (println "❌ No token found")
      (let [test-bbox {:west 34.823 :south 32.067 :east 34.826 :north 32.069}
            response (mapillary-api-call token test-bbox 1)]
        (if (:success response)
          (do
            (println "✅ Mapillary API connected successfully!")
            (println (format "  Found %d images in test area" (count (:data response)))))
          (println (format "❌ API error: %s" (:error response))))))))

(defn quick-fetch-tel-aviv-border
  "Quick test: fetch a few images from Tel Aviv/Ramat Gan border"
  []
  (let [token (read-api-token)]
    (when token
      (let [;; Known border area coordinates
            test-point {:lat 32.0680
                        :lng 34.8245
                        :name "test-border"
                        :difficulty :medium
                        :bbox (border-finder/calculate-bbox 32.0680 34.8245 100)}
            images (fetch-border-images test-point token :limit 3)]
        (println "\n🧪 Quick fetch results:")
        (doseq [img images]
          (println (format "  - Image %s at %.4f,%.4f"
                           (:id img) (:lat img) (:lng img))))
        images))))