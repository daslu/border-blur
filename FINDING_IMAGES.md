# Finding Images for Border Blur

## Overview
Border Blur requires street-view images from city border areas where visual differences are subtle. Players must identify if two images are from the same or different cities. This document outlines our strategy adapted from the places1 project's successful approaches.

## Core Requirements

### Game-Specific Needs
- **Border areas**: Focus on city boundaries, not neighborhoods
- **Ambiguous locations**: Areas where city identity isn't obvious
- **Paired images**: Same/different city combinations
- **Progressive difficulty**: Easy → Medium → Hard based on visual similarity

### Target Border Areas
1. **Tel Aviv / Ramat Gan**: Ayalon Highway area, Diamond Exchange district
2. **Tel Aviv / Holon**: Wolfson area, industrial zones
3. **Tel Aviv / Givatayim**: Nahalat Yitzhak Cemetery area
4. **Tel Aviv / Bnei Brak**: Jabotinsky Road corridor

## Proven Approach from places1

### 1. POI Discovery via OpenStreetMap Overpass API ✅
Instead of neighborhoods, query POIs near city borders:

```clojure
(defn border-overpass-query [border-lat border-lng radius]
  (format "[out:json][timeout:25];
           (node[\"amenity\"](around:%d,%.6f,%.6f);
            node[\"shop\"](around:%d,%.6f,%.6f);
            node[\"highway\"~\"bus_stop\"](around:%d,%.6f,%.6f););
           out geom;"
          radius border-lat border-lng
          radius border-lat border-lng
          radius border-lat border-lng))
```

### 2. Border Point Generation
Using existing `border-blur.gis.boundaries`:

```clojure
(defn get-border-search-points
  "Generate search points along city borders"
  [city-a city-b]
  (let [border-points (boundaries/find-game-worthy-points city-a city-b 500)
        ;; Group by difficulty for progressive gameplay
        by-difficulty (group-by :difficulty border-points)]
    {:easy (take 10 (:easy by-difficulty))
     :medium (take 15 (:medium by-difficulty))
     :hard (take 10 (:hard by-difficulty))}))
```

### 3. Mapillary API Integration (Proven Success)

#### Setup
1. Get token from https://www.mapillary.com/dashboard/developers
2. Store in `.env` or `resources/api-keys.edn`:
```clojure
{:mapillary {:token "MLY|your-token-here"}}
```

#### API Pattern for Border Areas
```clojure
(defn fetch-border-images [border-point token]
  (let [bbox (calculate-bbox (:lat border-point) (:lng border-point) 100) ; 100m radius
        url "https://graph.mapillary.com/images"
        params {:access_token token
                :bbox (format "%.6f,%.6f,%.6f,%.6f" 
                             (:west bbox) (:south bbox) 
                             (:east bbox) (:north bbox))
                :limit 5
                :fields "id,thumb_original_url,geometry,captured_at"}]
    (http/get url {:query-params params :as :json})))
```

## Implementation Plan

### Phase 1: Border Coordinate Discovery
```clojure
(ns border-blur.images.border-finder
  (:require [border-blur.gis.boundaries :as boundaries]
            [border-blur.gis.cities :as cities]
            [clojure.java.shell :as shell]
            [clojure.data.json :as json]))

(defn discover-border-pois
  "Find POIs near city borders using Overpass API"
  [border-points radius]
  (for [point border-points]
    (let [query (border-overpass-query (:lat point) (:lng point) radius)
          result (shell/sh "curl" "-s" "-X" "POST"
                          "https://overpass-api.de/api/interpreter"
                          "-d" query)]
      (when (= 0 (:exit result))
        (json/read-str (:out result) :key-fn keyword)))))

(defn enrich-border-points
  "Combine algorithmic border detection with real POI discovery"
  [city-a-name city-b-name]
  (let [city-a (cities/get-city cities/cities city-a-name)
        city-b (cities/get-city cities/cities city-b-name)
        ;; Get border points from existing algorithm
        border-points (boundaries/find-game-worthy-points city-a city-b 500)
        ;; Enhance with real POIs
        enriched (map #(assoc % :pois (discover-border-pois [%] 200))
                     border-points)]
    enriched))
```

### Phase 2: Smart Image Pair Generation
```clojure
(defn generate-game-pairs
  "Create same/different city pairs based on difficulty"
  [border-images stage-number total-stages]
  (let [difficulty (cond 
                    (< stage-number 5) :easy
                    (< stage-number 15) :medium
                    :else :hard)
        
        ;; Filter images by difficulty
        candidate-images (filter #(= (:difficulty %) difficulty) border-images)
        
        ;; 60% different cities, 40% same city (from game logic)
        pair-type (if (< (rand) 0.6) :different :same)]
    
    (if (= pair-type :different)
      ;; Select from opposite sides of border
      (select-cross-border-pair candidate-images)
      ;; Select from same city but different locations
      (select-same-city-pair candidate-images))))
```

### Phase 3: Spatial Diversity (Adapted from places1)
```clojure
(defn ensure-spatial-diversity
  "Ensure minimum 200m separation between image locations"
  [border-points min-distance-m]
  (loop [selected [] 
         remaining (sort-by :priority > border-points)]
    (if (empty? remaining)
      selected
      (let [candidate (first remaining)
            far-enough? (every? #(> (haversine-distance candidate %) min-distance-m)
                               selected)]
        (if far-enough?
          (recur (conj selected candidate) (rest remaining))
          (recur selected (rest remaining)))))))
```

## Key Differences from places1

### Focus on Borders vs Neighborhoods
- **places1**: POIs within neighborhood boundaries
- **Border Blur**: POIs straddling city boundaries

### Image Pair Logic
- **places1**: Correct (in neighborhood) vs Wrong (outside)
- **Border Blur**: Same city pair vs Different city pair

### Difficulty Classification
- **places1**: Based on landmark recognizability
- **Border Blur**: Based on distance from border + visual ambiguity

## Testing Workflow

### 1. Test Border Detection
```clojure
(require '[border-blur.gis.boundaries :as b])
(require '[border-blur.gis.cities :as c])

(def tel-aviv (c/get-city c/cities :tel-aviv))
(def ramat-gan (c/get-city c/cities :ramat-gan))
(def border-points (b/find-game-worthy-points tel-aviv ramat-gan 500))

;; Verify we get points with difficulty classification
(map :difficulty border-points) ; => (:easy :medium :hard ...)
```

### 2. Test POI Discovery
```clojure
(require '[clojure.java.shell :as shell])
(require '[clojure.data.json :as json])

;; Test Overpass API for border area
(def test-point {:lat 32.0680 :lng 34.8245}) ; Tel Aviv/Ramat Gan border
(def query (border-overpass-query (:lat test-point) (:lng test-point) 200))
(def result (shell/sh "curl" "-s" "-X" "POST"
                      "https://overpass-api.de/api/interpreter"
                      "-d" query))
(def pois (json/read-str (:out result) :key-fn keyword))
(count (:elements pois)) ; Should return POI count
```

### 3. Test Mapillary Integration
```clojure
;; With token from user
(def token "MLY|...")
(def test-bbox {:west 34.823 :south 32.067 :east 34.826 :north 32.069})
(def images (mapillary-api-call token test-bbox 5))
```

## Expected Results

### Coordinate Distribution
- **Border hotspots**: 10-20 per city pair
- **POI enhancement**: 50+ real locations per border
- **After filtering**: 20-30 spatially diverse points

### Image Collection
- **Per border point**: 3-5 street-view images
- **Total per city pair**: 60-150 images
- **After curation**: 20-40 high-quality pairs

## Storage Structure
```
resources/public/images/
├── border-cache/
│   ├── tel-aviv-ramat-gan/
│   │   ├── easy/
│   │   ├── medium/
│   │   └── hard/
│   ├── tel-aviv-holon/
│   └── metadata.edn
└── api-responses/
    └── mapillary-cache.edn
```

## Next Steps

1. **Get Mapillary Token** - Need from user or register account
2. **Create border-finder namespace** - Implement border POI discovery
3. **Test with Tel Aviv/Ramat Gan** - Most ambiguous border
4. **Build caching layer** - Avoid repeated API calls
5. **Integrate with game** - Replace placeholder images

## Lessons from places1

### What Worked
✅ **Mapillary API** - Excellent coverage in Israeli cities
✅ **POI Discovery** - Real locations more interesting than coordinates
✅ **Spatial Diversity** - 200m minimum separation prevents clustering
✅ **Aspect Ratio Filtering** - Remove panoramic images

### What to Avoid
❌ **Manual coordinates** - Too clustered, not representative
❌ **Large search radius** - Gets irrelevant images
❌ **Single API dependency** - Have fallback options

## Quick Reference Commands

```bash
# Start REPL for testing
clojure -A:nrepl

# In REPL: Load and test
(require '[border-blur.gis.boundaries :as b])
(require '[border-blur.gis.cities :as c])
(def border-points (b/find-game-worthy-points 
                     (c/get-city c/cities :tel-aviv)
                     (c/get-city c/cities :ramat-gan) 
                     500))

# Test POI discovery
(require '[clojure.java.shell :as shell])
(def query "[out:json];node[\"shop\"](around:200,32.068,34.824);out;")
(def result (shell/sh "curl" "-s" "https://overpass-api.de/api/interpreter" 
                     "-d" query))
```

---

*This document adapts proven strategies from places1 for Border Blur's unique border-detection gameplay.*