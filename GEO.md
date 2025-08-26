# GIS in Clojure - Comprehensive Guide

## Overview

This document covers Geographic Information Systems (GIS) concepts and operations in Clojure using the Factual/geo library. It focuses on spatial operations relevant to the Border Blur project, including point-in-polygon testing, spatial clustering detection, and coordinate transformations.

## Core Libraries

### Factual/geo Integration
The Factual/geo library unifies five JVM geospatial libraries:
- **JTS Topology Suite** - Geometric operations and spatial predicates
- **Spatial4j** - Spatial calculations and geometry interfaces  
- **Geohash-java** - Geospatial hashing for indexing
- **Proj4j** - Coordinate reference system transformations
- **H3** - Hexagonal cell operations

### Key Namespaces
```clojure
(require '[geo.spatial :as spatial])
(require '[geo.jts :as jts]) 
(require '[geo.geohash :as geohash])
(require '[geo.crs :as crs])
(require '[geo.io :as geoio])
```

## Essential GIS Concepts for Border Blur

### 1. Coordinate Systems and Transformations

#### WGS84 vs Local Coordinates
- **WGS84 (EPSG:4326)**: Global latitude/longitude system
- **Local Projected**: More accurate for distance calculations

```clojure
;; Create coordinate system transformation
(def wgs84->local
  (crs/create-transform 
    (crs/create-crs 4326)     ; WGS84
    (crs/create-crs 2039)))   ; Israel TM Grid

;; Transform a point
(defn transform-point [lat lng]
  (let [point (jts/point lng lat)]  ; Note: JTS uses lng,lat order
    (jts/transform-geom point wgs84->local)))
```

#### Why Coordinate Transformations Matter
- Lat/lng distances are distorted (especially at higher latitudes)
- Local projected coordinates give accurate Euclidean distances
- Essential for clustering detection and spatial distribution

### 2. Point-in-Polygon Testing

#### Basic Point-in-Polygon
```clojure
(defn point-in-polygon? [lat lng polygon-coords]
  (let [point (jts/point lng lat)  ; JTS uses lng,lat order
        polygon (jts/polygon polygon-coords)]
    (spatial/intersects? point polygon)))

;; Example usage
(point-in-polygon? 32.0853 34.7818 tel-aviv-boundary)
```

#### Robust Implementation for Border Blur
```clojure
(defn classify-image-location 
  "Determine which city an image belongs to using GIS verification"
  [lat lng city-polygons]
  (first 
    (for [[city-key city-data] city-polygons
          :let [polygon (jts/polygon (:boundary city-data))]
          :when (spatial/intersects? (jts/point lng lat) polygon)]
      city-key)))

;; Usage
(classify-image-location 32.0853 34.7818 cities/cities)
;; => :tel-aviv-yafo
```

### 3. Distance Calculations

#### Great Circle Distance (Global)
```clojure
(defn great-circle-distance [lat1 lng1 lat2 lng2]
  "Distance in meters using earth's curvature"
  (spatial/distance 
    (spatial/point lng1 lat1)
    (spatial/point lng2 lat2)))
```

#### Euclidean Distance (Local Coordinates)
```clojure
(defn euclidean-distance [point1 point2]
  "Accurate distance in projected coordinates"
  (let [x1 (.getX point1) y1 (.getY point1)
        x2 (.getX point2) y2 (.getY point2)]
    (Math/sqrt (+ (* (- x2 x1) (- x2 x1))
                  (* (- y2 y1) (- y2 y1))))))
```

### 4. Spatial Clustering Detection

#### Cluster Analysis for Image Distribution
```clojure
(defn detect-clustering 
  "Identify if images are too clustered together"
  [image-locations min-distance]
  (let [transformed-points (map #(transform-point (:lat %) (:lng %)) 
                                image-locations)]
    (for [p1 transformed-points
          p2 transformed-points
          :when (not= p1 p2)
          :let [dist (euclidean-distance p1 p2)]
          :when (< dist min-distance)]
      {:point1 p1 :point2 p2 :distance dist})))

;; Example: Find images within 500m of each other
(detect-clustering image-locations 500)
```

#### Spatial Distribution Quality Score
```clojure
(defn spatial-distribution-score 
  "Rate the spatial distribution quality (0-1)"
  [image-locations target-min-distance]
  (let [clustering (detect-clustering image-locations target-min-distance)
        total-pairs (/ (* (count image-locations) 
                         (dec (count image-locations))) 2)]
    (- 1.0 (/ (count clustering) total-pairs))))
```

### 5. Geometry Operations

#### Buffer Operations
```clojure
(defn create-buffer-zone [polygon radius-meters]
  "Create a buffer zone around a polygon"
  (spatial/buffer polygon radius-meters))

;; Usage: Create 100m buffer around Tel Aviv
(def tel-aviv-buffer 
  (create-buffer-zone tel-aviv-polygon 100))
```

#### Polygon Intersection
```clojure
(defn polygon-overlap [poly1 poly2]
  "Calculate area of overlap between two polygons"
  (let [intersection (spatial/intersection poly1 poly2)]
    (if (spatial/intersects? poly1 poly2)
      (spatial/area intersection)
      0)))
```

### 6. Spatial Indexing for Performance

#### STRtree for Efficient Queries
```clojure
(defn create-spatial-index [geometries]
  "Create spatial index for fast lookup"
  (let [index (org.locationtech.jts.index.strtree.STRtree.)]
    (doseq [[id geom] geometries]
      (.insert index (.getEnvelopeInternal geom) {:id id :geometry geom}))
    (.build index)
    index))

(defn spatial-query [index query-geometry]
  "Find all geometries that intersect with query"
  (.query index (.getEnvelopeInternal query-geometry)))
```

## Border Blur Specific Applications

### 1. Image Verification Pipeline
```clojure
(defn verify-image-location 
  "Comprehensive verification of image location"
  [image-path lat lng city-boundaries]
  (let [verified-city (classify-image-location lat lng city-boundaries)
        folder-city (extract-city-from-path image-path)
        accurate? (= verified-city folder-city)]
    {:image-path image-path
     :coordinates [lng lat]
     :gis-verified-city verified-city
     :folder-indicated-city folder-city
     :verification-accurate? accurate?
     :verification-note (when-not accurate? 
                         (str "Folder indicates " folder-indicated-city 
                              " but GIS shows " verified-city))}))
```

### 2. Optimal Image Sampling
```clojure
(defn generate-well-distributed-points 
  "Generate spatially distributed sample points within city boundaries"
  [city-polygon num-points min-distance]
  (let [bounds (spatial/bounds city-polygon)]
    (loop [points []
           attempts 0]
      (if (or (>= (count points) num-points) (> attempts 10000))
        points
        (let [candidate (generate-random-point-in-polygon city-polygon)
              too-close? (some #(< (euclidean-distance candidate %) min-distance) 
                              points)]
          (if too-close?
            (recur points (inc attempts))
            (recur (conj points candidate) attempts)))))))
```

### 3. Border Proximity Analysis
```clojure
(defn calculate-border-proximity 
  "Calculate how close a point is to city boundaries"
  [lat lng city-polygon]
  (let [point (jts/point lng lat)
        boundary (spatial/boundary city-polygon)]
    (spatial/distance point boundary)))

(defn classify-difficulty-by-proximity 
  "Assign difficulty based on distance from border"
  [lat lng city-polygon]
  (let [proximity (calculate-border-proximity lat lng city-polygon)]
    (cond
      (> proximity 1000) :easy      ; >1km from border
      (> proximity 200)  :medium    ; 200m-1km from border  
      :else              :hard)))   ; <200m from border
```

## Performance Considerations

### 1. Coordinate System Choice
- Use local projected coordinates for distance calculations
- Transform once, use many times
- Cache transformed geometries

### 2. Spatial Indexing
- Build spatial indexes for large datasets
- Use STRtree for polygon lookups
- Consider geohashing for point clustering

### 3. Geometry Simplification
```clojure
(defn simplify-polygon [polygon tolerance]
  "Reduce polygon complexity for better performance"
  (spatial/simplify polygon tolerance))

;; Example: Simplify to 10m tolerance
(def simplified-boundary 
  (simplify-polygon complex-boundary 10.0))
```

## Common Patterns and Best Practices

### 1. Loading Spatial Data
```clojure
;; From EDN coordinates
(defn coords->polygon [coord-pairs]
  (jts/polygon (map (fn [[lng lat]] [lng lat]) coord-pairs)))

;; From GeoJSON
(defn load-geojson-features [file-path]
  (geoio/read-geojson (io/resource file-path)))
```

### 2. Error Handling
```clojure
(defn safe-point-in-polygon [lat lng polygon]
  (try
    (point-in-polygon? lat lng polygon)
    (catch Exception e
      (do 
        (println "Geometry error:" (.getMessage e))
        false))))
```

### 3. Coordinate Validation
```clojure
(defn valid-coordinates? [lat lng]
  (and (number? lat) (number? lng)
       (<= -90 lat 90)
       (<= -180 lng 180)))
```

## Testing GIS Operations

### Unit Testing Spatial Functions
```clojure
(deftest test-point-in-polygon
  (testing "Point-in-polygon accuracy"
    ;; Known point inside Tel Aviv
    (is (point-in-polygon? 32.0853 34.7818 tel-aviv-boundary))
    ;; Known point outside Tel Aviv  
    (is (not (point-in-polygon? 32.1500 34.9000 tel-aviv-boundary)))))

(deftest test-distance-calculation
  (testing "Distance calculation accuracy"
    (let [dist (great-circle-distance 32.0853 34.7818 32.0900 34.7900)]
      (is (< 800 dist 1200))))) ; Approximately 1km
```

### Performance Testing
```clojure
(defn benchmark-spatial-operation [operation data iterations]
  (let [start-time (System/currentTimeMillis)]
    (dotimes [_ iterations]
      (operation data))
    (let [end-time (System/currentTimeMillis)]
      (/ (- end-time start-time) iterations))))
```

## Troubleshooting Common Issues

### 1. Coordinate Order Confusion
- **JTS uses [longitude, latitude] order**
- Most geographic data uses [latitude, longitude] order
- Always verify coordinate order when creating geometries

### 2. Precision Issues
- Use appropriate tolerance values for spatial operations
- Be aware of floating-point precision limits
- Consider using BigDecimal for high-precision coordinates

### 3. Coordinate System Mismatches
- Always verify coordinate reference systems
- Transform coordinates to appropriate local systems
- Test with known reference points

## Integration with Border Blur

### Current Issues and Solutions
1. **Clustering Problem**: Use spatial distribution analysis and minimum distance constraints
2. **Misclassification**: Replace folder-based logic with GIS point-in-polygon testing  
3. **Distance Accuracy**: Transform to local coordinates for precise measurements

### Recommended Implementation
```clojure
;; Enhanced image verification pipeline
(defn enhanced-image-verification []
  (->> (get-all-image-files)
       (map extract-coordinates)
       (filter valid-coordinates?)
       (map #(verify-image-location % city-boundaries))
       (map #(assoc % :border-proximity 
                   (calculate-border-proximity (:lat %) (:lng %) 
                                             (get-city-polygon (:verified-city %))))
       (map #(assoc % :difficulty 
                   (classify-difficulty-by-proximity (:lat %) (:lng %) 
                                                   (get-city-polygon (:verified-city %)))))))
```

This comprehensive approach will resolve both clustering and classification issues while providing a foundation for more sophisticated spatial analysis.