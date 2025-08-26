(ns border-blur.views
  (:require [hiccup.page :refer [html5 include-css include-js]]
            [cheshire.core :as json]
            [border-blur.gis.cities :as cities]
            [border-blur.gis.core :as gis-core]
            [border-blur.images.spatial-optimizer :as optimizer]))

(defn generate-boundaries-json []
  "Generate JSON representation of all city boundaries for JavaScript"
  (let [boundaries (into {}
                         (map (fn [[key city]]
                                [key {:name (:name city)
                                      :boundary (:boundary city)
                                      :osmSource (boolean (:osm-source city))
                                      :osmId (:osm-id city)}])
                              cities/cities))]
    (json/generate-string boundaries)))

(defn parse-coords-from-filename [filename]
  "Extract coordinates from filename pattern: lat_lng_location_id.jpg"
  (when-let [match (re-find #"(\d+\.\d+)_(\d+\.\d+)_" filename)]
    [(Double/parseDouble (nth match 2)) ; lng first for [lng, lat] format
     (Double/parseDouble (nth match 1))]))

(defn identify-city-from-path [file-path]
  "Identify city from file path and content"
  (cond
    (re-find #"/tel-aviv/" file-path) :tel-aviv-yafo
    (re-find #"/ramat-gan/" file-path) :ramat-gan
    (re-find #"/givatayim/" file-path) :givatayim
    (re-find #"/bnei-brak/" file-path) :bnei-brak
    (re-find #"/holon/" file-path) :holon
    (re-find #"/bat-yam/" file-path) :bat-yam
    :else :unknown))

(defn get-all-image-locations []
  "Collect all verified-collection images with ENHANCED buffer-based city classification"
  ;; Load required namespaces for buffer classification

  (let [verified-collection-path "resources/public/images/verified-collection"
        collection-dir (java.io.File. verified-collection-path)]
    (if (.exists collection-dir)
      (->> (.listFiles collection-dir)
           (filter #(.isDirectory %))
           (mapcat (fn [city-dir]
                     (let [folder-city-name (.getName city-dir)
                           folder-city-key (keyword folder-city-name)]
                       (->> (.listFiles city-dir)
                            (filter #(.isFile %))
                            (filter #(.endsWith (.getName %) ".jpg"))
                            (keep (fn [image-file]
                                    (let [filename (.getName image-file)
                                          image-id (clojure.string/replace filename #"\.jpg$" "")
                                          metadata-file (java.io.File. city-dir (str image-id ".edn"))
                                          relative-path (str "images/verified-collection/" folder-city-name "/" filename)]
                                      (when (.exists metadata-file)
                                        (try
                                          (let [metadata (read-string (slurp metadata-file))
                                                coords (:coordinates metadata)
                                                lat (:lat coords)
                                                lng (:lng coords)

                                                ;; APPLY ENHANCED BUFFER-BASED CLASSIFICATION
                                                buffer-classification (optimizer/classify-image-by-gis lat lng cities/cities)

                                                ;; Determine actual vs folder city
                                                folder-suggests-city folder-city-name
                                                actual-city-key buffer-classification
                                                actual-city-name (case actual-city-key
                                                                   :tel-aviv-yafo "tel-aviv-yafo"
                                                                   :ramat-gan "ramat-gan"
                                                                   :givatayim "givatayim"
                                                                   :bnei-brak "bnei-brak"
                                                                   :bat-yam "bat-yam"
                                                                   :holon "holon"
                                                                   "unclassified")

                                                ;; Check classification accuracy
                                                folder-matches-buffer? (= folder-city-name actual-city-name)

                                                classification-status (cond
                                                                        (nil? actual-city-key) "outside-buffers"
                                                                        folder-matches-buffer? "accurate"
                                                                        :else "reclassified")]

                                            {:filename filename
                                             :path relative-path
                                             :coordinates [lng lat] ; [lng, lat] format for map
                                             :folder-city folder-city-name ; Original folder location
                                             :buffer-city actual-city-name ; New buffer-based classification
                                             :city actual-city-name ; Use buffer classification as primary
                                             :classification-status classification-status
                                             :classification-accurate folder-matches-buffer?
                                             :metadata metadata
                                             :verified true
                                             :buffer-enhanced true}) ; Mark as using enhanced classification

                                          (catch Exception e
                                            (println "Error processing" filename ":" (.getMessage e))
                                            ;; Fallback to folder-based classification
                                            (let [metadata (try (read-string (slurp metadata-file)) (catch Exception _ {}))
                                                  coords (get metadata :coordinates {:lat 0 :lng 0})]
                                              {:filename filename
                                               :path relative-path
                                               :coordinates [(:lng coords) (:lat coords)]
                                               :folder-city folder-city-name
                                               :buffer-city "error"
                                               :city folder-city-name
                                               :classification-status "fallback"
                                               :classification-accurate false
                                               :metadata metadata
                                               :verified false
                                               :buffer-enhanced false
                                               :error (.getMessage e)})))))))))))
           (vec))
      [])))

(defn generate-image-locations-json []
  "Generate JSON data for all image locations"
  (json/generate-string (get-all-image-locations)))

(defn layout [title content]
  "Base HTML layout with Leaflet.js for maps and OSM attribution"
  (html5
   [:head
    [:title title]
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    (include-css "/css/style.css")
    ;; Leaflet.js for map integration
    [:link {:rel "stylesheet" :href "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"}]
    [:script {:src "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"}]]
   [:body
    content
    ;; Attribution footer
    [:footer {:style "text-align: center; padding: 20px; font-size: 12px; color: #666; background: #f9f9f9; margin-top: 40px;"}
     [:p
      "Boundary data © "
      [:a {:href "https://www.openstreetmap.org/copyright" :target "_blank"} "OpenStreetMap contributors"]
      " | Licensed under "
      [:a {:href "https://opendatacommons.org/licenses/odbl/" :target "_blank"} "ODbL"]
      " | Educational use"]
     [:p {:style "font-size: 10px; margin-top: 5px;"}
      "Border Blur teaches Israeli geography through community-verified municipal boundaries"]]]))

(defn home-page []
  "Landing page for Tel Aviv geography game"
  (layout
   "Border Blur - Tel Aviv Geography Game"
   [:div.container
    [:header
     [:h1 "🌆 Border Blur"]
     [:p.subtitle "Test your Tel Aviv geography knowledge!"]]

    [:main
     [:div.intro
      [:p "This game tests your knowledge of Tel Aviv city boundaries."]
      [:p "You'll see street-view images and decide: Is it in Tel Aviv or not?"]
      [:p "After each answer, we'll reveal the true location on a map!"]]

     [:form.city-form {:method "post" :action "/start-game"}
      [:div.input-group
       [:label {:for "city"} "Have you lived or worked in Tel Aviv?"]
       [:select {:id "city" :name "city" :required true}
        [:option {:value "Yes, I know Tel Aviv well"} "Yes, I know Tel Aviv well"]
        [:option {:value "Somewhat familiar"} "Somewhat familiar with Tel Aviv"]
        [:option {:value "Not familiar"} "Not familiar with Tel Aviv"]]]
      [:button.start-btn {:type "submit"} "Start Tel Aviv Game 🎯"]]]

    [:footer
     [:p "No cookies used - your session is tracked by URL only"]]]))

(defn game-page [game-session current-image]
  "Main game interface with single Tel Aviv image"
  (layout
   (str "Stage " (:current-stage game-session) " of " (:total-stages game-session))
   [:div.game-container
    [:header.game-header
     [:div.progress
      [:span.stage-info "Stage " (:current-stage game-session) " of " (:total-stages game-session)]
      [:span.score "Score: " (:score game-session)]
      (when (> (:streak game-session) 0)
        [:span.streak "🔥 " (:streak game-session)])]
     [:div.progress-bar
      [:div.progress-fill {:style (str "width: "
                                       (* 100 (/ (dec (:current-stage game-session))
                                                 (:total-stages game-session))) "%")}]]]

    [:main.game-main
     [:div.question
      [:h2 "Is it in Tel Aviv?"]]

     [:div.single-image-container
      [:div.image-wrapper
       [:img.game-image {:src (:url current-image)
                         :alt "Street view location"}]
       [:div.difficulty-indicator {:class (name (:difficulty current-image))}
        (case (:difficulty current-image)
          :easy "Easy"
          :medium "Medium"
          :hard "Hard")]]]

     [:form.answer-form {:method "post" :action (str "/game/" (:session-id game-session) "/answer")}
      [:div.answer-buttons
       [:button.answer-btn.yes {:type "submit" :name "answer" :value "yes"}
        "✓ Yes, it's in Tel Aviv"]
       [:button.answer-btn.no {:type "submit" :name "answer" :value "no"}
        "✗ No, it's not in Tel Aviv"]]]]]))

(defn reveal-page [game-session result]
  "Answer reveal page with map showing true location"
  (let [current-image (:current-image result)
        correct? (:correct? result)
        points (:points result)
        is-in-tel-aviv (:is-in-tel-aviv current-image)]
    (layout
     "Answer Revealed!"
     [:div.reveal-container
      [:header.reveal-header
       [:div.result {:class (if correct? "correct" "incorrect")}
        (if correct? "✓ Correct!" "✗ Incorrect")
        [:span.points " +" points " points"]]
       (when (> (:streak result) 0)
         [:div.streak "🔥 Streak: " (:streak result)])
       [:div.score "Total Score: " (:score game-session)]]

      [:main.reveal-main
       [:div.image-result
        [:img.revealed-image {:src (:url current-image)
                              :alt "Location revealed"}]
        [:div.answer-reveal
         [:h3 "The Truth:"]
         [:p.location-answer {:class (if is-in-tel-aviv "tel-aviv" "not-tel-aviv")}
          (if is-in-tel-aviv
            "✓ This is in Tel Aviv"
            "✗ This is NOT in Tel Aviv")]
         [:p.location-name "📍 " (:location-name current-image)]]]

       [:div.map-container
        [:h3 "Location on Map:"]
        [:div#reveal-map.map-display {:data-coords (str (first (:coords current-image)) "," (second (:coords current-image)))
                                      :data-tel-aviv (str is-in-tel-aviv)
                                      :data-location (str (:location-name current-image))}]
        [:div.map-legend
         [:span.tel-aviv-boundary "— Tel Aviv Boundary"]
         [:span.location-marker "📍 Image Location"]]]]

      [:form.next-form {:method "post" :action (str "/game/" (:session-id game-session) "/next")}
       [:button.next-btn {:type "submit"}
        (if (>= (inc (:current-stage game-session)) (:total-stages game-session))
          "View Final Results 🏆"
          "Next Image →")]]])))

(defn results-page [game-session]
  "Final results page"
  (let [total-stages (count (:stages game-session))
        correct-answers (count (filter :correct? (:stages game-session)))
        accuracy (if (> total-stages 0)
                   (int (* 100 (/ correct-answers total-stages)))
                   0)]
    (layout
     "Game Results"
     [:div.results-container
      [:header.results-header
       [:h1 "🎯 Game Complete!"]
       [:div.final-score
        [:span.score-value (:score game-session)]
        [:span.score-label "points"]]
       [:div.accuracy
        [:span.accuracy-value accuracy "%"]
        [:span.accuracy-label "accuracy"]]]

      [:main.results-main
       [:div.game-summary
        [:p "You played " total-stages " stages"]
        [:p "Got " correct-answers " correct answers"]
        (when (> (:streak game-session) 5)
          [:p "🔥 Best streak: " (apply max (map :streak (:stages game-session)))])]

       [:div.stage-breakdown
        [:h3 "Stage by Stage:"]
        [:ul.stages-list
         (for [stage (:stages game-session)]
           [:li.stage-item {:class (if (:correct? stage) "correct" "incorrect")}
            [:span.stage-number "Stage " (:stage stage)]
            [:span.stage-result (if (:correct? stage) "✓" "✗")]
            [:span.stage-points "+" (:points stage) " pts"]])]]

       [:div.play-again
        [:a.play-again-btn {:href "/"} "Play Again 🎮"]]]

      [:footer
       [:p "Thanks for playing Border Blur!"]]])))

(defn error-page [message]
  "Error page"
  (layout
   "Error"
   [:div.error-container
    [:h1 "😕 Oops!"]
    [:p message]
    [:a {:href "/"} "← Back to Home"]]))

(defn boundaries-page []
  "Interactive map showing all city boundaries for visualization and testing"
  (layout
   "Border Blur - City Boundaries Visualization"
   [:div
    [:div {:class "header-section"}
     [:h1 "Israeli City Boundaries"]
     [:p "Visual verification of OpenStreetMap municipal boundaries used in Border Blur"]
     [:div {:style "margin: 20px 0; padding: 15px; background: #f0f8ff; border-radius: 5px;"}
      [:h3 {:style "margin: 0 0 10px 0;"} "Legend:"]
      [:div {:style "display: flex; flex-wrap: wrap; gap: 15px;"}
       [:div [:span {:style "color: #FF6B6B; font-weight: bold;"} "●"] " Tel Aviv-Yafo (🗺️ OSM)"]
       [:div [:span {:style "color: #4ECDC4; font-weight: bold;"} "●"] " Ramat Gan (🗺️ OSM)"]
       [:div [:span {:style "color: #45B7D1; font-weight: bold;"} "●"] " Givatayim (🗺️ OSM)"]
       [:div [:span {:style "color: #96CEB4; font-weight: bold;"} "●"] " Bnei Brak (🗺️ OSM)"]
       [:div [:span {:style "color: #FECA57; font-weight: bold;"} "●"] " Holon (🗺️ OSM)"]
       [:div [:span {:style "color: #FF9FF3; font-weight: bold;"} "●"] " Bat Yam (🗺️ OSM)"]]]]

    [:div {:id "boundaries-map" :style "width: 100%; height: 600px; border: 1px solid #ddd; border-radius: 5px; margin: 20px 0;"}]

    [:div {:style "margin: 20px 0; padding: 15px; background: #f9f9f9; border-radius: 5px;"}
     [:h3 "Boundary Data Sources:"]
     [:ul
      [:li [:strong "🗺️ OSM Official"] " - Community-verified boundaries from OpenStreetMap (6 cities)"]
      [:li [:strong "Total"] " - 2,000+ coordinate points from official data"]
      [:li [:strong "Updated"] " - Tel Aviv and Yafo merged as single Tel Aviv-Yafo municipality (correct since 1950)"]]]

    [:div {:style "text-align: center; margin: 30px 0;"}
     [:a {:href "/" :class "button"} "← Back to Game"]
     [:span {:style "margin: 0 20px;"} "•"]
     [:a {:href "https://www.openstreetmap.org/copyright" :target "_blank"} "OSM Copyright"]
     [:span {:style "margin: 0 20px;"} "•"]
     [:a {:href "https://stadiamaps.com/" :target "_blank"} "Stadia Maps"]
     [:span {:style "margin: 0 20px;"} "•"]
     [:a {:href "/boundaries" :onclick "location.reload(); return false;"} "Refresh Map"]]

     ;; Inject city boundary data as JavaScript
    [:script {:type "text/javascript"}
     (str "window.cityBoundaries = " (generate-boundaries-json) ";")]

     ;; JavaScript to render boundaries
    [:script {:type "text/javascript"}
     "
      // Initialize map centered on Tel Aviv area
      var map = L.map('boundaries-map').setView([32.0853, 34.8131], 11);
      
      // Add Stadia AlidadeSmooth tiles
      L.tileLayer('https://tiles.stadiamaps.com/tiles/alidade_smooth/{z}/{x}/{y}{r}.png', {
          attribution: '&copy; <a href=\"https://stadiamaps.com/\">Stadia Maps</a>, &copy; <a href=\"https://openmaptiles.org/\">OpenMapTiles</a> &copy; <a href=\"http://openstreetmap.org\">OpenStreetMap</a> contributors',
          maxZoom: 20,
      }).addTo(map);
      
      // City colors matching legend (updated for corrected city keys)
      var cityColors = {
          'tel-aviv-yafo': '#FF6B6B',
          'ramat-gan': '#4ECDC4', 
          'givatayim': '#45B7D1',
          'bnei-brak': '#96CEB4',
          'holon': '#FECA57',
          'bat-yam': '#FF9FF3'
      };
      
      // Add city boundaries
      Object.keys(window.cityBoundaries).forEach(function(cityKey) {
          var city = window.cityBoundaries[cityKey];
          var color = cityColors[cityKey] || '#999999';
          
          // Convert coordinates to Leaflet format [lat, lng]
          var latLngs = city.boundary.map(function(coord) {
              return [coord[1], coord[0]]; // Swap lng,lat to lat,lng
          });
          
          // Create polygon
          var polygon = L.polygon(latLngs, {
              color: color,
              weight: 2,
              fillOpacity: 0.3,
              fillColor: color
          }).addTo(map);
          
          // Add popup with city info
          var osmText = city.osmSource ? '🗺️ OSM Official' : '📐 Approximate';
          var pointCount = city.boundary.length;
          polygon.bindPopup('<strong>' + city.name + '</strong><br/>' +
                          osmText + '<br/>' +
                          pointCount + ' boundary points' +
                          (city.osmId ? '<br/>OSM ID: ' + city.osmId : ''));
      });
      "]]))

(defn image-locations-page []
  "Interactive map showing all collected image locations with city boundaries"
  (layout
   "Border Blur - Verified Image Collection Map"
   [:div
    [:div {:class "header-section"}
     [:h1 "Spatially Optimized Image Collection"]
     [:p "Visual map of all GIS-verified street-view images in our collection with confirmed city boundaries"]
     [:div {:style "margin: 20px 0; padding: 15px; background: #f0f8ff; border-radius: 5px;"}
      [:h3 {:style "margin: 0 0 10px 0;"} "Enhanced Buffer-Based Classification Legend:"]
      [:div {:style "display: flex; flex-wrap: wrap; gap: 15px; margin-bottom: 10px;"}
       [:div [:span {:style "color: #FF6B6B; font-weight: bold;"} "●"] " Tel Aviv-Yafo (Buffer Verified)"]
       [:div [:span {:style "color: #4ECDC4; font-weight: bold;"} "●"] " Ramat Gan (Buffer Verified)"]
       [:div [:span {:style "color: #45B7D1; font-weight: bold;"} "●"] " Givatayim (Buffer Verified)"]
       [:div [:span {:style "color: #96CEB4; font-weight: bold;"} "●"] " Bnei Brak (Buffer Verified)"]
       [:div [:span {:style "color: #FECA57; font-weight: bold;"} "●"] " Holon (Buffer Verified)"]
       [:div [:span {:style "color: #FF9FF3; font-weight: bold;"} "●"] " Bat Yam (Buffer Verified)"]
       [:div [:span {:style "color: #999999; font-weight: bold;"} "●"] " Outside Buffers/Reclassified"]]
      [:div {:style "display: flex; flex-wrap: wrap; gap: 20px; margin: 10px 0; padding: 10px; background: rgba(255,255,255,0.7); border-radius: 3px;"}
       [:div [:span {:style "background: #4CAF50; color: white; padding: 2px 6px; border-radius: 3px; font-size: 0.8em;"} "✓"] " Folder matches buffer classification"]
       [:div [:span {:style "background: #FF9800; color: white; padding: 2px 6px; border-radius: 3px; font-size: 0.8em;"} "⚠"] " Reclassified by buffer analysis"]
       [:div [:span {:style "background: #f44336; color: white; padding: 2px 6px; border-radius: 3px; font-size: 0.8em;"} "◯"] " Outside all 10m city buffers"]]
      [:p {:style "margin-top: 10px; font-size: 0.9em; color: #666;"}
       "Click markers to see buffer classification vs original folder location. Images now classified using 10-meter buffer exclusivity system."]]]

    [:div {:id "image-locations-map" :style "width: 100%; height: 600px; border: 1px solid #ddd; border-radius: 5px; margin: 20px 0;"}]

    [:div {:style "margin: 20px 0; padding: 15px; background: #e8f5e8; border-radius: 5px;"}
     [:h3 "✅ Verified Collection Statistics:"]
     [:ul
      [:li [:strong "Collection Method:"] " Advanced Spatial Optimization System with GIS verification"]
      [:li [:strong "Verification:"] " 100% ray-casting point-in-polygon testing against city boundaries"]
      [:li [:strong "Image Sources:"] " Mapillary API with real-time fetching"]
      [:li [:strong "Total Images:"] (str " " (count (get-all-image-locations)) " spatially optimized images")]
      [:li [:strong "Cities Covered:"] " 6 Israeli cities with full boundary verification"]
      [:li [:strong "Metadata:"] " Complete attribution, coordinates, and verification data"]]]

    [:div {:style "text-align: center; margin: 30px 0;"}
     [:a {:href "/" :class "button"} "← Back to Game"]
     [:span {:style "margin: 0 20px;"} "•"]
     [:a {:href "/boundaries" :class "button-secondary"} "View Boundaries Only"]
     [:span {:style "margin: 0 20px;"} "•"]
     [:a {:href "/image-locations" :onclick "location.reload(); return false;"} "Refresh Map"]]

     ;; Inject city boundary data as JavaScript
    [:script {:type "text/javascript"}
     (str "window.cityBoundaries = " (generate-boundaries-json) ";")]

     ;; Inject image location data as JavaScript  
    [:script {:type "text/javascript"}
     (str "window.imageLocations = " (generate-image-locations-json) ";")]

     ;; JavaScript to render map with boundaries and image markers
    [:script {:type "text/javascript"}
     "
      // Initialize map centered on Tel Aviv area
      var map = L.map('image-locations-map').setView([32.0853, 34.8131], 11);
      
      // Add Stadia AlidadeSmooth tiles
      L.tileLayer('https://tiles.stadiamaps.com/tiles/alidade_smooth/{z}/{x}/{y}{r}.png', {
          attribution: '&copy; <a href=\"https://stadiamaps.com/\">Stadia Maps</a>, &copy; <a href=\"https://openmaptiles.org/\">OpenMapTiles</a> &copy; <a href=\"http://openstreetmap.org\">OpenStreetMap</a> contributors',
          maxZoom: 20,
      }).addTo(map);
      
      // City colors matching legend
      var cityColors = {
          'tel-aviv-yafo': '#FF6B6B',
          'ramat-gan': '#4ECDC4', 
          'givatayim': '#45B7D1',
          'bnei-brak': '#96CEB4',
          'holon': '#FECA57',
          'bat-yam': '#FF9FF3',
          'unknown': '#999999'
      };
      
      // Add city boundaries first (lower opacity)
      Object.keys(window.cityBoundaries).forEach(function(cityKey) {
          var city = window.cityBoundaries[cityKey];
          var color = cityColors[cityKey] || '#999999';
          
          // Convert coordinates to Leaflet format [lat, lng]
          var latLngs = city.boundary.map(function(coord) {
              return [coord[1], coord[0]]; // Swap lng,lat to lat,lng
          });
          
          // Create polygon with lower opacity than boundaries-only page
          var polygon = L.polygon(latLngs, {
              color: color,
              weight: 1.5,
              fillOpacity: 0.1,
              fillColor: color,
              opacity: 0.6
          }).addTo(map);
          
          // Add popup with city info
          var osmText = city.osmSource ? '🗺️ OSM Official' : '📐 Approximate';
          var pointCount = city.boundary.length;
          polygon.bindPopup('<strong>' + city.name + '</strong><br/>' +
                          osmText + '<br/>' +
                          pointCount + ' boundary points' +
                          (city.osmId ? '<br/>OSM ID: ' + city.osmId : ''));
      });
      
      // Add verified image markers with ENHANCED buffer-based classification styling
      window.imageLocations.forEach(function(image) {
          var bufferCity = image.bufferCity || image.city; // Use buffer classification
          var folderCity = image.folderCity || image.city; // Original folder location
          var cityKey = bufferCity.replace(/:/g, ''); // Remove colon from keyword
          var color = cityColors[cityKey] || cityColors['unknown'];
          var coords = image.coordinates;
          
          // Determine marker style based on classification accuracy
          var isAccurate = image.classificationAccurate !== false;
          var isOutsideBuffers = image.classificationStatus === 'outside-buffers';
          var isReclassified = image.classificationStatus === 'reclassified';
          
          // Create circle marker with classification-aware styling
          var markerStyle = {
              radius: isReclassified ? 9 : 7, // Larger for reclassified
              fillColor: color,
              color: isAccurate ? '#fff' : (isOutsideBuffers ? '#f44336' : '#FF9800'), // White border for accurate, red for outside buffers, orange for reclassified
              weight: isReclassified ? 3 : 2, // Thicker border for reclassified
              opacity: 1.0,
              fillOpacity: isOutsideBuffers ? 0.5 : 0.9, // Lower opacity for outside buffers
              dashArray: isReclassified ? '5,3' : null // Dashed border for reclassified
          };
          
          var marker = L.circleMarker([coords[1], coords[0]], markerStyle).addTo(map);
          
          // Enhanced popup content with buffer classification details
          var statusBadge = '';
          var statusBackground = '';
          if (isAccurate && !isOutsideBuffers) {
              statusBadge = '✅ BUFFER VERIFIED';
              statusBackground = '#4CAF50';
          } else if (isReclassified) {
              statusBadge = '⚠️ RECLASSIFIED';
              statusBackground = '#FF9800';
          } else if (isOutsideBuffers) {
              statusBadge = '◯ OUTSIDE BUFFERS';
              statusBackground = '#f44336';
          } else {
              statusBadge = '❓ UNKNOWN STATUS';
              statusBackground = '#999';
          }
          
          var popupContent = '<div style=\"text-align: center; max-width: 260px;\">' +
                           '<div style=\"background: ' + statusBackground + '; color: white; padding: 5px; border-radius: 3px; margin-bottom: 10px;\">' +
                           '<strong>' + statusBadge + '</strong>' +
                           '</div>' +
                           '<strong>' + image.filename + '</strong><br/>' +
                           '<img src=\"' + image.path + '\" style=\"max-width: 200px; max-height: 140px; margin: 10px 0; border-radius: 3px; border: 1px solid #ddd;\" /><br/>';
          
          // Add classification comparison if different
          if (folderCity !== bufferCity && bufferCity !== 'unclassified') {
              popupContent += '<div style=\"background: #fff3cd; padding: 5px; border-radius: 3px; margin: 5px 0; font-size: 0.9em;\">' +
                            '<strong>📁 Folder:</strong> ' + folderCity.replace(/-/g, ' ').replace(/\\b\\w/g, function(l){ return l.toUpperCase() }) + '<br/>' +
                            '<strong>🎯 Buffer:</strong> ' + bufferCity.replace(/-/g, ' ').replace(/\\b\\w/g, function(l){ return l.toUpperCase() }) +
                            '</div>';
          }
          
          popupContent += '<strong>Final Classification:</strong> ' + bufferCity.replace(/:/g, '').replace(/-/g, ' ').replace(/\\b\\w/g, function(l){ return l.toUpperCase() }) + '<br/>' +
                         '<strong>Method:</strong> 10m Buffer Exclusivity<br/>' +
                         '<strong>Coordinates:</strong><br/>' +
                         'Lat: ' + coords[1].toFixed(6) + '<br/>' +
                         'Lng: ' + coords[0].toFixed(6);
          
          if (image.error) {
              popupContent += '<div style=\"background: #f8d7da; color: #721c24; padding: 5px; border-radius: 3px; margin-top: 5px; font-size: 0.8em;\">' +
                            '<strong>Error:</strong> ' + image.error +
                            '</div>';
          }
          
          popupContent += '</div>';
          
          marker.bindPopup(popupContent, {
              maxWidth: 280,
              minWidth: 240
          });
      });
      
      // Update map bounds to include all markers if we have images
      if (window.imageLocations.length > 0) {
          var group = new L.featureGroup();
          window.imageLocations.forEach(function(image) {
              var coords = image.coordinates;
              group.addLayer(L.marker([coords[1], coords[0]]));
          });
          map.fitBounds(group.getBounds().pad(0.1));
      }
      "]]))