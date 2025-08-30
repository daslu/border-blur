(ns run-collection
  "Simple script to run the NYC street view collection"
  (:require [border-blur.images.collector :as collector]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn -main []
  (println "\n=== NYC Street View Collection ===\n")
  (println "Starting uniform grid collection across NYC...")
  
  ;; Collect images with a small test grid
  (let [images (collector/collect-nyc-images 
                :grid-size 10     ; 10x10 grid = 100 points
                :max-images-per-point 2)]
    
    (println (format "\nCollection complete: %d images collected" (count images)))
    
    ;; Save the raw images
    (when (seq images)
      (io/make-parents "data/nyc-images.json")
      (spit "data/nyc-images.json" 
            (json/generate-string images {:pretty true}))
      (println (format "Saved to data/nyc-images.json"))
      
      ;; Show sample
      (println "\nSample images collected:")
      (doseq [img (take 3 images)]
        (println (format "  - ID: %s, Location: (%.6f, %.6f)" 
                        (:id img) (:lat img) (:lng img)))))))

(-main)