(ns border-blur.images.selector
  (:require [border-blur.images.fetcher :as fetcher]
            [border-blur.gis.boundaries :as boundaries]
            [border-blur.gis.cities :as cities]
            [geo.jts :as jts]
            [geo.spatial :as spatial]))

;; Cache for border points and images
(def border-cache (atom {}))
(def image-cache (atom {}))

(defn cache-key [city-a-key city-b-key]
  "Generate cache key for city pair"
  (let [sorted (sort [city-a-key city-b-key])]
    (str (first sorted) "-" (second sorted))))

(defn get-cached-border-points [city-a-key city-b-key]
  "Retrieve cached border points for city pair"
  (get @border-cache (cache-key city-a-key city-b-key)))

(defn cache-border-points! [city-a-key city-b-key points]
  "Cache border points for reuse"
  (swap! border-cache assoc (cache-key city-a-key city-b-key) points))

(defn select-diverse-images
  "Select images that are visually diverse for better gameplay"
  [images num-images]
  ;; For now, random selection. Later: add visual similarity scoring
  (take num-images (shuffle images)))

(defn create-fallback-image-pair
  "Create realistic placeholder image pair when real images can't be fetched"
  [city-a city-b difficulty-level answer-type]
  (let [city-a-name (or (:name city-a) (name city-a))
        city-b-name (or (:name city-b) (name city-b))
        ;; Different image styles based on difficulty
        style-a (case difficulty-level
                  :easy "4A90E2/FFFFFF" ; Blue background
                  :medium "F39C12/FFFFFF" ; Orange background  
                  :hard "E74C3C/FFFFFF") ; Red background
        style-b (if (= answer-type :same)
                  style-a ; Same color for same city
                  "2ECC71/FFFFFF")] ; Green for different city
    {:left {:url (str "https://via.placeholder.com/400x300/" style-a "?text="
                      (java.net.URLEncoder/encode (str city-a-name " Street View") "UTF-8"))
            :city city-a-name
            :coords (or (:center city-a) [0 0])}
     :right {:url (str "https://via.placeholder.com/400x300/" style-b "?text="
                       (java.net.URLEncoder/encode (str city-b-name " Street View") "UTF-8"))
             :city city-b-name
             :coords (or (:center city-b) [0 0])}
     :correct-answer answer-type
     :difficulty difficulty-level
     :fallback true}))

(defn prepare-image-pair
  "Prepare an image pair for game presentation"
  [city-a city-b difficulty-level]
  (let [city-a-data (cities/get-city cities/cities city-a)
        city-b-data (cities/get-city cities/cities city-b)]

    (if (and city-a-data city-b-data)
      ;; Try to create real image pair, fallback to placeholder
      (try
        ;; For now, just use fallback since real API integration needs more work
        (create-fallback-image-pair city-a-data city-b-data difficulty-level :different)
        (catch Exception e
          (println "Error creating image pair:" (.getMessage e))
          (create-fallback-image-pair city-a-data city-b-data difficulty-level :different)))

      ;; Cities not found, create basic fallback
      (create-fallback-image-pair
       {:name (name city-a) :center [0 0]}
       {:name (name city-b) :center [0 0]}
       difficulty-level :different))))

(defn prepare-same-city-pair
  "Prepare two images from the same city"
  [city difficulty-level]
  (let [city-data (cities/get-city cities/cities city)]
    (if city-data
      (try
        ;; Create two different views of the same city
        {:left {:url (str "https://via.placeholder.com/400x300/4A90E2/FFFFFF?text="
                          (java.net.URLEncoder/encode (str (:name city-data) " Area A") "UTF-8"))
                :city (:name city-data)
                :coords (or (:center city-data) [0 0])}
         :right {:url (str "https://via.placeholder.com/400x300/4A90E2/FFFFFF?text="
                           (java.net.URLEncoder/encode (str (:name city-data) " Area B") "UTF-8"))
                 :city (:name city-data)
                 :coords (or (:center city-data) [0 0])}
         :correct-answer :same
         :difficulty difficulty-level
         :fallback true}
        (catch Exception e
          (println "Error creating same-city pair:" (.getMessage e))
          (create-fallback-image-pair city-data city-data difficulty-level :same)))

      ;; City not found, create basic fallback
      (let [city-name (name city)]
        {:left {:url (str "https://via.placeholder.com/400x300/4A90E2/FFFFFF?text="
                          (java.net.URLEncoder/encode (str city-name " A") "UTF-8"))
                :city city-name
                :coords [0 0]}
         :right {:url (str "https://via.placeholder.com/400x300/4A90E2/FFFFFF?text="
                           (java.net.URLEncoder/encode (str city-name " B") "UTF-8"))
                 :city city-name
                 :coords [0 0]}
         :correct-answer :same
         :difficulty difficulty-level
         :fallback true}))))

(defn generate-smart-image-pair
  "Generate an image pair based on game state and difficulty"
  [user-city current-stage total-stages]
  (let [;; Progressive difficulty
        difficulty (cond
                     (< current-stage 5) :easy
                     (< current-stage 15) :medium
                     :else :hard)

        ;; Mix of same/different (60% different, 40% same for variety)
        should-be-different? (< (rand) 0.6)

        ;; Prioritize user's city early on
        use-user-city? (and user-city (< current-stage 10) (< (rand) 0.4))

        available-cities (keys cities/cities)
        city-pairs (cities/find-city-pairs cities/cities)]

    (if should-be-different?
      ;; Different cities
      (let [pair (if use-user-city?
                  ;; Find a pair involving user's city
                   (first (filter #(some #{(keyword user-city)} %) city-pairs))
                  ;; Random pair
                   (rand-nth city-pairs))]
        (when pair
          (prepare-image-pair (first pair) (second pair) difficulty)))

      ;; Same city
      (let [city (if use-user-city?
                   (keyword user-city)
                   (rand-nth available-cities))]
        (prepare-same-city-pair city difficulty)))))

(defn preload-images-for-session
  "Preload images for smoother gameplay"
  [user-city num-stages]
  ;; Generate multiple image pairs in advance
  (let [pairs (repeatedly num-stages
                          #(generate-smart-image-pair user-city
                                                      (rand-int 20)
                                                      20))]
    (swap! image-cache assoc user-city pairs)
    pairs))

(defn get-next-image-pair
  "Get the next image pair for the game"
  [session]
  (let [user-city (:user-city session)
        current-stage (:current-stage session)
        total-stages (:total-stages session)

        ;; Check if we have preloaded images
        cached-pairs (get @image-cache user-city)]

    (if (and cached-pairs (< current-stage (count cached-pairs)))
      ;; Use preloaded
      (nth cached-pairs (dec current-stage))
      ;; Generate on demand
      (generate-smart-image-pair user-city current-stage total-stages))))

;; ===== NEW TEL AVIV SINGLE IMAGE FUNCTIONS =====

(defn create-fallback-tel-aviv-image
  "Create realistic placeholder image for Tel Aviv binary game"
  [city-name is-in-tel-aviv difficulty-level coords]
  (let [;; Different colors based on difficulty
        style (case difficulty-level
                :easy (if is-in-tel-aviv "4A90E2/FFFFFF" "2ECC71/FFFFFF") ; Blue for Tel Aviv, green for others  
                :medium (if is-in-tel-aviv "F39C12/FFFFFF" "9B59B6/FFFFFF") ; Orange for Tel Aviv, purple for others
                :hard "E74C3C/FFFFFF") ; Red for both (harder to distinguish)
        location-hint (if is-in-tel-aviv "Tel Aviv District" "Near Tel Aviv")]
    {:url (str "https://via.placeholder.com/600x400/" style "?text="
               (java.net.URLEncoder/encode (str city-name " - " location-hint) "UTF-8"))
     :city city-name
     :coords (or coords [34.7818 32.0853]) ; Default Tel Aviv coords
     :is-in-tel-aviv is-in-tel-aviv
     :difficulty difficulty-level
     :location-name location-hint
     :fallback true}))

(defn select-tel-aviv-image [difficulty]
  "Select a Tel Aviv image based on difficulty"
  (let [tel-aviv (cities/get-city cities/cities :tel-aviv)
        coords (or (:center tel-aviv) [34.7818 32.0853])
        location-name (case difficulty
                        :easy "Central Tel Aviv"
                        :medium "Tel Aviv Neighborhood"
                        :hard "Tel Aviv Border Area")]
    (create-fallback-tel-aviv-image location-name true difficulty coords)))

(defn select-neighbor-image [difficulty]
  "Select a non-Tel Aviv image from neighboring cities"
  (let [neighbors [:ramat-gan :holon :givatayim :bnei-brak :petah-tikva]
        neighbor-key (rand-nth neighbors)
        neighbor (cities/get-city cities/cities neighbor-key)
        city-name (or (:name neighbor) (name neighbor-key))
        coords (or (:center neighbor) [34.8 32.1])
        location-name (case difficulty
                        :easy (str "Central " city-name)
                        :medium (str city-name " Area")
                        :hard (str city-name " Border Area"))]
    (create-fallback-tel-aviv-image location-name false difficulty coords)))

;; ===== REAL IMAGE COLLECTION FUNCTIONS =====

(def tel-aviv-images
  "Real Tel Aviv street-view images with coordinates from filenames"
  ["/images/border-collection/tel-aviv/32.04884_34.77505_ta-south-1_173677.jpg"
   "/images/border-collection/tel-aviv/32.04887_34.77496_ta-south-1_794647.jpg"
   "/images/border-collection/tel-aviv/32.04889_34.77455_ta-south-1_951838.jpg"
   "/images/border-collection/tel-aviv/32.05990_34.81638_ta-south-border_280449.jpg"
   "/images/border-collection/tel-aviv/32.06083_34.81634_ta-south-border_229366.jpg"
   "/images/border-collection/tel-aviv/32.06123_34.81631_ta-south-border_793498.jpg"
   "/images/border-collection/tel-aviv/32.06478_34.81969_ta-southeast_341487.jpg"
   "/images/border-collection/tel-aviv/32.06494_34.82141_ta-southeast_194334.jpg"
   "/images/border-collection/tel-aviv/32.06598_34.82098_ta-southeast_799966.jpg"
   "/images/border-collection/tel-aviv/32.06679_34.82129_ayalon-west_570540.jpg"
   "/images/border-collection/tel-aviv/32.06686_34.82356_ta-border-south_115366.jpg"
   "/images/border-collection/tel-aviv/32.06695_34.82289_ta-border-south_113269.jpg"
   "/images/border-collection/tel-aviv/32.06756_34.82175_ayalon-west_395016.jpg"
   "/images/border-collection/tel-aviv/32.06769_34.82157_ta-border-south_802044.jpg"
   "/images/border-collection/tel-aviv/32.06826_34.82103_ayalon-west_939366.jpg"
   "/images/border-collection/tel-aviv/32.07009_34.80658_ta-east-center_304905.jpg"
   "/images/border-collection/tel-aviv/32.07030_34.80437_ta-east-center_826532.jpg"
   "/images/border-collection/tel-aviv/32.07033_34.80358_ta-east-center_302601.jpg"])

(def neighbor-images
  "Real neighbor city images (Ramat Gan, Givatayim, Bnei Brak)"
  {:ramat-gan
   ["/images/border-collection/ramat-gan/32.06857_34.82639_rg-border-west_119347.jpg"
    "/images/border-collection/ramat-gan/32.06859_34.82445_rg-border-west_134799.jpg"
    "/images/border-collection/ramat-gan/32.06865_34.82444_rg-border-west_153737.jpg"
    "/images/border-collection/ramat-gan/32.06890_34.82740_diamond-exchange_323240.jpg"
    "/images/border-collection/ramat-gan/32.06899_34.82652_diamond-exchange_124245.jpg"
    "/images/border-collection/ramat-gan/32.06901_34.82624_diamond-exchange_510717.jpg"
    "/images/manual-testing/ramat-gan/rg-center_01_score93_321621.jpg"
    "/images/manual-testing/ramat-gan/rg-center_02_score93_115529.jpg"
    "/images/manual-testing/ramat-gan/rg-center_03_score93_651367.jpg"
    "/images/manual-testing/ramat-gan/rg-center_04_score93_557252.jpg"]

   :givatayim
   ["/images/border-collection/givatayim/32.06752_34.80918_cemetery-area_164869.jpg"
    "/images/border-collection/givatayim/32.06826_34.80846_cemetery-area_492767.jpg"
    "/images/border-collection/givatayim/32.06895_34.81236_givatayim-west_116394.jpg"
    "/images/border-collection/givatayim/32.06913_34.81237_givatayim-west_547412.jpg"
    "/images/border-collection/givatayim/32.07437_34.81244_givatayim-north_472857.jpg"
    "/images/border-collection/givatayim/32.07445_34.81207_givatayim-north_293848.jpg"
    "/images/manual-testing/givatayim/gv-center_01_score89_829236.jpg"
    "/images/manual-testing/givatayim/gv-center_02_score89_167375.jpg"]

   :bnei-brak
   ["/images/manual-testing/bnei-brak/bb-center_01_score97_349698.jpg"
    "/images/manual-testing/bnei-brak/bb-center_02_score97_118183.jpg"
    "/images/manual-testing/bnei-brak/bb-center_03_score97_165816.jpg"
    "/images/manual-testing/bnei-brak/bb-center_04_score97_379965.jpg"
    "/images/manual-testing/bnei-brak/bb-center_05_score97_794855.jpg"]})

(defn parse-coords-from-filename [filename]
  "Extract coordinates from filename pattern: lat_lng_location_id.jpg"
  (when-let [match (re-find #"(\d+\.\d+)_(\d+\.\d+)_" filename)]
    [(Double/parseDouble (nth match 2)) ; lng first for [lng, lat] format
     (Double/parseDouble (nth match 1))]))

(defn parse-location-from-filename [filename]
  "Extract location description from filename"
  (cond
    (re-find #"ta-south|ta-east|ta-border|ayalon" filename) "Tel Aviv"
    (re-find #"diamond-exchange|rg-" filename) "Ramat Gan"
    (re-find #"givatayim|gv-|cemetery" filename) "Givatayim"
    (re-find #"bb-|bnei-brak" filename) "Bnei Brak"
    :else "Unknown"))

(defn create-real-image [image-path is-in-tel-aviv-hint difficulty]
  "Create image data from real collected image with GIS verification"
  (let [parsed-coords (parse-coords-from-filename image-path)
        ;; For images without coords in filename, use city-appropriate defaults
        default-coords (cond
                         (re-find #"ramat-gan" image-path) [34.8131 32.0853] ; Ramat Gan center
                         (re-find #"givatayim" image-path) [34.8095 32.0720] ; Givatayim center  
                         (re-find #"bnei-brak" image-path) [34.8333 32.0807] ; Bnei Brak center
                         :else [34.7818 32.0853]) ; Tel Aviv center as fallback
        coords (or parsed-coords default-coords)
        original-location (parse-location-from-filename image-path)
        area-type (case difficulty
                    :easy "Center"
                    :medium "Neighborhood"
                    :hard "Border Area")
        ;; CRITICAL FIX: Verify actual location using GIS boundaries
        ;; Note: This is a simplified check using bounding box
        ;; For production, use proper point-in-polygon test
        [lon lat] coords
        actually-in-tel-aviv (and (<= 34.75 lon 34.82)
                                  (<= 32.05 lat 32.12))
        ;; FIX: Determine actual city based on coordinates
        actual-city (cond
                      actually-in-tel-aviv "Tel Aviv"
                      (and (> lon 34.82) (<= lat 32.12)) "Ramat Gan Area" ; East of Tel Aviv
                      (and (<= lon 34.75) (<= lat 32.05)) "Bat Yam Area" ; Southwest
                      (and (<= lon 34.80) (< lat 32.05)) "Holon Area" ; South
                      :else "Near Tel Aviv")]
    {:url image-path
     :city actual-city ; Use actual city based on coordinates
     :coords coords
     :is-in-tel-aviv actually-in-tel-aviv ; Use verified result, not folder hint
     :difficulty difficulty
     :location-name (str actual-city " " area-type)
     :fallback false
     :has-real-coords (some? parsed-coords) ; Track if coords are from filename or default
     :verification-note (when (not= is-in-tel-aviv-hint actually-in-tel-aviv)
                          (str "WARNING: Image was in "
                               (if is-in-tel-aviv-hint "tel-aviv" "neighbor")
                               " folder but is actually "
                               (if actually-in-tel-aviv "inside" "outside")
                               " Tel Aviv boundaries"))}))

(defn select-real-tel-aviv-image [difficulty]
  "Select a real Tel Aviv image"
  (let [image-path (rand-nth tel-aviv-images)]
    (create-real-image image-path true difficulty)))

(defn select-real-neighbor-image [difficulty]
  "Select a real neighbor city image"
  (let [city-key (rand-nth [:ramat-gan :givatayim :bnei-brak])
        city-images (get neighbor-images city-key)
        image-path (rand-nth city-images)]
    (create-real-image image-path false difficulty)))

(defn get-single-tel-aviv-image
  "Generate a single image for Tel Aviv binary choice game using real images"
  [{:keys [user-city current-stage total-stages]}]
  (let [;; Progressive difficulty  
        difficulty (cond
                     (< current-stage 8) :easy
                     (< current-stage 16) :medium
                     :else :hard)

        ;; Tel Aviv probability based on difficulty
        tel-aviv-probability (case difficulty
                               :easy 0.6 ; 60% Tel Aviv for clear cases
                               :medium 0.6 ; 60% Tel Aviv for suburban mix  
                               :hard 0.5) ; 50% Tel Aviv for boundary confusion

        is-tel-aviv? (< (rand) tel-aviv-probability)]

    ;; Try to use real images first, fallback to placeholders if needed
    (try
      (if is-tel-aviv?
        (select-real-tel-aviv-image difficulty)
        (select-real-neighbor-image difficulty))
      (catch Exception e
        (println "Error loading real image, using placeholder:" (.getMessage e))
        ;; Fallback to placeholder system
        (if is-tel-aviv?
          (select-tel-aviv-image difficulty)
          (select-neighbor-image difficulty))))))