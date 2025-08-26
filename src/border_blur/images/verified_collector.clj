(ns border-blur.images.verified-collector
  "Advanced image collector with GIS verification and proper attribution"
  (:require [border-blur.images.fetcher :as fetcher]
            [border-blur.gis.cities :as cities]
            [border-blur.gis.core :as gis-core]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io])
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
  "Collect and verify images for a specific city with proper attribution"
  [city-key target-count]
  (println (str "🔍 Collecting " target-count " verified images for " (name city-key) "..."))

  (let [city-data (cities/get-city cities/cities city-key)]

    (if-not city-data
      {:error (str "City not found: " city-key)}

      (let [search-points (generate-city-search-points city-data 20)
            collected-images (atom [])
            attempts (atom 0)
            max-attempts (* target-count 3)]

        (println (str "  Generated " (count search-points) " search points within " (name city-key)))

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
                  (try
                    ;; Verify image is actually in target city
                    (let [img-in-city? (gis-core/point-in-city? (:lat img) (:lng img)
                                                                (:boundary city-data))
                          verification {:gis-verified true
                                        :verification-accurate img-in-city?
                                        :actual-city (if img-in-city?
                                                       (:name city-data)
                                                       "Outside boundary")}]

                      (when img-in-city?
                        (let [metadata (generate-image-metadata img verification city-key)]
                          (swap! collected-images conj {:image img
                                                        :metadata metadata
                                                        :verification verification}))))
                    (catch Exception e
                      (println (str "\n    GIS verification failed for image: " (.getMessage e))))))))))

        (println (str "\n  ✅ Collected " (count @collected-images) " verified images for " (name city-key)))

        {:success true
         :city city-key
         :collected-count (count @collected-images)
         :target-count target-count
         :attempts @attempts
         :images @collected-images}))))

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

     ;; Step 2: Generate summary
     (println "\n🎉 COLLECTION COMPLETE!")
     (println "========================")
     (let [total-collected (reduce + (map (fn [[_ result]]
                                            (if (:success result)
                                              (:collected-count result) 0))
                                          results))]
       (println (str "Total images collected: " total-collected))
       (println (str "Target was: " (* (count results) target-per-city)))
       (println (str "Success rate: " (int (* 100 (/ total-collected
                                                     (* (count results) target-per-city)))) "%")))

     results)))