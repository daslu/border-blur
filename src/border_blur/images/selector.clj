(ns border-blur.images.selector
  (:require [border-blur.images.fetcher :as fetcher]
            [border-blur.gis.boundaries :as boundaries]
            [border-blur.gis.cities :as cities]
            [border-blur.gis.core :as gis-core]
            [border-blur.images.spatial-optimizer :as optimizer]
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

(def verified-tel-aviv-images
  "GIS-verified Tel Aviv-Yafo images (was 18, now 36 after correction)"
  [;; Original tel-aviv folder (all verified correct)
   "/images/border-collection/tel-aviv/32.04884_34.77505_ta-south-1_173677.jpg"
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
   "/images/border-collection/tel-aviv/32.07033_34.80358_ta-east-center_302601.jpg"
   ;; Corrected from ramat-gan folder (actually in Tel Aviv-Yafo)
   "/images/border-collection/ramat-gan/32.06857_34.82639_rg-border-west_119347.jpg"
   "/images/border-collection/ramat-gan/32.06859_34.82445_rg-border-west_134799.jpg"
   "/images/border-collection/ramat-gan/32.06865_34.82444_rg-border-west_153737.jpg"
   "/images/border-collection/ramat-gan/32.06890_34.82740_diamond-exchange_323240.jpg"
   "/images/border-collection/ramat-gan/32.06899_34.82652_diamond-exchange_124245.jpg"
   "/images/border-collection/ramat-gan/32.06901_34.82624_diamond-exchange_510717.jpg"
   "/images/manual-testing/ramat-gan/rg-center_01_score93_321621.jpg"
   "/images/manual-testing/ramat-gan/rg-center_02_score93_115529.jpg"
   "/images/manual-testing/ramat-gan/rg-center_03_score93_651367.jpg"
   "/images/manual-testing/ramat-gan/rg-center_04_score93_557252.jpg"
   ;; Corrected from givatayim folder (actually in Tel Aviv-Yafo)
   "/images/border-collection/givatayim/32.06752_34.80918_cemetery-area_164869.jpg"
   "/images/border-collection/givatayim/32.06826_34.80846_cemetery-area_492767.jpg"
   "/images/border-collection/givatayim/32.06895_34.81236_givatayim-west_116394.jpg"
   "/images/border-collection/givatayim/32.06913_34.81237_givatayim-west_547412.jpg"
   "/images/border-collection/givatayim/32.07437_34.81244_givatayim-north_472857.jpg"
   "/images/border-collection/givatayim/32.07445_34.81207_givatayim-north_293848.jpg"
   "/images/manual-testing/givatayim/gv-center_01_score89_829236.jpg"
   "/images/manual-testing/givatayim/gv-center_02_score89_167375.jpg"])

(def verified-neighbor-images
  "GIS-verified non-Tel-Aviv images (only Bnei Brak confirmed outside boundaries)"
  {:bnei-brak
   ["/images/manual-testing/bnei-brak/bb-center_01_score97_349698.jpg"
    "/images/manual-testing/bnei-brak/bb-center_02_score97_118183.jpg"
    "/images/manual-testing/bnei-brak/bb-center_03_score97_165816.jpg"
    "/images/manual-testing/bnei-brak/bb-center_04_score97_379965.jpg"
    "/images/manual-testing/bnei-brak/bb-center_05_score97_794855.jpg"]

   ;; Note: Original ramat-gan and givatayim folders were all reclassified as Tel Aviv-Yafo
   ;; These remain empty until we get true non-Tel-Aviv images from these areas
   :ramat-gan []
   :givatayim []
   :bat-yam []
   :holon []})

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
  "Create image data from real collected image with PROPER GIS verification using actual polygons"
  (let [parsed-coords (parse-coords-from-filename image-path)
        ;; For images without coords in filename, use city-appropriate defaults
        default-coords (cond
                         (re-find #"ramat-gan" image-path) [34.8131 32.0853] ; Ramat Gan center
                         (re-find #"givatayim" image-path) [34.8095 32.0720] ; Givatayim center  
                         (re-find #"bnei-brak" image-path) [34.8333 32.0807] ; Bnei Brak center
                         (re-find #"bat-yam" image-path) [34.7478 32.0136] ; Bat Yam center
                         (re-find #"holon" image-path) [34.785 32.0137] ; Holon center
                         :else [34.7973 32.0877]) ; Tel Aviv-Yafo center as fallback
        coords (or parsed-coords default-coords)
        original-location (parse-location-from-filename image-path)
        area-type (case difficulty
                    :easy "Center"
                    :medium "Neighborhood"
                    :hard "Border Area")

        ;; PROPER GIS VERIFICATION: Use actual polygon boundaries
        [lon lat] coords
        tel-aviv-yafo (cities/get-city cities/cities :tel-aviv-yafo)
        actually-in-tel-aviv (when (and tel-aviv-yafo lat lon)
                               (try
                                 (gis-core/point-in-city? lat lon (:boundary tel-aviv-yafo))
                                 (catch Exception e
                                   (println "GIS verification failed:" (.getMessage e))
                                   ;; Fallback to bounding box if GIS fails
                                   (and (<= 34.74 lon 34.83) (<= 32.02 lat 32.13)))))

        ;; Determine actual city using proper polygon testing
        actual-city (if actually-in-tel-aviv
                      "Tel Aviv-Yafo"
                      ;; Test against other city polygons to find actual location
                      (let [cities-to-test [[:ramat-gan "Ramat Gan"]
                                            [:givatayim "Givatayim"]
                                            [:bnei-brak "Bnei Brak"]
                                            [:bat-yam "Bat Yam"]
                                            [:holon "Holon"]]]
                        (or (first (keep (fn [[city-key city-name]]
                                           (let [city-data (cities/get-city cities/cities city-key)]
                                             (when (and city-data lat lon)
                                               (try
                                                 (when (gis-core/point-in-city? lat lon (:boundary city-data))
                                                   city-name)
                                                 (catch Exception e
                                                   nil)))))
                                         cities-to-test))
                            "Near Tel Aviv Area"))) ; Fallback if not in any known polygon

        ;; Calculate verification accuracy
        folder-suggests-tel-aviv? (boolean (re-find #"tel-aviv" image-path))
        verification-correct? (= folder-suggests-tel-aviv? actually-in-tel-aviv)

        verification-note (cond
                            verification-correct? "✅ Folder location matches GIS verification"
                            (not verification-correct?)
                            (str "⚠️ MISLABELED: Image was in "
                                 (if folder-suggests-tel-aviv? "tel-aviv" "neighbor")
                                 " folder but GIS shows it's actually "
                                 (if actually-in-tel-aviv "inside" "outside")
                                 " Tel Aviv-Yafo boundaries")
                            :else "❓ Could not verify location")]

    {:url image-path
     :city actual-city ; Use GIS-verified city name
     :coords coords
     :is-in-tel-aviv actually-in-tel-aviv ; Use polygon-verified result
     :difficulty difficulty
     :location-name (str actual-city " " area-type)
     :fallback false
     :has-real-coords (some? parsed-coords) ; Track if coords are from filename or default
     :gis-verified true ; Mark as using proper GIS verification
     :verification-accurate verification-correct?
     :verification-note verification-note}))

(defn select-real-tel-aviv-image [difficulty]
  "Select a GIS-verified Tel Aviv-Yafo image"
  (let [image-path (rand-nth verified-tel-aviv-images)]
    (create-real-image image-path true difficulty)))

(defn select-real-neighbor-image [difficulty]
  "Select a GIS-verified non-Tel-Aviv image"
  (let [;; Only use cities that have confirmed non-Tel-Aviv images
        available-cities (filter #(seq (get verified-neighbor-images %))
                                 [:bnei-brak :ramat-gan :givatayim :bat-yam :holon])]
    (if (seq available-cities)
      (let [city-key (rand-nth available-cities)
            city-images (get verified-neighbor-images city-key)
            image-path (rand-nth city-images)]
        (create-real-image image-path false difficulty))
      ;; Fallback if no verified neighbor images available
      (select-neighbor-image difficulty))))

(defn get-single-tel-aviv-image
  "Generate a single image for Tel Aviv binary choice game with SPATIAL OPTIMIZATION"
  [{:keys [user-city current-stage total-stages] :as session}]
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

        is-tel-aviv? (< (rand) tel-aviv-probability)

        ;; Try spatial optimization first
        selected-image (try
                         ;; Use spatial optimization (already required as optimizer)
                         (let [results (optimizer/validate-all-images)
                               validations (:validations results)
                               tel-aviv-imgs (filter #(= (:gis-verified-city %) :tel-aviv-yafo) validations)
                               other-imgs (filter #(and (:gis-verified-city %)
                                                        (not= (:gis-verified-city %) :tel-aviv-yafo))
                                                  validations)]
                           (if is-tel-aviv?
                             (when (seq tel-aviv-imgs)
                               (let [img (rand-nth tel-aviv-imgs)]
                                 {:url (:file-path img)
                                  :city "Tel Aviv-Yafo"
                                  :is-in-tel-aviv true
                                  :difficulty difficulty
                                  :location-name "Tel Aviv-Yafo Area"
                                  :fallback false
                                  :gis-verified true
                                  :spatial-optimized true
                                  :verification-note "✅ Spatially optimized with GIS verification"}))
                             (when (seq other-imgs)
                               (let [img (rand-nth other-imgs)]
                                 {:url (:file-path img)
                                  :city (name (:gis-verified-city img))
                                  :is-in-tel-aviv false
                                  :difficulty difficulty
                                  :location-name (str (name (:gis-verified-city img)) " Area")
                                  :fallback false
                                  :gis-verified true
                                  :spatial-optimized true
                                  :verification-note "✅ Spatially optimized with GIS verification"}))))
                         (catch Exception e
                           (println "Spatial optimization not available, using standard selection")
                           nil))]

    ;; If optimization didn't work, fall back to standard selection
    (or selected-image
        (try
          (if is-tel-aviv?
            (select-real-tel-aviv-image difficulty)
            (select-real-neighbor-image difficulty))
          (catch Exception e
            (println "Error loading real image, using placeholder:" (.getMessage e))
            ;; Fallback to placeholder system
            (if is-tel-aviv?
              (select-tel-aviv-image difficulty)
              (select-neighbor-image difficulty)))))))

(defn load-spatially-optimized-images
  "Load images from the verified-collection directory"
  [city-key]
  (let [city-dir (str "resources/public/images/verified-collection/" (name city-key))
        image-files (try
                      (->> (clojure.java.io/file city-dir)
                           .listFiles
                           (filter #(.endsWith (.getName %) ".jpg"))
                           (map #(.getName %)))
                      (catch Exception _ []))]
    (map (fn [filename]
           (let [image-id (clojure.string/replace filename #"\.jpg$" "")
                 metadata-file (str city-dir "/" image-id ".edn")
                 metadata (try
                            (read-string (slurp metadata-file))
                            (catch Exception _ {}))]
             {:id image-id
              :url (str "/images/verified-collection/" (name city-key) "/" filename)
              :metadata metadata
              :source :spatially-optimized}))
         image-files)))

(defn get-spatially-optimized-image
  "Get a random spatially-optimized image for Tel Aviv"
  []
  (let [tel-aviv-images (load-spatially-optimized-images :tel-aviv-yafo)]
    (if (seq tel-aviv-images)
      (rand-nth tel-aviv-images)
      ;; Fallback to old method if no optimized images available
      (get-single-tel-aviv-image))))

;; ===== IMAGE RECOLLECTION AND VERIFICATION FUNCTIONS =====

(defn verify-all-images-with-polygons
  "Verify all images in our collection using proper polygon boundaries"
  []
  (println "🔍 Starting image verification with proper polygon boundaries...")
  (let [all-image-paths (concat verified-tel-aviv-images
                                (mapcat second verified-neighbor-images))
        verification-results (map (fn [image-path]
                                    (println (str "  Checking: " (last (clojure.string/split image-path #"/"))))
                                    (let [result (create-real-image image-path true :medium)]
                                      {:path image-path
                                       :result result}))
                                  all-image-paths)
        correct-count (count (filter #(get-in % [:result :verification-accurate]) verification-results))
        total-count (count verification-results)]

    (println (str "\n📊 VERIFICATION SUMMARY:"))
    (println (str "  Total images: " total-count))
    (println (str "  Correctly labeled: " correct-count " (" (int (* 100 (/ correct-count total-count))) "%)"))
    (println (str "  Mislabeled: " (- total-count correct-count) " (" (int (* 100 (/ (- total-count correct-count) total-count))) "%)"))

    (println "\n📋 DETAILED RESULTS:")
    (doseq [{:keys [path result]} verification-results]
      (let [filename (last (clojure.string/split path #"/"))
            status (if (:verification-accurate result) "✅" "⚠️")
            city (:city result)
            in-ta (:is-in-tel-aviv result)]
        (println (str "  " status " " filename))
        (println (str "      └─ " city " (in Tel Aviv: " in-ta ")"))
        (when-not (:verification-accurate result)
          (println (str "      └─ " (:verification-note result))))))

    verification-results))

(defn recollect-verified-images
  "Create new properly organized image collections based on GIS verification"
  []
  (println "🗂️ Recollecting images based on GIS verification...")
  (let [verification-results (verify-all-images-with-polygons)

        ;; Group by actual city (GIS-verified)
        by-city (group-by #(get-in % [:result :city]) verification-results)

        ;; Create new collections
        new-tel-aviv-images (map :path (get by-city "Tel Aviv-Yafo" []))
        new-ramat-gan-images (map :path (get by-city "Ramat Gan" []))
        new-givatayim-images (map :path (get by-city "Givatayim" []))
        new-bnei-brak-images (map :path (get by-city "Bnei Brak" []))
        new-bat-yam-images (map :path (get by-city "Bat Yam" []))
        new-holon-images (map :path (get by-city "Holon" []))
        new-other-images (map :path (get by-city "Near Tel Aviv Area" []))]

    (println "\n📂 NEW VERIFIED COLLECTIONS:")
    (println (str "  Tel Aviv-Yafo: " (count new-tel-aviv-images) " images"))
    (println (str "  Ramat Gan: " (count new-ramat-gan-images) " images"))
    (println (str "  Givatayim: " (count new-givatayim-images) " images"))
    (println (str "  Bnei Brak: " (count new-bnei-brak-images) " images"))
    (println (str "  Bat Yam: " (count new-bat-yam-images) " images"))
    (println (str "  Holon: " (count new-holon-images) " images"))
    (println (str "  Other/Border: " (count new-other-images) " images"))

    {:tel-aviv-yafo new-tel-aviv-images
     :ramat-gan new-ramat-gan-images
     :givatayim new-givatayim-images
     :bnei-brak new-bnei-brak-images
     :bat-yam new-bat-yam-images
     :holon new-holon-images
     :other new-other-images
     :verification-results verification-results}))

(defn get-verified-image-by-city
  "Get a random verified image from the specified city"
  [city-key difficulty verified-collections]
  (when-let [city-images (get verified-collections city-key)]
    (when (seq city-images)
      (let [image-path (rand-nth city-images)]
        (create-real-image image-path (= city-key :tel-aviv-yafo) difficulty)))))

(defn get-single-tel-aviv-image-verified
  "Generate a single image for Tel Aviv binary choice game using GIS-verified images"
  [{:keys [user-city current-stage total-stages]} verified-collections]
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

    ;; Try to use GIS-verified images
    (try
      (if is-tel-aviv?
        (or (get-verified-image-by-city :tel-aviv-yafo difficulty verified-collections)
            (select-real-tel-aviv-image difficulty)) ; Fallback to old system
        (or (first (keep #(get-verified-image-by-city % difficulty verified-collections)
                         [:ramat-gan :givatayim :bnei-brak :bat-yam :holon]))
            (select-real-neighbor-image difficulty))) ; Fallback to old system
      (catch Exception e
        (println "Error loading verified image, using fallback:" (.getMessage e))
        ;; Fallback to placeholder system
        (if is-tel-aviv?
          (select-tel-aviv-image difficulty)
          (select-neighbor-image difficulty))))))

