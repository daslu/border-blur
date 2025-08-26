(ns border-blur.images.verified-collector
  "Advanced image collector with GIS verification and proper attribution using corrected boundaries"
  (:require [border-blur.images.fetcher :as fetcher]
            [border-blur.gis.cities :as cities]
            [border-blur.gis.core :as gis-core]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [geo.poly :as poly])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

;; ===== ATTRIBUTION AND METADATA =====

(def attribution-info
  "Complete attribution requirements for each image source"
  {:mapillary {:name "Mapillary"
               :license "CC BY-SA 4.0"
               :homepage "https://www.mapillary.com"
               :attribution-format "Image from Mapillary (CC BY-SA 4.0) - https://www.mapillary.com"
               :requirements ["Must display Mapillary logo for commercial use"
                              "Must link back to original image"
                              "Must credit photographer if available"]}
   :openstreetcam {:name "OpenStreetCam"
                   :license "CC BY-SA 4.0"
                   :homepage "https://openstreetcam.org"
                   :attribution-format "Image from OpenStreetCam (CC BY-SA 4.0) - https://openstreetcam.org"
                   :requirements ["Attribution to OpenStreetCam required"
                                  "Must link back to original image"]}
   :flickr {:name "Flickr"
            :license "Varies by image"
            :homepage "https://flickr.com"
            :attribution-format "Image from Flickr - License varies by photographer"
            :requirements ["Must check individual image license"
                           "Must attribute photographer"
                           "Commercial use depends on license"]}})

;; Load corrected boundaries for better GIS classification
(defn load-corrected-cities []
  "Load city data from corrected-boundaries.edn with better boundary definitions"
  (-> (io/resource "cities/corrected-boundaries.edn")
      slurp
      edn/read-string))

;; Use corrected boundaries by default, fall back to original if not available
(def corrected-cities
  (try
    (load-corrected-cities)
    (catch Exception e
      (println "Warning: Could not load corrected-boundaries.edn, falling back to original cities")
      (cities/load-cities))))

(defn generate-image-metadata
  "Generate comprehensive metadata for collected image"
  [image-data verification-result city-key]
  {:image-id (str (:source image-data) "-" (:id image-data))
   :original-source (:source image-data)
   :source-url (:url image-data)
   :coordinates {:lat (:lat image-data)
                 :lng (:lng image-data)}
   :city-verified city-key
   :gis-verified (:gis-verified verification-result true)
   :verification-accurate (:verification-accurate verification-result true)
   :captured-at (or (:captured-at image-data) (:shot-date image-data))
   :compass-angle (:compass-angle image-data)
   :attribution (get attribution-info (:source image-data))
   :collection-date (.format (LocalDateTime/now) DateTimeFormatter/ISO_LOCAL_DATE_TIME)
   :collector-version "1.0"
   :legal-notice "This image is used under fair use for educational purposes in a geography learning game."})

;; ===== INTELLIGENT SEARCH GRID GENERATION =====

(defn generate-city-search-points
  "Generate search points strategically within a city's actual boundaries"
  [city-data num-points]
  (let [boundary (:boundary city-data)
        [min-lng max-lng min-lat max-lat]
        (reduce (fn [[min-lng max-lng min-lat max-lat] [lng lat]]
                  [(min min-lng lng) (max max-lng lng)
                   (min min-lat lat) (max max-lat lat)])
                [180 -180 90 -90]
                boundary)

        ;; Generate candidate points in bounding box
        candidates (for [i (range (* num-points 3))] ; Generate 3x more candidates than needed
                     {:lng (+ min-lng (* (rand) (- max-lng min-lng)))
                      :lat (+ min-lat (* (rand) (- max-lat min-lat)))})

        ;; Filter to only points actually inside city boundaries  
        valid-points (filter (fn [pt]
                               (try
                                 (gis-core/point-in-city? (:lat pt) (:lng pt) boundary)
                                 (catch Exception e
                                   ;; Fallback to simple bounding box if GIS fails
                                   (and (<= min-lng (:lng pt) max-lng)
                                        (<= min-lat (:lat pt) max-lat)))))
                             candidates)]

    (take num-points valid-points)))

(defn collect-verified-images-for-city
  "Collect and verify images using poly-contains? with multi-city filtering"
  [city-key target-count]
  (println (str "🔍 Collecting " target-count " verified images for " (name city-key) "..."))

  (let [city-data (get corrected-cities city-key)]

    (if-not city-data
      {:error (str "City not found: " city-key)}

      (let [search-points (generate-city-search-points city-data 20)
            collected-images (atom [])
            attempts (atom 0)
            max-attempts (* target-count 3)]

        (println (str "  Generated " (count search-points) " search points within " (name city-key)))

        ;; Clean approach: poly-contains? + multi-city filtering
        (letfn [(point-in-city? [city-key lat lng]
                  "Test if point is within city using poly-contains?"
                  (when-let [city-data (get corrected-cities city-key)]
                    (when-let [boundary (:boundary city-data)]
                      (let [coords-flat (mapcat (fn [[lng lat]] [lat lng]) boundary)
                            poly-format [[coords-flat]]]
                        (poly/poly-contains? lat lng poly-format)))))

                (find-all-containing-cities [lat lng]
                  "Find ALL cities that contain this point"
                  (->> corrected-cities
                       (filter (fn [[city-key _]]
                                 (point-in-city? city-key lat lng)))
                       (map (fn [[city-key city-data]]
                              {:key city-key :name (:name city-data)}))))

                (classify-image-location [lat lng target-city-key]
                  "Accept only if point is in exactly one city (the target city)"
                  (let [containing-cities (find-all-containing-cities lat lng)]
                    (cond
                      ;; Perfect case: point is in exactly one city and it's our target
                      (and (= (count containing-cities) 1)
                           (= (:key (first containing-cities)) target-city-key))
                      {:correct? true
                       :confidence :high
                       :method "poly-contains-unique"
                       :verified-city (:name (first containing-cities))
                       :ambiguity :none}

                      ;; Ambiguous case: point is in multiple cities - reject
                      (> (count containing-cities) 1)
                      {:correct? false
                       :confidence :none
                       :method "poly-contains-ambiguous"
                       :reason "multiple-cities"
                       :ambiguity (mapv :name containing-cities)}

                      ;; Point is in exactly one city but not our target - reject
                      (= (count containing-cities) 1)
                      {:correct? false
                       :confidence :none
                       :method "poly-contains-wrong-city"
                       :reason "different-city"
                       :actual-city (:name (first containing-cities))}

                      ;; Point is not in any city - reject
                      :else
                      {:correct? false
                       :confidence :none
                       :method "poly-contains-outside"
                       :reason "outside-boundaries"})))]

          ;; Collect images from multiple points until we have enough
          (while (and (< (count @collected-images) target-count)
                      (< @attempts max-attempts)
                      (seq search-points))

            (let [search-pt (rand-nth search-points)
                  api-result (fetcher/fetch-from-multiple-sources search-pt 300)]

              (swap! attempts inc)
              (print ".")

              (when (:success api-result)
                (doseq [img (:images api-result)]
                  (when (< (count @collected-images) target-count)
                    (let [classification (classify-image-location (:lat img) (:lng img) city-key)]

                      (when (:correct? classification)
                        (let [metadata (generate-image-metadata img
                                                                {:gis-verified true
                                                                 :verification-accurate true
                                                                 :classification classification
                                                                 :actual-city (:verified-city classification)}
                                                                city-key)]
                          (swap! collected-images conj {:image img
                                                        :metadata metadata
                                                        :verification classification}))))))))))

        (println (str "\n  ✅ Collected " (count @collected-images) " unambiguous poly-contains images for " (name city-key)))

        {:success true
         :city city-key
         :collected-count (count @collected-images)
         :target-count target-count
         :attempts @attempts
         :images @collected-images
         :method "poly-contains-filtered"}))))

(defn collect-all-missing-cities
  "Collect images for all cities that currently have insufficient coverage"
  [target-per-city]
  (println "🚀 Starting comprehensive image collection for all cities")
  (println "========================================================")

  (let [cities-to-collect [:ramat-gan :givatayim :bat-yam :holon :bnei-brak]
        results (atom {})]

    (doseq [city-key cities-to-collect]
      (println (str "\n📍 Processing " (name city-key) "..."))
      (let [result (collect-verified-images-for-city city-key target-per-city)]
        (swap! results assoc city-key result)
        (Thread/sleep 2000))) ; Be respectful to APIs

    (println "\n📊 COLLECTION SUMMARY:")
    (println "======================")
    (doseq [[city-key result] @results]
      (if (:success result)
        (println (str "✅ " (name city-key) ": "
                      (:collected-count result) "/" (:target-count result)
                      " images (" (:attempts result) " API attempts)"))
        (println (str "❌ " (name city-key) ": " (:error result)))))

    @results))

(defn download-and-save-image
  "Download an image from URL and save to disk with metadata"
  [image-data metadata base-dir city-key]
  (try
    (let [city-dir (str base-dir "/verified-collection/" (name city-key))
          img-filename (str (:id (:image image-data)) ".jpg")
          metadata-filename (str (:id (:image image-data)) ".edn")
          img-path (str city-dir "/" img-filename)
          metadata-path (str city-dir "/" metadata-filename)]

      ;; Ensure directory exists
      (io/make-parents img-path)

      ;; Download and save image
      (with-open [in (io/input-stream (:url (:image image-data)))
                  out (io/output-stream img-path)]
        (io/copy in out))

      ;; Save metadata
      (spit metadata-path (pr-str metadata))

      {:success true
       :image-path img-path
       :metadata-path metadata-path})
    (catch Exception e
      {:error (.getMessage e)})))

(defn save-collected-images
  "Save all collected images to disk with proper directory structure"
  [collection-results base-dir]
  (println "💾 Saving collected images to disk...")
  (let [saved-count (atom 0)
        error-count (atom 0)]

    (doseq [[city-key result] collection-results]
      (when (:success result)
        (println (str "  Saving " (count (:images result)) " images for " (name city-key) "..."))
        (doseq [img-data (:images result)]
          (let [save-result (download-and-save-image img-data
                                                     (:metadata img-data)
                                                     base-dir
                                                     city-key)]
            (if (:success save-result)
              (swap! saved-count inc)
              (do
                (swap! error-count inc)
                (println (str "    ❌ Failed to save image: " (:error save-result)))))))))

    (println (str "💾 Saved " @saved-count " images successfully, " @error-count " errors"))
    {:saved @saved-count :errors @error-count}))

(defn run-complete-image-collection
  "Run the complete image collection and verification process"
  ([target-per-city] (run-complete-image-collection target-per-city "resources/public/images"))
  ([target-per-city base-dir]
   (println "🎯 COMPREHENSIVE STREET VIEW IMAGE COLLECTION")
   (println "=============================================")
   (println (str "Target: " target-per-city " verified images per city"))
   (println (str "Output: " base-dir "/verified-collection/"))
   (println)

   ;; Step 1: Collect all images
   (let [results (collect-all-missing-cities target-per-city)]

     ;; Step 2: Save images to disk
     (println "\n💾 SAVING IMAGES TO DISK")
     (println "========================")
     (let [save-results (save-collected-images results base-dir)]

       ;; Step 3: Generate summary
       (println "\n🎉 COLLECTION COMPLETE!")
       (println "========================")
       (let [total-collected (reduce + (map (fn [[_ result]]
                                              (if (:success result)
                                                (:collected-count result) 0))
                                            results))]
         (println (str "Total images collected: " total-collected))
         (println (str "Total images saved to disk: " (:saved save-results)))
         (println (str "Target was: " (* (count results) target-per-city)))
         (println (str "Collection success rate: " (int (* 100 (/ total-collected
                                                                  (* (count results) target-per-city)))) "%"))
         (println (str "Save success rate: " (int (* 100 (/ (:saved save-results) total-collected))) "%")))

       (merge results {:save-summary save-results})))))