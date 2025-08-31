(ns border-blur.boroughs.fetcher
  "Fetch NYC borough boundaries from OpenStreetMap"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(def overpass-url "https://overpass-api.de/api/interpreter")

(def borough-osm-ids
  "OSM relation IDs for NYC boroughs - verified from OpenStreetMap direct links"
  {:manhattan 2552485 ; New York County/Manhattan - verified
   :brooklyn 369518 ; Kings County/Brooklyn - verified working
   :queens 369519 ; Queens County/Queens - corrected from web search
   :bronx 2552450 ; Bronx County - verified from OSM
   :staten-island 962876}) ; Richmond County/Staten Island ; Richmond County/Staten Island - corrected ID

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
            ;; Build all disconnected boundary components and return the largest
            (letfn [(way-coordinates [way-id]
                      (when-let [way-nodes (get ways way-id)]
                        (map (fn [node-id]
                               (let [node (get nodes node-id)]
                                 [(:lon node) (:lat node)]))
                             way-nodes)))

                    (build-component [start-way remaining-ways]
                      (loop [result (vec (way-coordinates start-way))
                             remaining (disj remaining-ways start-way)]
                        (if (empty? remaining)
                          result
                          (let [last-point (last result)
                                first-point (first result)
                                ;; Find a way that connects to our current chain
                                next-way (first
                                          (filter (fn [way-id]
                                                    (when-let [coords (way-coordinates way-id)]
                                                      (let [way-first (first coords)
                                                            way-last (last coords)]
                                                        (or (= last-point way-first)
                                                            (= last-point way-last)
                                                            (= first-point way-first)
                                                            (= first-point way-last)))))
                                                  remaining))]
                            (if next-way
                              (let [next-coords (way-coordinates next-way)
                                    way-first (first next-coords)
                                    way-last (last next-coords)]
                                (cond
                                  ;; Connects at end, add in order (skip duplicate point)
                                  (= last-point way-first)
                                  (recur (into result (rest next-coords))
                                         (disj remaining next-way))

                                  ;; Connects at end, add in reverse (skip duplicate point)
                                  (= last-point way-last)
                                  (recur (into result (rest (reverse next-coords)))
                                         (disj remaining next-way))

                                  ;; Connects at beginning, prepend in reverse (skip duplicate)
                                  (= first-point way-last)
                                  (recur (into (vec (reverse (butlast next-coords))) result)
                                         (disj remaining next-way))

                                  ;; Connects at beginning, prepend in order (skip duplicate)
                                  (= first-point way-first)
                                  (recur (into (vec (butlast (reverse next-coords))) result)
                                         (disj remaining next-way))

                                  :else
                                  (recur result (disj remaining next-way))))
                              ;; No more connecting ways, return what we have
                              result)))))

                    (build-all-components [all-ways]
                      (loop [remaining (set all-ways)
                             components []]
                        (if (empty? remaining)
                          components
                          (let [start-way (first remaining)
                                component (build-component start-way remaining)
                                used-ways (set (filter (fn [way-id]
                                                         (some #(= % way-id)
                                                               (map (fn [coord]
                                                                      (some (fn [way-id2]
                                                                              (let [way-coords (way-coordinates way-id2)]
                                                                                (some #(= coord %) way-coords)))
                                                                            all-ways))
                                                                    component)))
                                                       remaining))]
                            (recur (apply disj remaining
                                          (filter (fn [way-id]
                                                    (let [coords (way-coordinates way-id)]
                                                      (some (fn [coord]
                                                              (some #(= coord %) component))
                                                            coords)))
                                                  remaining))
                                   (conj components component))))))]

              ;; Build all components and return the largest one
              (let [components (build-all-components outer-ways)
                    largest-component (->> components
                                           (sort-by count)
                                           last)]
                (println (format "  Found %d boundary components, using largest with %d points"
                                 (count components)
                                 (count largest-component)))
                largest-component))))))))

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