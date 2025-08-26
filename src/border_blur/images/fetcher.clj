(ns border-blur.images.fetcher
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [geo.jts :as jts]
            [geo.spatial :as spatial]))

;; Image API configurations
(def api-configs
  {:mapillary {:base-url "https://graph.mapillary.com/images"
               :requires-key true
               :rate-limit 100} ; requests per minute
   :openstreetcam {:base-url "https://api.openstreetcam.org/2.0/photo"
                   :requires-key false
                   :rate-limit 60}
   :flickr {:base-url "https://api.flickr.com/services/rest"
            :requires-key true
            :rate-limit 300}})

(defn get-api-key [api-name]
  "Get API key from environment or config"
  (let [env-key (str (name api-name) "_API_KEY")
        config-key (keyword (str (name api-name) "-api-key"))]
    (or (System/getenv env-key)
        ;; Fallback to config file with proper key format
        (get-in (try
                  (read-string (slurp "resources/api-keys.edn"))
                  (catch Exception _ {}))
                [config-key]))))

(defmulti fetch-images-near
  "Fetch images near a given coordinate from different APIs"
  (fn [api-name coords radius-m] api-name))

(defmethod fetch-images-near :mapillary
  [_ {:keys [lng lat]} radius-m]
  (let [api-key (get-api-key :mapillary)]
    (if (not api-key)
      {:error "Mapillary API key not found"}
      (try
        (let [response (http/get
                        (str (:base-url (:mapillary api-configs)))
                        {:query-params {:access_token api-key
                                        :bbox (format "%.6f,%.6f,%.6f,%.6f"
                                                      (- lng 0.01) (- lat 0.01)
                                                      (+ lng 0.01) (+ lat 0.01))
                                        :limit 20
                                        :fields "id,geometry,captured_at,compass_angle,thumb_1024_url"}
                         :as :json})]
          {:success true
           :images (map (fn [img]
                          {:id (:id img)
                           :url (or (:thumb_1024_url img)
                                    (str "https://images.mapillary.com/" (:id img) "/thumb-1024.jpg"))
                           :lat (get-in img [:geometry :coordinates 1])
                           :lng (get-in img [:geometry :coordinates 0])
                           :captured-at (:captured_at img)
                           :compass-angle (:compass_angle img)
                           :source :mapillary})
                        (get-in response [:body :data]))})
        (catch Exception e
          {:error (.getMessage e)})))))

(defmethod fetch-images-near :openstreetcam
  [_ {:keys [lng lat]} radius-m]
  ;; OpenStreetCam doesn't require API key
  (try
    (let [response (http/get
                    (:base-url (:openstreetcam api-configs))
                    {:query-params {:lat lat
                                    :lng lng
                                    :radius (/ radius-m 1000.0) ; km
                                    :limit 20}
                     :as :json})]
      {:success true
       :images (map (fn [img]
                      {:id (:id img)
                       :url (:lth_url img) ; Large thumbnail
                       :lat (:lat img)
                       :lng (:lng img)
                       :captured-at (:shot_date img)
                       :compass-angle (:heading img)
                       :source :openstreetcam})
                    (get-in response [:body :result :data]))})
    (catch Exception e
      {:error (.getMessage e)})))

(defmethod fetch-images-near :flickr
  [_ {:keys [lng lat]} radius-m]
  (let [api-key (get-api-key :flickr)]
    (if (not api-key)
      {:error "Flickr API key not found"}
      (try
        (let [response (http/get
                        (:base-url (:flickr api-configs))
                        {:query-params {:method "flickr.photos.search"
                                        :api_key api-key
                                        :lat lat
                                        :lon lng
                                        :radius (/ radius-m 1000.0)
                                        :radius_units "km"
                                        :has_geo 1
                                        :geo_context 2 ; Outdoors
                                        :per_page 20
                                        :format "json"
                                        :nojsoncallback 1}
                         :as :json})]
          {:success true
           :images (map (fn [photo]
                          {:id (:id photo)
                           :url (format "https://live.staticflickr.com/%s/%s_%s_z.jpg"
                                        (:server photo) (:id photo) (:secret photo))
                           :lat (parse-double (:latitude photo))
                           :lng (parse-double (:longitude photo))
                           :title (:title photo)
                           :source :flickr})
                        (get-in response [:body :photos :photo]))})
        (catch Exception e
          {:error (.getMessage e)})))))

(defmethod fetch-images-near :default
  [api-name _ _]
  {:error (str "Unknown API: " api-name)})

(defn fetch-from-multiple-sources
  "Try to fetch images from multiple sources, fallback if one fails"
  [coords radius-m]
  (let [apis [:openstreetcam :mapillary :flickr] ; Order by preference
        results (map #(fetch-images-near % coords radius-m) apis)
        successful (filter #(and (:success %) (seq (:images %))) results)]
    (if (seq successful)
      {:success true
       :images (mapcat :images successful)
       :sources (map :source successful)}
      {:error "No images found from any source"
       :attempted apis})))

;; Helper function - should be in boundaries namespace but keeping here for simplicity
(defn generate-search-grid [center-lng center-lat radius-m grid-size]
  (let [meters-per-degree 111000
        degree-offset (/ radius-m meters-per-degree)
        step (/ (* 2 degree-offset) (dec grid-size))]
    (for [i (range grid-size)
          j (range grid-size)]
      {:lng (+ center-lng (- degree-offset) (* i step))
       :lat (+ center-lat (- degree-offset) (* j step))})))

(defn fetch-image-pair-for-border
  "Fetch two images from different sides of a city border"
  [border-point city-a-polygon city-b-polygon search-radius-m]
  (let [;; Find points clearly in each city
        search-points (generate-search-grid
                       (first (:coords border-point))
                       (second (:coords border-point))
                       search-radius-m
                       3)

        ;; Classify points by city
        points-by-city (group-by
                        (fn [pt]
                          (let [jts-pt (jts/point (:lng pt) (:lat pt))]
                            (cond
                              (spatial/intersects? city-a-polygon jts-pt) :city-a
                              (spatial/intersects? city-b-polygon jts-pt) :city-b
                              :else :neither)))
                        search-points)

        ;; Fetch images from each city
        city-a-images (when-let [pts (:city-a points-by-city)]
                        (fetch-from-multiple-sources (first pts) 200))
        city-b-images (when-let [pts (:city-b points-by-city)]
                        (fetch-from-multiple-sources (first pts) 200))]

    (if (and (:success city-a-images) (:success city-b-images)
             (seq (:images city-a-images)) (seq (:images city-b-images)))
      {:success true
       :city-a-image (first (:images city-a-images))
       :city-b-image (first (:images city-b-images))
       :border-point border-point}
      {:error "Could not find images on both sides of border"
       :city-a-found (boolean (seq (:images city-a-images)))
       :city-b-found (boolean (seq (:images city-b-images)))})))

;; Moving this function earlier to fix dependency order