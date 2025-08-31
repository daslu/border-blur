(ns border-blur.web.server
  "Web server for NYC street view image visualization"
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.util.response :refer [response content-type redirect]]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [border-blur.borough-game :as borough-game]))

(def ^:dynamic *server* nil)

(defn load-image-data
  "Load collected NYC street view images from JSON file"
  []
  (let [random-file "data/random-classified-images.json"
        grid-file "data/nyc-images.json"]
    (cond
      (.exists (io/file random-file))
      (try
        (json/parse-string (slurp random-file) true)
        (catch Exception e
          (println "Error loading random image data:" (.getMessage e))
          []))

      (.exists (io/file grid-file))
      (try
        (json/parse-string (slurp grid-file) true)
        (catch Exception e
          (println "Error loading grid image data:" (.getMessage e))
          []))

      :else [])))

(defn load-borough-data
  "Load NYC borough boundary data from EDN file"
  []
  (when (.exists (io/file "resources/boroughs/nyc-boroughs.edn"))
    (try
      (read-string (slurp "resources/boroughs/nyc-boroughs.edn"))
      (catch Exception e
        (println "Error loading borough data:" (.getMessage e))
        {}))))

(defn borough-color
  "Return color for borough visualization"
  [borough]
  (case borough
    :manhattan "#DC2626" ; Dark red
    :brooklyn "#059669" ; Dark green  
    :queens "#1D4ED8" ; Dark blue
    :bronx "#7C2D12" ; Dark brown
    :staten-island "#A16207" ; Dark amber
    "#4B5563")) ; Dark grey for unclassified ; grey for unclassified

(defn images-geojson
  "Convert image data to GeoJSON format for map visualization"
  []
  (let [images (load-image-data)]
    {:type "FeatureCollection"
     :features (mapv (fn [image]
                       (let [borough-kw (cond
                                          (nil? (:borough image)) :unclassified
                                          (= "unknown" (:borough image)) :unclassified
                                          :else (keyword (:borough image)))]
                         {:type "Feature"
                          :geometry {:type "Point"
                                     :coordinates [(:lng image) (:lat image)]}
                          :properties {:id (:id image)
                                       :borough borough-kw
                                       :color (borough-color borough-kw)
                                       :captured-at (:captured-at image)
                                       :url (:url image)}}))
                     images)}))

(defn boroughs-geojson
  "Convert borough boundary data to GeoJSON format"
  []
  (let [boroughs (load-borough-data)]
    {:type "FeatureCollection"
     :features (mapv (fn [[borough-key borough-data]]
                       {:type "Feature"
                        :geometry {:type "Polygon"
                                   :coordinates [(mapv (fn [[lng lat]] [lng lat])
                                                       (:full-boundary borough-data))]}
                        :properties {:name (name borough-key)
                                     :borough borough-key
                                     :color (borough-color borough-key)}})
                     boroughs)}))

(defn map-page
  "Generate HTML page with interactive map"
  []
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "NYC Street View Images"]
    [:link {:rel "stylesheet"
            :href "https://unpkg.com/leaflet@1.7.1/dist/leaflet.css"}]
    [:style "
       body { margin: 0; padding: 0; font-family: Arial, sans-serif; }
       #map { height: 100vh; width: 100vw; }
       .legend { 
         background: white; 
         padding: 10px; 
         border-radius: 5px; 
         box-shadow: 0 1px 5px rgba(0,0,0,0.4);
       }
       .legend-item { 
         display: flex; 
         align-items: center; 
         margin: 2px 0; 
       }
       .legend-color { 
         width: 12px; 
         height: 12px; 
         border-radius: 50%; 
         margin-right: 8px; 
       }
       #image-viewer {
         position: fixed;
         top: 20px;
         right: 20px;
         width: 350px;
         max-height: 400px;
         background: white;
         border-radius: 8px;
         box-shadow: 0 4px 12px rgba(0,0,0,0.3);
         padding: 15px;
         display: none;
         z-index: 1000;
         overflow-y: auto;
       }
       #image-viewer img {
         max-width: 100%;
         height: auto;
         display: block;
         margin: 10px 0;
         border-radius: 4px;
       }
       #image-viewer .close-btn {
         float: right;
         background: #f44336;
         color: white;
         border: none;
         border-radius: 50%;
         width: 25px;
         height: 25px;
         cursor: pointer;
         margin-bottom: 10px;
       }
       #image-viewer .info {
         margin-bottom: 10px;
         font-size: 14px;
       }
     "]]
   [:body
    [:div#map]
    [:div#image-viewer
     [:button.close-btn {:onclick "document.getElementById('image-viewer').style.display='none'"} "×"]
     [:div.info "Click a marker to view image"]
     [:img {:id "viewer-image" :style "display: none;"}]
     [:a {:id "viewer-link" :target "_blank" :style "display: none;"} "View Full Size"]]
    [:script {:src "https://unpkg.com/leaflet@1.7.1/dist/leaflet.js"}]
    [:script
     (str "
        var map = L.map('map').setView([40.7128, -74.0060], 11);
        
        L.tileLayer('https://tiles.stadiamaps.com/tiles/alidade_smooth/{z}/{x}/{y}{r}.png', {
          maxZoom: 20,
          attribution: '&copy; <a href=\"https://stadiamaps.com/\">Stadia Maps</a>, &copy; <a href=\"https://openmaptiles.org/\">OpenMapTiles</a> &copy; <a href=\"http://openstreetmap.org\">OpenStreetMap</a> contributors'
        }).addTo(map);
        
        // Load borough boundaries first
        fetch('/api/boroughs')
          .then(response => response.json())
          .then(data => {
            data.features.forEach(feature => {
              var coords = feature.geometry.coordinates[0];
              var props = feature.properties;
              
              // Convert coordinates for Leaflet (swap lng/lat)
              var leafletCoords = coords.map(coord => [coord[1], coord[0]]);
              
              L.polygon(leafletCoords, {
                color: props.color,
                weight: 2,
                opacity: 0.7,
                fillColor: props.color,
                fillOpacity: 0.1,
                interactive: false  // Make polygons non-interactive
              })
              .addTo(map);
            });
          });
        
        // Load image points
        fetch('/api/images')
          .then(response => response.json())
          .then(data => {
            data.features.forEach(feature => {
              var coords = feature.geometry.coordinates;
              var props = feature.properties;
              
              // Create circle marker
              var marker = L.circleMarker([coords[1], coords[0]], {
                radius: 8,
                fillColor: props.color,
                color: props.color,
                weight: 2,
                opacity: 1,
                fillOpacity: 0.8
              });
              
              marker.addTo(map);
              
              // Add click handler to show image in viewer
              marker.on('click', function(e) {
                var viewer = document.getElementById('image-viewer');
                
                if (!viewer) return;
                
                var img = document.getElementById('viewer-image');
                var link = document.getElementById('viewer-link');
                var info = viewer.querySelector('.info');
                
                // Update info text
                if (info) {
                  info.innerHTML = '<strong>' + (props.borough || 'Unknown') + '</strong><br>' +
                    'Captured: ' + new Date(props['captured-at']).toLocaleDateString();
                }
                
                // Set up image
                if (img) {
                  img.style.display = 'none';
                  img.onload = function() {
                    this.style.display = 'block';
                  };
                  img.onerror = function() {
                    this.style.display = 'none';
                    if (info) info.innerHTML += '<br><em>Image could not be loaded</em>';
                  };
                  img.src = props.url;
                }
                
                // Set up link
                if (link) {
                  link.href = props.url;
                  link.style.display = 'block';
                }
                
                // Show the viewer
                viewer.style.display = 'block';
              });
              
              // No popup - only click handler for image viewer
            });
          });
        
        var legend = L.control({position: 'topright'});
        legend.onAdd = function (map) {
          var div = L.DomUtil.create('div', 'legend');
          div.innerHTML = '<h4>Boroughs</h4>' +
            '<div class=\"legend-item\"><div class=\"legend-color\" style=\"background-color: #DC2626\"></div>Manhattan</div>' +
            '<div class=\"legend-item\"><div class=\"legend-color\" style=\"background-color: #059669\"></div>Brooklyn</div>' +
            '<div class=\"legend-item\"><div class=\"legend-color\" style=\"background-color: #1D4ED8\"></div>Queens</div>' +
            '<div class=\"legend-item\"><div class=\"legend-color\" style=\"background-color: #7C2D12\"></div>Bronx</div>' +
            '<div class=\"legend-item\"><div class=\"legend-color\" style=\"background-color: #A16207\"></div>Staten Island</div>' +
            '<div class=\"legend-item\"><div class=\"legend-color\" style=\"background-color: #4B5563\"></div>Unclassified</div>';
          return div;
        };
        legend.addTo(map);
      ")]]))

(defn borough-game-landing []
  "Landing page for borough identification game"
  (html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "Which Borough? - NYC Geography Game"]
    [:style "
       body { margin: 0; padding: 0; font-family: Arial, sans-serif; background: #f5f5f5; }
       .container { max-width: 800px; margin: 0 auto; padding: 20px; }
       .header { text-align: center; margin-bottom: 30px; }
       .header h1 { color: #2563eb; font-size: 2.5em; margin-bottom: 10px; }
       .subtitle { color: #6b7280; font-size: 1.2em; }
       .intro { background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); margin-bottom: 30px; }
       .intro p { margin: 15px 0; line-height: 1.6; }
       .form-container { background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
       .form-group { margin-bottom: 20px; }
       label { display: block; margin-bottom: 10px; font-weight: bold; color: #374151; }
       select { width: 100%; padding: 12px; border: 2px solid #d1d5db; border-radius: 6px; font-size: 16px; }
       .start-btn { background: #2563eb; color: white; padding: 15px 30px; border: none; border-radius: 6px; font-size: 18px; font-weight: bold; cursor: pointer; width: 100%; }
       .start-btn:hover { background: #1d4ed8; }
     "]]
   [:body
    [:div.container
     [:header.header
      [:h1 "🏙️ Which Borough?"]
      [:p.subtitle "Test your NYC geography knowledge!"]]

     [:div.intro
      [:p "Welcome to the NYC Borough Identification Game!"]
      [:p "You'll see street-view images from around New York City. Your job is to identify which of the 5 boroughs each image is from."]
      [:p "After each answer, we'll reveal the correct location on an interactive map with all borough boundaries."]
      [:p "📊 <strong>15 stages</strong> with progressive difficulty - start with clear borough centers, then move to challenging border areas."]]

     [:div.form-container
      [:form {:method "post" :action "/borough-game/start"}
       [:div.form-group
        [:label {:for "familiarity"} "How familiar are you with New York City?"]
        [:select {:id "familiarity" :name "familiarity" :required true}
         [:option {:value "Very familiar - I live/lived in NYC"} "Very familiar - I live/lived in NYC"]
         [:option {:value "Somewhat familiar - I've visited often"} "Somewhat familiar - I've visited often"]
         [:option {:value "Not very familiar - Limited experience"} "Not very familiar - Limited experience"]
         [:option {:value "Not familiar at all"} "Not familiar at all"]]]
       [:button.start-btn {:type "submit"} "Start Borough Game 🎯"]]]]]))

(defn borough-game-page [session-id]
  "Main borough game interface"
  (if-let [game-session (borough-game/get-game-session session-id)]
    (if (:answer-revealed game-session)
      ;; Show reveal page
      (redirect (str "/borough-game/" session-id "/reveal"))
      ;; Show game question
      (if-let [current-image (:current-image game-session)]
        (html5
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title (str "Stage " (:current-stage game-session) " of " (:total-stages game-session))]
          [:style "
             body { margin: 0; padding: 0; font-family: Arial, sans-serif; background: #f5f5f5; }
             .game-container { max-width: 1000px; margin: 0 auto; padding: 20px; }
             .game-header { background: white; padding: 20px; border-radius: 10px; margin-bottom: 20px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
             .progress { display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px; }
             .stage-info { font-weight: bold; color: #374151; }
             .score { color: #059669; font-weight: bold; }
             .streak { color: #dc2626; font-weight: bold; }
             .progress-bar { width: 100%; height: 8px; background: #e5e7eb; border-radius: 4px; overflow: hidden; }
             .progress-fill { height: 100%; background: #2563eb; transition: width 0.3s ease; }
             .question { text-align: center; margin: 30px 0; }
             .question h2 { color: #1f2937; font-size: 2em; margin-bottom: 20px; }
             .image-container { text-align: center; margin-bottom: 30px; }
             .game-image { max-width: 100%; max-height: 500px; border-radius: 10px; box-shadow: 0 4px 20px rgba(0,0,0,0.1); }
             .difficulty-indicator { display: inline-block; padding: 5px 15px; border-radius: 20px; font-size: 14px; font-weight: bold; margin-top: 10px; }
             .difficulty-indicator.easy { background: #d1fae5; color: #065f46; }
             .difficulty-indicator.medium { background: #fef3c7; color: #92400e; }
             .difficulty-indicator.hard { background: #fee2e2; color: #991b1b; }
             .answer-options { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 15px; max-width: 800px; margin: 0 auto; }
             .borough-btn { 
               padding: 15px 25px; 
               border: 3px solid transparent; 
               border-radius: 8px; 
               font-size: 18px; 
               font-weight: bold; 
               cursor: pointer; 
               transition: all 0.2s; 
               color: white !important;
               text-shadow: 1px 1px 2px rgba(0,0,0,0.5);
               box-shadow: 0 2px 8px rgba(0,0,0,0.2);
             }
             .borough-btn:hover { transform: translateY(-2px); box-shadow: 0 6px 20px rgba(0,0,0,0.3) !important; }
             .borough-btn.manhattan { background: #EF4444 !important; border-color: #DC2626 !important; }
             .borough-btn.manhattan:hover { background: #DC2626 !important; }
             .borough-btn.brooklyn { background: #10B981 !important; border-color: #059669 !important; }
             .borough-btn.brooklyn:hover { background: #059669 !important; }
             .borough-btn.queens { background: #3B82F6 !important; border-color: #2563EB !important; }
             .borough-btn.queens:hover { background: #2563EB !important; }
             .borough-btn.bronx { background: #A16207 !important; border-color: #92400E !important; }
             .borough-btn.bronx:hover { background: #92400E !important; }
             .borough-btn.staten-island { background: #F59E0B !important; border-color: #D97706 !important; }
             .borough-btn.staten-island:hover { background: #D97706 !important; }
           "]]
         [:body
          [:div.game-container
           [:div.game-header
            [:div.progress
             [:span.stage-info "Stage " (:current-stage game-session) " of " (:total-stages game-session)]
             [:span.score "Score: " (:score game-session)]
             (when (> (:streak game-session) 0)
               [:span.streak "🔥 " (:streak game-session)])]
            [:div.progress-bar
             [:div.progress-fill {:style (str "width: " (* 100 (/ (dec (:current-stage game-session)) (:total-stages game-session))) "%")}]]]

           [:div.question
            [:h2 "Which Borough is this?"]]

           [:div.image-container
            [:img.game-image {:src (:url current-image) :alt "Street view location"}]
            [:div.difficulty-indicator {:class (name (:difficulty current-image))}
             (str (name (:difficulty current-image)) " difficulty")]]

           [:form.answer-form {:method "post" :action (str "/borough-game/" session-id "/answer")}
            [:div.answer-options
             (for [borough borough-game/boroughs]
               (let [borough-name (name borough)
                     borough-color (case borough
                                     :manhattan "#EF4444"
                                     :brooklyn "#10B981"
                                     :queens "#3B82F6"
                                     :bronx "#A16207"
                                     :staten-island "#F59E0B"
                                     "#6B7280")]
                 [:button.borough-btn {:type "submit"
                                       :name "answer"
                                       :value borough-name
                                       :class (str "borough-btn " borough-name)
                                       :style (str "background-color: " borough-color " !important; "
                                                   "border-color: " borough-color " !important; "
                                                   "color: white !important;")}
                  (get borough-game/borough-display-names borough)]))]]]])
        ;; No current image - redirect to results or error
        (redirect (str "/borough-game/" session-id "/results"))))
    ;; No session found
    (html5
     [:head [:title "Game Not Found"]]
     [:body
      [:div {:style "text-align: center; padding: 50px;"}
       [:h1 "Game session not found"]
       [:p [:a {:href "/borough-game"} "Start a new game"]]]])))

(defn borough-reveal-page [game-session]
  "Answer reveal page with interactive map"
  (let [current-image (:current-image game-session)
        last-stage (last (:stages game-session))]

    ;; Handle case where stages might be empty (shouldn't happen in normal flow)
    (if (nil? last-stage)
      (redirect "/borough-game") ; Redirect if no stage data

      (let [correct? (:correct? last-stage)
            points (:points last-stage)
            correct-borough (:correct-answer last-stage)
            user-borough (:user-answer last-stage)]

        (html5
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:title "Answer Revealed!"]
          [:link {:rel "stylesheet" :href "https://unpkg.com/leaflet@1.7.1/dist/leaflet.css"}]
          [:style "
             body { margin: 0; padding: 0; font-family: Arial, sans-serif; background: #f5f5f5; }
             .reveal-container { max-width: 1000px; margin: 0 auto; padding: 20px; }
             .reveal-header { background: white; padding: 20px; border-radius: 10px; margin-bottom: 20px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); text-align: center; }
             .result { font-size: 1.5em; font-weight: bold; margin-bottom: 10px; }
             .result.correct { color: #059669; }
             .result.incorrect { color: #dc2626; }
             .points { color: #2563eb; font-size: 1.2em; }
             .streak { color: #dc2626; font-weight: bold; margin: 10px 0; }
             .score { color: #374151; font-size: 1.1em; }
             .reveal-main { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
             .image-result { background: white; padding: 20px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
             .revealed-image { max-width: 100%; border-radius: 8px; }
             .answer-reveal { margin-top: 20px; text-align: center; }
             .location-answer { font-size: 1.3em; font-weight: bold; margin: 15px 0; padding: 15px; border-radius: 8px; }
             .borough-correct { background: #d1fae5; color: #065f46; }
             .borough-incorrect { background: #fee2e2; color: #991b1b; }
             .map-container { background: white; padding: 20px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
             .map-display { height: 400px; border-radius: 8px; }
             .next-form { text-align: center; margin-top: 20px; }
             .next-btn { background: #2563eb; color: white; padding: 15px 30px; border: none; border-radius: 8px; font-size: 16px; font-weight: bold; cursor: pointer; }
             .next-btn:hover { background: #1d4ed8; }
             @media (max-width: 768px) { .reveal-main { grid-template-columns: 1fr; } }
           "]]
         [:body
          [:div.reveal-container
           [:div.reveal-header
            [:div.result {:class (if correct? "correct" "incorrect")}
             (if correct? "✓ Correct!" "✗ Incorrect")]
            [:div.points "+" points " points"]
            (when (> (:streak game-session) 0)
              [:div.streak "🔥 Streak: " (:streak game-session)])
            [:div.score "Total Score: " (:score game-session)]]

           [:div.reveal-main
            [:div.image-result
             [:img.revealed-image {:src (:url current-image) :alt "Location revealed"}]
             [:div.answer-reveal
              [:h3 "Your Answer: " (get borough-game/borough-display-names user-borough "Unknown")]
              [:p.location-answer {:class (if correct? "borough-correct" "borough-incorrect")}
               "Correct Answer: " (get borough-game/borough-display-names correct-borough "Unknown")]]]

            [:div.map-container
             [:h3 "Location on Map:"]
             [:div#reveal-map.map-display {:data-lat (:lat current-image)
                                           :data-lng (:lng current-image)
                                           :data-borough (name correct-borough)}]]]

           [:form.next-form {:method "post" :action (str "/borough-game/" (:session-id game-session) "/next")}
            [:button.next-btn {:type "submit"}
             (if (>= (:current-stage game-session) (:total-stages game-session))
               "View Final Results 🏆"
               "Next Image →")]]]

          [:script {:src "https://unpkg.com/leaflet@1.7.1/dist/leaflet.js"}]
          [:script "
            document.addEventListener('DOMContentLoaded', function() {
              const mapElement = document.getElementById('reveal-map');
              const lat = parseFloat(mapElement.dataset.lat);
              const lng = parseFloat(mapElement.dataset.lng);
              const borough = mapElement.dataset.borough;
              
              const map = L.map('reveal-map').setView([lat, lng], 12);
              
              L.tileLayer('https://tiles.stadiamaps.com/tiles/alidade_smooth/{z}/{x}/{y}{r}.png', {
                maxZoom: 20,
                attribution: '&copy; <a href=\"https://stadiamaps.com/\">Stadia Maps</a>'
              }).addTo(map);
              
              // Borough colors
              const colors = {
                'manhattan': '#DC2626',
                'brooklyn': '#059669', 
                'queens': '#1D4ED8',
                'bronx': '#7C2D12',
                'staten-island': '#A16207'
              };
              
              // Load borough boundaries first
              fetch('/api/boroughs')
                .then(response => response.json())
                .then(data => {
                  data.features.forEach(feature => {
                    var coords = feature.geometry.coordinates[0];
                    var props = feature.properties;
                    
                    // Convert coordinates for Leaflet (swap lng/lat)
                    var leafletCoords = coords.map(coord => [coord[1], coord[0]]);
                    
                    // Highlight the correct borough
                    var isCorrectBorough = props.borough === borough;
                    
                    L.polygon(leafletCoords, {
                      color: props.color,
                      weight: isCorrectBorough ? 4 : 2,
                      opacity: isCorrectBorough ? 1.0 : 0.6,
                      fillColor: props.color,
                      fillOpacity: isCorrectBorough ? 0.3 : 0.1,
                      interactive: false
                    }).addTo(map);
                  });
                  
                  // Add location marker after polygons
                  const marker = L.circleMarker([lat, lng], {
                    radius: 15,
                    fillColor: colors[borough] || '#4B5563',
                    color: 'white',
                    weight: 4,
                    opacity: 1,
                    fillOpacity: 0.9
                  }).addTo(map);
                  
                  marker.bindPopup('<strong>' + borough.charAt(0).toUpperCase() + borough.slice(1).replace('-', ' ') + '</strong><br>📍 Image Location').openPopup();
                })
                .catch(error => {
                  console.error('Error loading borough boundaries:', error);
                  
                  // Fallback: just add the location marker
                  const marker = L.circleMarker([lat, lng], {
                    radius: 15,
                    fillColor: colors[borough] || '#4B5563',
                    color: 'white',
                    weight: 4,
                    opacity: 1,
                    fillOpacity: 0.9
                  }).addTo(map);
                  
                  marker.bindPopup('<strong>' + borough.charAt(0).toUpperCase() + borough.slice(1).replace('-', ' ') + '</strong><br>📍 Image Location').openPopup();
                });
            });
          "]])))))

(defn borough-game-results [session-id]
  "Final results page"
  (if-let [game-session (borough-game/get-game-session session-id)]
    (let [stages (:stages game-session)
          total-score (:score game-session)
          correct-answers (count (filter :correct? stages))
          total-stages (count stages)]
      (html5
       [:head
        [:meta {:charset "utf-8"}]
        [:title "Game Results"]
        [:style "
           body { margin: 0; padding: 0; font-family: Arial, sans-serif; background: #f5f5f5; }
           .results-container { max-width: 800px; margin: 0 auto; padding: 20px; }
           .results-header { background: white; padding: 30px; border-radius: 10px; margin-bottom: 30px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); text-align: center; }
           .final-score { font-size: 3em; color: #2563eb; font-weight: bold; margin-bottom: 10px; }
           .accuracy { font-size: 1.5em; color: #374151; margin-bottom: 20px; }
           .summary { background: white; padding: 20px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
           .actions { text-align: center; margin-top: 30px; }
           .action-btn { background: #2563eb; color: white; padding: 15px 30px; border: none; border-radius: 8px; font-size: 16px; font-weight: bold; cursor: pointer; margin: 0 10px; text-decoration: none; display: inline-block; }
           .action-btn:hover { background: #1d4ed8; }
         "]]
       [:body
        [:div.results-container
         [:div.results-header
          [:h1 "🏆 Game Complete!"]
          [:div.final-score total-score " points"]
          [:div.accuracy correct-answers " out of " total-stages " correct (" (int (* 100 (/ correct-answers total-stages))) "% accuracy)"]]

         [:div.summary
          [:h2 "Performance Summary"]
          [:p "You completed " total-stages " stages with a final score of " total-score " points."]
          [:p "Familiarity level: " (:user-familiarity game-session)]]

         [:div.actions
          [:a.action-btn {:href "/borough-game"} "Play Again"]
          [:a.action-btn {:href "/images-map"} "Explore NYC Map"]]]]))
    ;; No session found
    (redirect "/borough-game")))

(defroutes app-routes
  (GET "/" [] (redirect "/borough-game"))
  (GET "/images-map" [] (map-page))
  (GET "/borough-game" [] (borough-game-landing))
  (POST "/borough-game/start" request
    (let [familiarity (get-in request [:params "familiarity"] "Unknown")
          game-session (borough-game/new-game familiarity)]
      (borough-game/save-game-session! game-session)
      (redirect (str "/borough-game/" (:session-id game-session)))))
  (GET "/borough-game/:session-id" [session-id] (borough-game-page session-id))
  (GET "/borough-game/:session-id/reveal" [session-id]
    (if-let [game-session (borough-game/get-game-session session-id)]
      (borough-reveal-page game-session)
      (redirect "/borough-game")))
  (POST "/borough-game/:session-id/answer" [session-id :as request]
    (let [user-answer (or (get-in request [:params "answer"])
                          (get-in request [:form-params "answer"]))]
      (let [result (borough-game/process-answer session-id user-answer)]
        (redirect (str "/borough-game/" session-id "/reveal")))))
  (POST "/borough-game/:session-id/next" [session-id]
    (let [result (borough-game/advance-to-next-stage session-id)]
      (if (:game-over? result)
        (redirect (str "/borough-game/" session-id "/results"))
        (redirect (str "/borough-game/" session-id)))))
  (GET "/borough-game/:session-id/results" [session-id] (borough-game-results session-id))
  (GET "/api/images" []
    (-> (images-geojson)
        (json/generate-string)
        (response)
        (content-type "application/json")))
  (GET "/api/boroughs" []
    (-> (boroughs-geojson)
        (json/generate-string)
        (response)
        (content-type "application/json")))
  (route/resources "/")
  (route/not-found "Not Found"))

(def app
  (-> app-routes
      wrap-params
      wrap-keyword-params
      (wrap-defaults (-> site-defaults
                         (assoc-in [:security :anti-forgery] false)
                         (assoc-in [:params :urlencoded] true)
                         (assoc-in [:params :multipart] true)))))

(defn start-server!
  "Start the web server"
  [& {:keys [port] :or {port 3000}}]
  (when *server*
    (println "Stopping existing server...")
    (.stop *server*))
  (println (str "Starting server on port " port "..."))
  (alter-var-root #'*server*
                  (constantly (jetty/run-jetty #'app
                                               {:port port
                                                :join? false})))
  (println (str "Server running at http://localhost:" port)))

(defn stop-server!
  "Stop the web server"
  []
  (when *server*
    (println "Stopping server...")
    (.stop *server*)
    (alter-var-root #'*server* (constantly nil))
    (println "Server stopped.")))

(defn restart-server!
  "Restart the web server (useful for REPL development)"
  [& {:keys [port] :or {port 3000}}]
  (stop-server!)
  (start-server! :port port))