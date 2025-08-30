(ns border-blur.boroughs.fetcher
  "Fetch NYC borough boundaries from OpenStreetMap"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(def overpass-url "https://overpass-api.de/api/interpreter")

(def borough-osm-ids
  "OSM relation IDs for NYC boroughs - using correct IDs"
  {:manhattan 2552485 ; New York County/Manhattan
   :brooklyn 369518 ; Kings County/Brooklyn - corrected ID
   :queens 2552484 ; Queens County/Queens
   :bronx 2552486 ; Bronx County/The Bronx
   :staten-island 369519}) ; Richmond County/Staten Island ; Richmond County/Staten Island - corrected ID

(defn fetch-borough-boundary
  "Fetch a single borough boundary from OSM"
  [borough-name osm-id]
  (println (str "Fetching boundary for " (name borough-name) "..."))
  (let [query (format "[out:json];relation(%d);(._;>;);out;" osm-id)
        response (http/post overpass-url
                            {:form-params {:data query}
                             :as :json
                             :socket-timeout 30000
                             :conn-timeout 30000})]
    (when (= 200 (:status response))
      (let [elements (get-in response [:body :elements])
            nodes (->> elements
                       (filter #(= "node" (:type %)))
                       (map (fn [node] [(:id node) {:lat (:lat node)
                                                    :lon (:lon node)}]))
                       (into {}))
            ways (->> elements
                      (filter #(= "way" (:type %)))
                      (map (fn [way] [(:id way) (:nodes way)]))
                      (into {}))
            relation (->> elements
                          (filter #(= "relation" (:type %)))
                          first)]

        ;; Extract outer boundary
        (when relation
          (let [outer-ways (->> (:members relation)
                                (filter #(= "outer" (:role %)))
                                (filter #(= "way" (:type %)))
                                (map :ref))]
            ;; Combine way segments into continuous boundary
            (loop [remaining outer-ways
                   current-boundary []
                   used-ways #{}]
              (if (empty? remaining)
                current-boundary
                (let [way-id (first remaining)
                      way-nodes (get ways way-id)]
                  (if way-nodes
                    (let [coords (map (fn [node-id]
                                        (let [node (get nodes node-id)]
                                          [(:lon node) (:lat node)]))
                                      way-nodes)]
                      (recur (rest remaining)
                             (into current-boundary coords)
                             (conj used-ways way-id)))
                    (recur (rest remaining) current-boundary used-ways)))))))))))

(defn simplify-boundary
  "Simplify boundary by sampling every nth point"
  [boundary n]
  (let [sampled (take-nth n boundary)]
    ;; Ensure polygon is closed
    (if (= (first sampled) (last sampled))
      sampled
      (conj (vec sampled) (first sampled)))))

(defn fetch-all-boroughs
  "Fetch all NYC borough boundaries"
  []
  (reduce (fn [acc [borough-key osm-id]]
            (if-let [boundary (fetch-borough-boundary borough-key osm-id)]
              (do
                (println (str "✓ " (name borough-key) ": " (count boundary) " points"))
                (assoc acc borough-key
                       {:name (name borough-key)
                        :full-boundary boundary
                        :simplified-boundary (simplify-boundary boundary 10)}))
              (do
                (println (str "✗ Failed to fetch " (name borough-key)))
                acc)))
          {}
          borough-osm-ids))

(defn save-borough-data
  "Save borough data to EDN file"
  [borough-data filename]
  (io/make-parents filename)
  (spit filename (pr-str borough-data))
  (println (str "Saved borough data to " filename)))

(defn fetch-and-save-boroughs
  "Main function to fetch and save all borough boundaries"
  []
  (println "Fetching NYC borough boundaries from OpenStreetMap...")
  (let [borough-data (fetch-all-boroughs)]
    (save-borough-data borough-data "resources/boroughs/nyc-boroughs.edn")
    (println "\nSummary:")
    (doseq [[k v] borough-data]
      (println (format "  %s: %d boundary points (simplified to %d)"
                       (name k)
                       (count (:full-boundary v))
                       (count (:simplified-boundary v)))))
    borough-data))