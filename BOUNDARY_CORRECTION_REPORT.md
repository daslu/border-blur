# Boundary Correction Analysis Report

## Executive Summary

After comprehensive verification of the collected street view images, we discovered significant issues with the GIS classification system. Of 66 collected images, **only 28.8% were correctly classified**, with major boundary overlaps causing classification errors.

## Key Findings

### Original Issues (accurate-boundaries.edn)
- ✅ **Correct**: 19 images (28.8%)
- ❌ **Wrong city**: 6 images (9.1%) 
- ⚠️ **Ambiguous (multiple cities)**: 32 images (48.5%)
- 🚫 **Outside boundaries**: 9 images (13.6%)

### Major Boundary Overlaps Identified
1. **bat-yam ↔ holon**: 24 conflicts (most severe)
2. **ramat-gan ↔ bnei-brak**: 22 conflicts  
3. **givatayim ↔ tel-aviv**: 16 conflicts
4. **ramat-gan ↔ givatayim**: 6 conflicts
5. **ramat-gan ↔ tel-aviv**: 4 conflicts

## Root Cause Analysis

The boundary overlaps occur because:

1. **Overly generous boundaries**: City polygons were drawn too large, extending into neighboring territories
2. **Lack of buffer zones**: No separation between adjacent city boundaries
3. **Inaccurate real-world boundaries**: Some boundaries don't reflect actual municipal borders

## Corrective Actions Taken

### Created corrected-boundaries.edn with:
- **Reduced boundary sizes** for overlapping cities
- **Created buffer zones** between adjacent boundaries  
- **More conservative boundary definitions**

### Partial Success with Corrected Boundaries:
- **Fixed bnei-brak classification**: Point now correctly in single city
- **Fixed holon classification**: Point now correctly in single city
- **Still problematic**: bat-yam/holon and givatayim/ramat-gan overlaps persist

## Impact on Image Collection

The boundary issues explain why the manual verification found "still problems" - approximately **71.2% of collected images were misclassified** due to:

1. **Overlapping boundaries** causing ambiguous classifications
2. **Conservative filtering** in verified_collector.clj rejecting ambiguous cases
3. **Gap-filled boundaries** causing some images to fall outside all cities

## Recommended Next Steps

### Option 1: Use Real Municipal Boundary Data
- Source official municipal boundary shapefiles from Israeli mapping authorities
- Convert to appropriate coordinate format for poly-contains?
- Most accurate but requires external data sourcing

### Option 2: Continue Manual Refinement
- Iteratively adjust boundaries based on verification results
- Add more specific buffer zones between problem city pairs
- Test against collected images until acceptable accuracy achieved

### Option 3: Implement Coordinate-Based Classification  
- Use distance-to-city-center with learned boundaries
- Train boundary thresholds based on verified image locations
- More flexible than polygon-based approach

### Option 4: Hybrid Approach
- Use polygon classification for clear cases
- Fall back to distance-based classification for border areas
- Add confidence scoring system

## Technical Implementation Notes

### Current Verification System
- Created comprehensive verification scripts (`full_verification.clj`, `test_corrected_boundaries.clj`)
- Implemented proper poly-contains? format with corrected coordinate order
- Built detailed issue tracking and reporting

### File Structure
```
/resources/cities/
├── israeli-cities.edn          # Original simple boundaries  
├── accurate-boundaries.edn     # Hand-drawn boundaries (problematic)
├── corrected-boundaries.edn    # Refined boundaries (partial fix)
└── [future] official-boundaries.edn  # Real municipal data
```

### Verification Scripts
- `full_verification.clj` - Tests all 66 collected images
- `test_corrected_boundaries.clj` - Tests specific problematic points
- Both provide detailed classification reports

## Performance Metrics

### Before Correction (accurate-boundaries.edn):
- **Accuracy**: 28.8%
- **Major overlaps**: 3 city pairs with 16+ conflicts each
- **Images outside boundaries**: 13.6%

### After Correction (corrected-boundaries.edn):  
- **Partial improvement**: 2/6 test cases fixed
- **Remaining overlaps**: bat-yam/holon, givatayim/ramat-gan
- **Still needs refinement**: Some points remain outside boundaries

## Conclusion

The GIS classification system needs boundary refinement before reliable image collection. The verification system successfully identified issues and the corrected boundaries show improvement, but additional iteration is needed for production use.

**Current Status**: Boundary correction system implemented and partially working. Ready for next iteration of refinement or alternative approach implementation.