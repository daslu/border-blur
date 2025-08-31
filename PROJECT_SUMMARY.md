# Project Summary: NYC Street View Collection System

## Overview
A Clojure-based system for collecting street view images uniformly distributed across New York City's five boroughs and classifying them by geographic location. The system uses the Mapillary API for high-quality street imagery and OpenStreetMap data for borough boundary classification, with a complete web visualization interface featuring accurate borough polygons and corrected point classifications.

## Key Features
- **Uniform Grid Sampling**: Creates configurable grids (10×10 to 20×20) across NYC's geographic bounds
- **Quality Filtering**: Multi-criteria image quality assessment excluding panoramic and low-resolution images  
- **Borough Classification**: Point-in-polygon geometric analysis using JTS spatial libraries with corrected coordinate handling
- **API Integration**: Mapillary API with rate limiting, error handling, and authentication
- **Web Visualization**: Interactive Leaflet.js map showing color-coded borough classifications with proper polygon boundaries
- **License Compliance**: CC BY-SA 4.0 compliant for public website usage

## Project Structure

### Core Namespaces
```
src/border_blur/
├── core.clj                    # Main orchestrator and CLI interface
├── images/
│   └── collector.clj          # Mapillary API integration and image collection
├── boroughs/
│   ├── fetcher.clj           # OpenStreetMap boundary data fetching with improved way chaining
│   └── classifier.clj        # Geographic point-in-polygon classification with fixed coordinate handling
└── web/
    └── server.clj            # Ring/Compojure web server with map visualization and borough polygons
```

### Key Files
- **`deps.edn`**: Project dependencies and build configuration
- **`run_collection.clj`**: Simple collection script for testing
- **`.env`**: Mapillary API token configuration
- **`resources/boroughs/nyc-boroughs.edn`**: Cached NYC borough boundary data with corrected OSM IDs
- **`data/nyc-images.json`**: Collected image dataset with corrected borough classifications

### Documentation
- **`CLAUDE.md`**: Development workflow and commands
- **`NYC_STREET_VIEW_COLLECTION_METHOD.md`**: Detailed methodology documentation
- **`MAPILLARY_LICENSING_GUIDE.md`**: Legal compliance and attribution requirements

## Dependencies

### Core Libraries
- **`cheshire/cheshire` 5.12.0**: JSON parsing and generation
- **`clj-http/clj-http` 3.12.3**: HTTP client for API calls
- **`factual/geo` 3.0.1**: Geographic computations and spatial operations
- **`org.clojure/data.json` 2.5.0**: Additional JSON utilities

### Web Framework
- **`ring/ring-core` 1.12.1**: HTTP server foundation
- **`ring/ring-jetty-adapter` 1.12.1**: Jetty web server integration
- **`ring/ring-defaults` 0.5.0**: Security and middleware defaults
- **`ring/ring-devel` 1.12.1**: Development tools and reloading
- **`compojure/compojure` 1.7.1**: Routing library
- **`hiccup/hiccup` 1.0.5**: HTML generation

### Data Science
- **`org.scicloj/noj` 2-beta18**: Mathematical operations and data analysis

## Available Tools & APIs

### Image Collection
```clojure
;; Collect images with uniform grid sampling
(collect-nyc-images :grid-size 10 :max-images-per-point 2)

;; Quality filtering function
(is-quality-image? image-data) ; Returns boolean based on resolution, age, panoramic detection
```

### Borough Boundary Management  
```clojure
;; Fetch boundaries from OpenStreetMap with improved way chaining
(fetch-and-save-boroughs) ; Downloads and caches NYC borough polygons with corrected OSM IDs

;; Load cached boundary data
(load-borough-data) ; Returns borough boundary coordinates with proper polygon formation
```

### Geographic Classification
```clojure
;; Classify point by borough
(classify-point lat lng boroughs) ; Returns borough keyword or :unknown

;; Classification with confidence scoring
(classify-with-confidence lat lng boroughs) 
; Returns {:borough :manhattan :confidence :high :distance 0}

;; Classify entire image collection
(classify-images images boroughs) ; Returns images with :borough and :classification-confidence
```

### Web Server (REPL-Friendly)
```clojure
;; Start/stop/restart server for development
(require '[border-blur.web.server :as server])
(server/start-server! :port 3000)
(server/stop-server!)
(server/restart-server! :port 3000)

;; Get GeoJSON data for API
(server/images-geojson) ; Returns GeoJSON FeatureCollection with corrected colors
(server/boroughs-geojson) ; Returns borough boundary polygons for visualization
(server/load-image-data) ; Returns raw image data with classifications
```

### CLI Commands
```bash
# Test collection (5×5 grid, ~25 images)
clj -M:run test

# Full collection (15×15 grid, ~450 images)  
clj -M:run collect

# Custom grid size
clj -M:run collect 20  # 20×20 grid

# Fetch borough boundaries only
clj -M:run fetch-boroughs

# Start web visualization server
clj -M:run web [port]  # Default port 3000

# Simple test script
clj run_collection.clj
```

## Architecture & Data Flow

### Collection Pipeline
1. **Grid Generation** → Geographic coordinate pairs across NYC bounding box
2. **API Fetching** → Mapillary image metadata retrieval per grid point
3. **Quality Filtering** → Multi-criteria assessment (resolution, age, panoramic detection)
4. **Borough Classification** → JTS point-in-polygon analysis with corrected coordinate handling
5. **Data Storage** → JSON output with geographic metadata and borough assignments

### Geographic Classification (Fixed)
1. **Boundary Loading** → OSM polygon data via Overpass API with corrected relation IDs
2. **Polygon Creation** → JTS geometry objects with proper way chaining and coordinate ordering
3. **Point Testing** → Spatial intersection analysis with corrected coordinate system
4. **Buffer Zones** → Distance-based fallback for boundary edge cases

### Web Visualization (Enhanced)
1. **Server Setup** → Ring/Compojure with Jetty adapter
2. **Data API** → GeoJSON endpoints for both images (`/api/images`) and boroughs (`/api/boroughs`)
3. **Map Interface** → Leaflet.js with Stadia AlidadeSmooth tiles and borough polygon overlays
4. **Interactive Features** → Color-coded markers, borough boundary polygons, popups, legend

### Key Design Patterns
- **Keyword-based Configuration**: Flexible parameter passing with defaults
- **Error-first Design**: Comprehensive error handling and graceful degradation
- **Data-driven Architecture**: EDN configuration files for boundaries and settings
- **Functional Composition**: Pure functions with minimal side effects
- **REPL-Friendly Development**: Hot-reloadable web server functions
- **Multi-component Boundary Handling**: Selects largest connected boundary component for complex geometries

## Configuration & Environment

### API Authentication
```bash
# .env file
MAPILLARY_TOKEN=MLY|your-token-here
```

### NYC Geographic Bounds
- **Longitude**: -74.26° to -73.70° (West to East)
- **Latitude**: 40.49° to 40.92° (South to North)
- **Search Radius**: ~222 meters per grid point

### Borough OSM IDs (Verified & Corrected)
- **Manhattan**: 2552485 (New York County) - verified working
- **Brooklyn**: 369518 (Kings County) - verified working
- **Queens**: 369519 (Queens County) - corrected from 2552484
- **Bronx**: 2552450 (Bronx County) - corrected from 2552486
- **Staten Island**: 962876 (Richmond County) - corrected from 369519

### Quality Filtering Criteria
- **Resolution**: Minimum 800×600 pixels
- **Age Preference**: Images within 5 years (scoring bonus for <1 year)
- **Panoramic Exclusion**: Multiple detection methods (API fields, aspect ratio, URL patterns)
- **Acceptance Threshold**: ≥70 points out of 100 total scoring

## Development Workflow

### Getting Started
```bash
# Start development REPL
clj -M:nrepl  # Port 7888

# Run tests
clj -M:test

# Interactive development with hot reload
clj -M:dev
```

### Web Development Workflow
```bash
# Start REPL and web server
clj -M:nrepl
# In REPL:
(require '[border-blur.web.server :as server])
(server/start-server! :port 3000)

# Make changes to server.clj, then:
(server/restart-server!)
```

### Testing & Debugging
```bash
# Small test collection
clj -M:run test

# Check boundary data
clj -M:run fetch-boroughs

# Start web interface
clj -M:run web

# Validate collected data
head -20 data/nyc-images.json
```

### Data Validation
- Geographic coordinates within NYC bounds
- Image quality metrics logged during collection
- Borough classification confidence scoring with corrected algorithm
- API rate limiting and error handling

## Web Interface Features

### Interactive Map (Enhanced)
- **Base Tiles**: Stadia AlidadeSmooth for clean, professional appearance
- **Borough Polygons**: Color-coded boundary overlays with proper geometry (10% opacity)
- **Markers**: Color-coded circle markers by borough classification overlaying polygons
- **Popups**: Image metadata, capture date, and Mapillary links
- **Legend**: Borough color key with classification counts matching actual point colors

### Borough Color Scheme (Corrected)
- **Manhattan**: #DC2626 (Dark red)
- **Brooklyn**: #059669 (Dark green)  
- **Queens**: #1D4ED8 (Dark blue)
- **Bronx**: #7C2D12 (Dark brown)
- **Staten Island**: #A16207 (Dark amber)
- **Unclassified**: #4B5563 (Dark grey)

### API Endpoints
- **`/`**: Main map visualization page
- **`/api/images`**: GeoJSON FeatureCollection of all images with corrected borough data
- **`/api/boroughs`**: GeoJSON FeatureCollection of borough boundary polygons

## Extension Points

### Scalability Enhancements
- **Multi-API Integration**: Add Google Street View, Bing Streetside APIs
- **Caching Layer**: Redis/database integration for large collections
- **Parallel Processing**: Concurrent API calls with threading

### Geographic Improvements  
- **Land Mask Integration**: Exclude water bodies from grid sampling
- **Density Weighting**: Population-based grid adjustment
- **Temporal Filtering**: Specific time period or season selection
- **Full-Resolution Boundaries**: Use non-simplified polygons for edge cases

### Classification Enhancements
- **Performance Optimization**: Pre-compute and cache JTS polygons
- **Machine Learning**: Automated image content classification
- **Confidence Tuning**: Dynamic buffer zone adjustment
- **Multi-level Geography**: Neighborhood, zip code, census tract classification

### Web Interface Enhancements
- **Real-time Collection**: Live progress monitoring during collection
- **Image Annotation**: Crowdsourced labeling interface
- **Filtering Controls**: Filter by borough, confidence, date range
- **Export Features**: Download filtered datasets
- **Authentication**: User management for data contributions

## License & Compliance
- **Source Code**: Open development with Clojure
- **Image Data**: Mapillary CC BY-SA 4.0 license
- **Attribution Required**: Mapillary logo + link, CC license reference
- **Public Website Safe**: Full compliance for research and educational use

## Performance Metrics
- **Test Collection**: 100 images from 10×10 grid in ~3-5 minutes
- **API Rate**: ~2-3 seconds per grid point including quality filtering
- **Success Rate**: 100% grid point coverage achieved
- **Image Quality**: 1920×1080 to 4032×3024 resolution range
- **Time Coverage**: 2017-2020 capture dates in test dataset
- **Boundary Reconstruction**: Proper way chaining eliminates diagonal line artifacts
- **Polygon Complexity**: 58-468 points per borough boundary with multi-component handling

## Current Status
✅ **Core Collection System**: Fully functional uniform image collection  
✅ **Quality Filtering**: Multi-criteria assessment working  
✅ **API Integration**: Mapillary integration with rate limiting  
✅ **Borough Boundaries**: Complete OSM data with corrected relation IDs and proper way chaining
✅ **Classification System**: Fixed coordinate handling and re-classified all images
✅ **Web Visualization**: Interactive map with accurate borough polygons and corrected point colors
✅ **REPL Development**: Hot-reloadable server functions for development
✅ **Boundary Reconstruction**: Proper polygon formation without diagonal line artifacts
✅ **Color Mapping**: Legend colors match actual point positions on map

### Current Classification Results (100 images - Corrected)
- **Unclassified**: 56 images (56% - reduced from 66%)
- **Brooklyn**: 14 images (14%)
- **Queens**: 14 images (14% - corrected from 0%)
- **Bronx**: 8 images (8% - corrected from 0%)
- **Manhattan**: 6 images (6%)
- **Staten Island**: 2 images (2% - corrected from 14%)

## Major Fixes Applied

### Borough Boundary Reconstruction (Latest)
- **OSM Relation ID Corrections**: Fixed Queens (369519), Bronx (2552450), Staten Island (962876) with verified OpenStreetMap relation IDs
- **Way Chaining Algorithm**: Implemented proper end-to-end connection of OSM way segments to form continuous boundaries
- **Multi-component Handling**: Algorithm now finds all disconnected boundary components and selects the largest (main landmass)
- **Coordinate Validation**: All borough boundaries now properly span their expected geographic ranges
- **Polygon Closure**: Fixed boundary formation to create proper closed polygons where possible

### Classification Accuracy (Latest)
- **Stale Data Detection**: Identified that stored image classifications were using old incorrect algorithm results
- **Algorithm Verification**: Confirmed current classification algorithm works correctly with proper coordinate handling
- **Data Re-processing**: Re-classified all 100 images using corrected algorithm
- **Coordinate System Fix**: Ensured consistent lat/lng ordering throughout the classification pipeline
- **Color Mapping Fix**: Corrected borough keyword conversion from strings ("unknown") to proper keywords (:unclassified)

### Web Visualization Enhancements (Latest)
- **Borough Polygon Display**: Added `/api/boroughs` endpoint and polygon rendering with correct colors
- **Coordinate Conversion**: Fixed JavaScript coordinate handling for Leaflet.js (lng/lat to lat/lng swapping)
- **Visual Layering**: Borough polygons display as background (10% opacity) with image markers on top
- **Legend Accuracy**: Colors in legend now correctly match actual point positions on map
- **Geographic Accuracy**: Points classified as Queens now display in blue in Queens area, not amber in wrong locations

### Technical Improvements
- **Error Handling**: Comprehensive exception handling in boundary reconstruction and classification
- **Logging**: Added detailed logging for boundary component detection and way chaining process
- **Data Integrity**: Validated all coordinates fall within expected NYC geographic bounds
- **Performance**: Reduced boundary complexity through proper way chaining (183-468 points vs previous 599+ points)

## Known Issues & Improvements Needed
1. **Water Bodies**: Points in rivers/harbors between boroughs may need special handling
2. **Performance**: JTS polygons created on each classification call (should cache for production)
3. **Boundary Resolution**: Using simplified boundaries - could use full-resolution for higher precision
4. **Edge Cases**: Complex waterfront areas might benefit from manual boundary adjustments

## Architecture Notes
- **Coordinate Systems**: OSM data is [lng, lat] but JTS spatial operations expect [lat, lng] - conversion handled correctly
- **Polygon Topology**: Complex boroughs (Manhattan, Queens) may have multiple boundary components (islands) - algorithm selects largest
- **Classification Confidence**: Distance-based confidence scoring provides fallback for edge cases near borough boundaries
- **Web Stack**: Pure Clojure/ClojureScript approach with server-side HTML generation and client-side JavaScript for maps