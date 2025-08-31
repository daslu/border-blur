(ns border-blur.web.server
  "Web server for NYC street view image visualization"
  (:require [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]

            [ring.util.response :refer [response content-type]]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

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

(defroutes app-routes
  (GET "/images-map" [] (map-page))
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
      (wrap-defaults site-defaults)))

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