(ns border-blur.core
  "Main orchestrator for NYC street view image collection and classification"
  (:require [border-blur.boroughs.fetcher :as fetcher]
            [border-blur.boroughs.classifier :as classifier]
            [border-blur.images.collector :as collector]
            [border-blur.web.server :as server]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:gen-class))

(defn ensure-borough-data
  "Ensure we have borough boundary data"
  []
  (let [borough-file "resources/boroughs/nyc-boroughs.edn"]
    (if (.exists (io/file borough-file))
      (do
        (println "Loading existing borough data...")
        (classifier/load-borough-data))
      (do
        (println "Borough data not found. Fetching from OpenStreetMap...")
        (fetcher/fetch-and-save-boroughs)))))

(defn collect-and-classify
  "Main collection and classification pipeline"
  [& {:keys [grid-size max-images-per-point output-file]
      :or {grid-size 15
           max-images-per-point 3
           output-file "data/classified-images.json"}}]

  (println "\n=== NYC Street View Collection System ===\n")

  ;; Step 1: Ensure we have borough boundaries
  (println "Step 1: Loading borough boundaries")
  (let [boroughs (ensure-borough-data)]
    (if (empty? boroughs)
      (println "ERROR: Could not load borough data")
      (do
        (println (format "✓ Loaded %d boroughs" (count boroughs)))

        ;; Step 2: Collect images
        (println "\nStep 2: Collecting street view images")
        (let [images (collector/collect-nyc-images
                      :grid-size grid-size
                      :max-images-per-point max-images-per-point)]

          (if (empty? images)
            (println "ERROR: No images collected")
            (do
              (println (format "✓ Collected %d images" (count images)))

              ;; Step 3: Classify images by borough
              (println "\nStep 3: Classifying images by borough")
              (let [classified-images (if (seq boroughs)
                                        (classifier/classify-images images boroughs)
                                        images)
                    stats (if (seq classified-images)
                            (classifier/get-borough-stats classified-images)
                            [])]

                ;; Step 4: Save results
                (println "\nStep 4: Saving results")
                (io/make-parents output-file)
                (spit output-file (json/generate-string classified-images {:pretty true}))
                (println (format "✓ Saved to %s" output-file))

                ;; Display summary
                (println "\n=== Collection Summary ===")
                (println (format "Total images: %d" (count classified-images)))
                (println "\nDistribution by borough:")
                (doseq [{:keys [borough count high-confidence medium-confidence low-confidence]} stats]
                  (println (format "  %s: %d images (High: %d, Med: %d, Low: %d)"
                                   (name borough) count
                                   high-confidence medium-confidence low-confidence)))

                ;; Return the classified images
                classified-images))))))))

(defn run-test-collection
  "Run a small test collection to verify everything works"
  []
  (println "\n=== Running Test Collection ===")
  (println "This will collect a small sample to test the system\n")
  (collect-and-classify
   :grid-size 5 ; 5x5 grid = 25 points
   :max-images-per-point 2 ; Max 2 images per point
   :output-file "data/test-collection.json"))

(defn -main
  "Main entry point"
  [& args]
  (let [command (first args)]
    (case command
      "test" (run-test-collection)
      "collect" (if-let [grid-size (second args)]
                  (collect-and-classify :grid-size (Integer/parseInt grid-size))
                  (collect-and-classify))
      "fetch-boroughs" (fetcher/fetch-and-save-boroughs)
      "web" (server/start-server! :port (if-let [port (second args)]
                                          (Integer/parseInt port)
                                          3000))
      (do
        (println "NYC Street View Collection System")
        (println "\nUsage:")
        (println "  clj -M:run test              # Run test collection (small sample)")
        (println "  clj -M:run collect [size]    # Full collection with optional grid size")
        (println "  clj -M:run fetch-boroughs    # Fetch borough boundaries only")
        (println "  clj -M:run web [port]        # Start web visualization server")
        (println "\nExample:")
        (println "  clj -M:run collect 20        # 20x20 grid across NYC")
        (println "  clj -M:run web 8080          # Start web server on port 8080")))))