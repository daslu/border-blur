# Diversity Sampling Plan for Border Blur

## Current Diversity Problems

### Analysis of Existing Sampling
Our current manual coordinate selection has several issues:

**Spatial Clustering:**
- Min distance between points: 0.73 km (too close)
- Max distance between points: 3.92 km (inconsistent)
- Average distance: 2.19 km (uneven distribution)
- Total coverage: ~4km × 4km (too small for Tel Aviv)

**Coverage Issues:**
- **Missing peripheral areas**: No samples from north/south Tel Aviv
- **Central bias**: Most points clustered in city center
- **Border gaps**: Missing systematic border area coverage
- **Arbitrary selection**: Hand-picked coordinates miss systematic patterns

**Impact on Border Blur:**
- **Biased gameplay**: Players see similar urban environments
- **Limited challenge**: Missing diverse architectural styles
- **Poor border detection**: Insufficient coverage of actual city boundaries
- **Repetitive imagery**: Similar street types and visual patterns

## Proposed Diversity Strategy

### 1. Multi-Scale Spatial Sampling

#### City-Wide Coverage (Scale 1: ~10km radius)
```
Objective: Cover entire metropolitan area systematically
Method: Stratified grid sampling with random jitter
Target: 20-30 points across full Tel Aviv region
```

#### Border-Focused Sampling (Scale 2: ~2km from borders) 
```
Objective: Dense sampling near city boundaries  
Method: Distance-weighted sampling from border polygons
Target: 40-50 points within 500m of borders
```

#### Neighborhood Diversity (Scale 3: ~500m spacing)
```
Objective: Capture local urban variety within areas
Method: Poisson disk sampling for even distribution
Target: 10-15 points per major neighborhood
```

### 2. Algorithmic Sampling Methods

#### A. Poisson Disk Sampling ⭐ **Primary Method**
- **Purpose**: Guarantee minimum distance between points
- **Benefits**: Even distribution, no clustering
- **Implementation**: Using Fastmath3 random generators
- **Parameters**: 
  - Minimum distance: 800m (prevents clustering)
  - Maximum attempts: 30 per point (ensures coverage)

#### B. Stratified Grid with Jitter
- **Purpose**: Systematic coverage with randomness
- **Benefits**: Predictable coverage, controlled variance
- **Implementation**: Grid + Gaussian jitter using Fastmath3
- **Parameters**:
  - Grid spacing: 1.5km × 1.5km
  - Jitter: ±300m (prevents rigid patterns)

#### C. Distance-Weighted Border Sampling
- **Purpose**: Focus on city boundary areas
- **Benefits**: Emphasizes border detection gameplay
- **Implementation**: Weight by distance to border polygons
- **Parameters**:
  - Weight function: exp(-distance/500m)
  - Sample density: Inverse distance to borders

#### D. Blue Noise Sampling
- **Purpose**: Natural, visually pleasing distribution
- **Benefits**: Human perception optimized
- **Implementation**: Spectral analysis for optimal spacing
- **Use case**: Final point refinement

### 3. Diversity Metrics & Constraints

#### Spatial Diversity Metrics
```clojure
;; Minimum pairwise distance constraint
(defn min-distance-constraint [points min-dist]
  (every? #(>= % min-dist) (pairwise-distances points)))

;; Coverage area metric  
(defn coverage-area [points]
  (area (convex-hull points)))

;; Distribution uniformity (variance of distances)
(defn uniformity-score [points]
  (/ (stddev pairwise-distances) (mean pairwise-distances)))
```

#### Urban Environment Diversity
- **Architectural eras**: Ancient, Modern, Contemporary  
- **Density levels**: High-rise, Mid-rise, Low-rise
- **Land use types**: Residential, Commercial, Mixed, Industrial
- **Infrastructure**: Major roads, Railways, Parks, Waterfront

### 4. Implementation Strategy Using Fastmath3

#### Phase 1: City Boundary Analysis
```clojure
(ns border-blur.sampling.spatial-diversity
  (:require [fastmath.core :as fm]
            [fastmath.random :as fr]
            [fastmath.vector :as fv]
            [fastmath.stats :as fs]))

(defn analyze-city-bounds [city-polygon]
  "Extract bounding box and key geometric properties"
  (let [points (polygon-vertices city-polygon)
        lats (map :lat points)
        lngs (map :lng points)]
    {:bounds {:min-lat (apply min lats)
              :max-lat (apply max lats)  
              :min-lng (apply min lngs)
              :max-lng (apply max lngs)}
     :area (polygon-area city-polygon)
     :perimeter (polygon-perimeter city-polygon)}))
```

#### Phase 2: Poisson Disk Sampling Implementation
```clojure
(defn poisson-disk-sampling [bounds min-distance max-points]
  "Generate evenly spaced points using Poisson disk sampling"
  (let [rng (fr/rng :mersenne)
        cell-size (/ min-distance (Math/sqrt 2))
        active-points (atom [])
        result-points (atom [])]
    
    ;; Dart throwing algorithm with spatial hashing
    (loop [attempts 0]
      (when (and (< attempts 10000) 
                 (< (count @result-points) max-points))
        (let [candidate (generate-random-point bounds rng)]
          (if (valid-point? candidate @result-points min-distance)
            (do (swap! result-points conj candidate)
                (swap! active-points conj candidate))
            (recur (inc attempts))))))
    
    @result-points))
```

#### Phase 3: Multi-Objective Optimization
```clojure
(defn optimize-point-set [initial-points constraints]
  "Optimize point distribution using simulated annealing"
  (let [rng (fr/rng :mersenne)]
    (loop [current-points initial-points
           temperature 1000.0
           iterations 0]
      
      (if (or (< temperature 0.1) (> iterations 5000))
        current-points
        (let [candidate (perturb-points current-points rng)
              current-score (diversity-score current-points constraints)
              candidate-score (diversity-score candidate constraints)
              accept? (or (> candidate-score current-score)
                         (< (fr/drandom rng) 
                            (Math/exp (/ (- candidate-score current-score) 
                                       temperature))))]
          
          (recur (if accept? candidate current-points)
                 (* temperature 0.995)
                 (inc iterations)))))))
```

### 5. Validation & Quality Metrics

#### Diversity Quality Score (0-100)
```
Spatial Distribution (40 points):
- Minimum distance compliance: 15 points
- Coverage area efficiency: 15 points  
- Distribution uniformity: 10 points

Urban Environment Variety (30 points):
- Architectural diversity: 10 points
- Density level coverage: 10 points
- Land use type variety: 10 points

Border Game Relevance (30 points):
- Border proximity coverage: 15 points
- Cross-city sampling balance: 10 points
- Visual ambiguity potential: 5 points
```

#### Testing Protocol
1. **Generate point sets** using each algorithm
2. **Calculate diversity scores** for comparison
3. **Visual validation** via mapping plots
4. **Image collection test** with sample coordinates
5. **Gameplay simulation** to test challenge level

### 6. Implementation Phases

#### Phase 1: Foundation (Week 1)
- [x] Analyze current diversity issues
- [ ] Implement basic Poisson disk sampling
- [ ] Create diversity scoring functions
- [ ] Test with Tel Aviv boundary data

#### Phase 2: Advanced Algorithms (Week 2)
- [ ] Implement stratified grid sampling
- [ ] Add distance-weighted border sampling
- [ ] Multi-objective optimization integration
- [ ] Cross-validation testing

#### Phase 3: Integration & Validation (Week 3)  
- [ ] Integration with Border Blur pipeline
- [ ] Large-scale point generation testing
- [ ] Image collection validation runs
- [ ] Gameplay quality assessment

### 7. Expected Outcomes

#### Quantitative Improvements
- **Minimum distance**: 800m+ (vs current 730m)
- **Coverage area**: 15km × 12km (vs current 4km × 4km)  
- **Point count**: 100+ diverse locations (vs current 10-20)
- **Uniformity score**: <0.3 (well-distributed)

#### Qualitative Improvements
- **Geographic coverage**: Full metropolitan area
- **Urban variety**: All neighborhood types represented
- **Border focus**: Systematic boundary area sampling
- **Visual diversity**: Different architectural styles, eras, densities

#### Border Blur Game Benefits
- **Increased challenge**: More diverse visual environments
- **Better border detection**: Actual boundary area coverage
- **Reduced repetition**: Systematic variety in imagery
- **Scalable collection**: Algorithm works for any city

---

**Next Steps**: Implement Poisson disk sampling using Fastmath3 and validate with Tel Aviv boundary data.