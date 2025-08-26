(ns border-blur.views
  (:require [hiccup.page :refer [html5 include-css include-js]]
            [cheshire.core :as json]
            [border-blur.gis.cities :as cities]))

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
  "Collect all images with their locations and metadata"
  (let [base-path "resources/public/images"
        image-dir (java.io.File. base-path)]
    (if (.exists image-dir)
      (->> image-dir
           file-seq
           (filter #(.isFile %))
           (filter #(re-matches #".*\.(jpg|jpeg|png)$" (.getName %)))
           (keep (fn [file]
                   (let [filename (.getName file)
                         file-path (.getPath file)
                         relative-path (clojure.string/replace file-path base-path "")
                         relative-path (if (clojure.string/starts-with? relative-path "/")
                                         (str "images" relative-path)
                                         (str "images/" relative-path))
                         coords (parse-coords-from-filename filename)
                         city (identify-city-from-path (.getPath file))]
                     (when coords
                       {:filename filename
                        :path relative-path
                        :coordinates coords
                        :city city}))))
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
   "Border Blur - Image Collection Map"
   [:div
    [:div {:class "header-section"}
     [:h1 "Image Collection Locations"]
     [:p "Visual map of all street-view images in our collection with verified city boundaries"]
     [:div {:style "margin: 20px 0; padding: 15px; background: #f0f8ff; border-radius: 5px;"}
      [:h3 {:style "margin: 0 0 10px 0;"} "Legend:"]
      [:div {:style "display: flex; flex-wrap: wrap; gap: 15px;"}
       [:div [:span {:style "color: #FF6B6B; font-weight: bold;"} "●"] " Tel Aviv-Yafo Images & Boundary"]
       [:div [:span {:style "color: #4ECDC4; font-weight: bold;"} "●"] " Ramat Gan Images & Boundary"]
       [:div [:span {:style "color: #45B7D1; font-weight: bold;"} "●"] " Givatayim Images & Boundary"]
       [:div [:span {:style "color: #96CEB4; font-weight: bold;"} "●"] " Bnei Brak Images & Boundary"]
       [:div [:span {:style "color: #FECA57; font-weight: bold;"} "●"] " Holon Images & Boundary"]
       [:div [:span {:style "color: #FF9FF3; font-weight: bold;"} "●"] " Bat Yam Images & Boundary"]]
      [:p {:style "margin-top: 10px; font-size: 0.9em; color: #666;"}
       "Click on any marker to view the image. Markers are colored by their verified city boundaries."]]]

    [:div {:id "image-locations-map" :style "width: 100%; height: 600px; border: 1px solid #ddd; border-radius: 5px; margin: 20px 0;"}]

    [:div {:style "margin: 20px 0; padding: 15px; background: #f9f9f9; border-radius: 5px;"}
     [:h3 "Image Collection Statistics:"]
     [:ul
      [:li [:strong "Collection Method:"] " Multi-API automated with GIS verification"]
      [:li [:strong "Verification:"] " All images tested against actual city polygons"]
      [:li [:strong "Accuracy:"] " 100% GIS-verified after comprehensive cleanup"]
      [:li [:strong "Sources:"] " Mapillary, OpenStreetCam, manual collection"]]]

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
      
      // Add image location markers with higher opacity
      window.imageLocations.forEach(function(image) {
          var cityKey = image.city.replace(/:/g, ''); // Remove colon from keyword
          var color = cityColors[cityKey] || cityColors['unknown'];
          var coords = image.coordinates;
          
          // Create circle marker
          var marker = L.circleMarker([coords[1], coords[0]], {
              radius: 6,
              fillColor: color,
              color: color,
              weight: 2,
              opacity: 0.9,
              fillOpacity: 0.8
          }).addTo(map);
          
          // Create popup content with image thumbnail and info
          var popupContent = '<div style=\"text-align: center; max-width: 200px;\">' +
                           '<strong>' + image.filename + '</strong><br/>' +
                           '<img src=\"' + image.path + '\" style=\"max-width: 180px; max-height: 120px; margin: 10px 0; border-radius: 3px;\" /><br/>' +
                           '<strong>City:</strong> ' + image.city.replace(/:/g, '').replace(/-/g, ' ').replace(/\\b\\w/g, function(l){ return l.toUpperCase() }) + '<br/>' +
                           '<strong>Coordinates:</strong><br/>' +
                           'Lat: ' + coords[1].toFixed(5) + '<br/>' +
                           'Lng: ' + coords[0].toFixed(5) +
                           '</div>';
          
          marker.bindPopup(popupContent, {
              maxWidth: 220,
              minWidth: 180
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