(ns fetch-boundaries
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.pprint :as pp]))

(defn fetch-city-boundary [city-name]
  "Fetch real city boundary from OpenStreetMap using Overpass API"
  (let [url "https://overpass-api.de/api/interpreter"
        query (str "[out:json][timeout:25];"
                   "relation[\"name:en\"=\"" city-name "\"][\"admin_level\"=\"8\"];"
                   "out geom;")]

    (println (format "Fetching boundary for %s..." city-name))
    (try
      (let [response (http/post url
                                {:body query
                                 :headers {"Content-Type" "text/plain"}
                                 :socket-timeout 30000
                                 :connection-timeout 10000})]
        (if (= 200 (:status response))
          (let [data (json/read-str (:body response) :key-fn keyword)]
            (if (seq (:elements data))
              (do
                (println (format "  ✅ Found %d boundary elements" (count (:elements data))))
                data)
              (do
                (println "  ⚠️ No boundary found")
                nil)))
          (do
            (println (format "  ❌ HTTP status: %d" (:status response)))
            nil)))
      (catch Exception e
        (println (format "  ❌ Exception: %s" (.getMessage e)))
        nil))))

(defn extract-boundary-coordinates [boundary-data]
  "Extract coordinate polygon from Overpass response"
  (when-let [elements (:elements boundary-data)]
    (let [relation (first elements)]
      (when-let [members (:members relation)]
        ;; Extract outer ways (boundary segments)
        (let [outer-ways (filter #(= (:role %) "outer") members)
              coordinates (mapcat (fn [way]
                                    (when-let [geometry (:geometry way)]
                                      (map (fn [coord] [(:lon coord) (:lat coord)]) geometry)))
                                  outer-ways)]
          (when (seq coordinates)
            (vec coordinates)))))))

(defn test-tel-aviv []
  "Test fetching Tel Aviv boundary"
  (let [boundary (fetch-city-boundary "Tel Aviv")
        coords (extract-boundary-coordinates boundary)]
    (when coords
      (println (format "Extracted %d coordinate points" (count coords)))
      (println "First few coordinates:")
      (pp/pprint (take 5 coords)))
    coords))

;; Run the test
(test-tel-aviv)