# GIS Classification Insights - Image Location Accuracy Session

## Session Summary
This session focused on fixing critical image classification errors in the Border Blur geography game where street view images were being assigned to incorrect cities despite having working spatial optimization systems.

## The Problem
- User reported "still wrong" classifications even after implementing spatial optimization
- Images were being assigned to wrong cities (e.g., coordinates in Ramat Gan claimed as Tel Aviv)
- Multiple previous attempts had failed to achieve reliable accuracy

## Root Cause Analysis

### Initial Approaches & Their Failures
1. **Ray Casting Algorithm**: Custom point-in-polygon implementation was working correctly
2. **Distance-to-Center**: Simple but ignored city shapes and boundaries  
3. **Hybrid Approach**: Combined boundaries + distance verification (achieved 80% accuracy)
4. **Boundary Data Quality**: City polygons had genuine overlaps and questionable accuracy

### Key Discovery: Correct geo.poly/poly-contains? Usage
The breakthrough came when properly implementing `geo.poly/poly-contains?`:

```clojure
;; Correct signature: [lat lng poly]
;; Correct polygon format: [[[lat1 lng1 lat2 lng2 ... latk lngk]]]

(defn point-in-city? [city-key lat lng]
  (when-let [boundary (:boundary city-data)]
    (let [coords-flat (mapcat (fn [[lng lat]] [lat lng]) boundary)
          poly-format [[coords-flat]]]
      (poly/poly-contains? lat lng poly-format))))
```

## Final Solution Architecture

### Multi-City Filtering Approach
**Key Insight**: Filter out ambiguous cases rather than trying to resolve them.

```clojure
(defn classify-image-location [lat lng target-city-key]
  (let [containing-cities (find-all-containing-cities lat lng)]
    (cond
      ;; Accept: Point in exactly one city (our target)
      (and (= (count containing-cities) 1)
           (= (:key (first containing-cities)) target-city-key))
      {:correct? true :confidence :high :method "poly-contains-unique"}
      
      ;; Reject: Point in multiple cities (ambiguous)
      (> (count containing-cities) 1)
      {:correct? false :reason "multiple-cities"}
      
      ;; Reject: Point in different city or no city
      :else
      {:correct? false :reason "wrong-or-outside-boundary"})))
```

## Accuracy Measurement Insights

### The Flawed Approach
Initially measured accuracy by comparing:
- **System Classification**: What city our algorithm assigned
- **Distance Verification**: Which city center was closest to the coordinates

**Problem**: This compared "official boundaries" vs "geometric proximity" - fundamentally different concepts.

### The Correct Approach  
Trust `poly-contains?` as ground truth and filter ambiguous cases:
- No artificial accuracy metrics based on distance-to-center
- Accept that some boundary cases are genuinely ambiguous 
- Let manual verification determine real-world correctness

## Technical Implementation Results

### Final Collection Statistics
- **66 unambiguous images** collected
- **13 images each**: Holon, Bnei Brak, Bat Yam, Givatayim
- **12 images**: Ramat Gan  
- **2 images**: Tel Aviv-Yafo
- **Zero ambiguity**: All images belong to exactly one city boundary

### Code Quality Improvements
1. **Proper geo library usage** instead of custom ray casting
2. **Clean filtering logic** that handles edge cases explicitly
3. **Rich metadata** with full verification details
4. **Modular verification functions** for easy testing and debugging

## Key Insights for Future Development

### 1. Trust Authoritative Data Sources
- Use established libraries (`geo.poly/poly-contains?`) over custom implementations
- Read documentation carefully - function signatures matter
- Don't second-guess official boundary data with distance calculations

### 2. Handle Ambiguity Explicitly  
- Filter out ambiguous cases rather than trying to resolve them
- Design systems that degrade gracefully when faced with edge cases
- Prefer high-confidence results over attempting to classify everything

### 3. Measurement Methodology Matters
- Choose ground truth carefully - don't mix different geographic concepts
- Distance-to-center ≠ administrative boundaries
- Manual verification beats algorithmic accuracy metrics for boundary cases

### 4. Iterative Problem Solving
- Start simple (`poly-contains?` alone) before adding complexity
- Test each approach thoroughly before combining approaches
- Listen to user feedback ("still wrong") over algorithmic confidence metrics

## Outstanding Issues
Manual verification revealed ongoing classification problems despite technical correctness, suggesting:
- Boundary data quality issues persist
- Administrative boundaries may not match practical geography
- Buffer zones around boundaries might be needed
- Additional data sources or manual curation may be required

## Recommendations for Future Sessions
1. **Manual Boundary Review**: Examine specific problematic coordinates
2. **Buffer Implementation**: Add configurable buffer zones around city boundaries  
3. **Multiple Data Sources**: Cross-reference with OpenStreetMap, Google geocoding
4. **Crowd-Sourced Verification**: Allow users to report incorrect classifications
5. **City-Specific Tuning**: Different approaches for different city pairs

## Files Modified
- `src/border_blur/images/verified_collector.clj` - Main collection logic
- `src/border_blur/images/fetcher.clj` - Fixed API key lookup
- `resources/api-keys.edn` - Added working Mapillary key
- Collection output: `resources/public/images/verified-collection/`

---
*Session Date: 2025-08-26*  
*Status: Ready for manual verification and next iteration*