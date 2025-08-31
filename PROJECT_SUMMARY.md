# Project Summary: NYC Street View Collection System

## Overview
A Clojure-based system for collecting street view images uniformly distributed across New York City's five boroughs and classifying them by geographic location. The system uses the Mapillary API for high-quality street imagery and OpenStreetMap data for borough boundary classification, with a complete web visualization interface featuring accurate borough polygons and pure uniform random sampling.

## Key Features
- **Pure Uniform Random Sampling**: Generates completely random points across NYC with no clustering constraints
- **Grid-Based Sampling**: Legacy option for structured grid patterns (maintained for comparison)
- **Quality Filtering**: Multi-criteria image quality assessment excluding panoramic and low-resolution images  
- **Borough Classification**: Point-in-polygon geometric analysis using JTS spatial libraries with corrected coordinate handling
- **API Integration**: Mapillary API with rate limiting, error handling, and authentication
- **Web Visualization**: Interactive Leaflet.js map showing color-coded borough classifications with proper polygon boundaries
- **NYC Borough Game**: Interactive geography game where users identify which borough street view images are from
- **Large-Scale Collection**: Proven at 1000+ point sampling with 25% success rate
- **License Compliance**: CC BY-SA 4.0 compliant for public website usage

## Project Structure

### Core Namespaces
```
src/border_blur/
├── core.clj                    # Main orchestrator and CLI interface
├── images/
│   └── collector.clj          # Mapillary API integration and image collection (both grid and random)
├── boroughs/
│   ├── fetcher.clj           # OpenStreetMap boundary data fetching with improved way chaining
│   └── classifier.clj        # Geographic point-in-polygon classification with fixed coordinate handling
├── borough_game.clj           # NYC borough identification game logic with session management
└── web/
    └── server.clj            # Ring/Compojure web server with map visualization, borough polygons, and game interface
```

### Key Files
- **`deps.edn`**: Project dependencies and build configuration
- **`run_collection.clj`**: Simple collection script for testing
- **`.env`**: Mapillary API token configuration
- **`resources/boroughs/nyc-boroughs.edn`**: Cached NYC borough boundary data with corrected OSM IDs
- **`data/random-classified-images.json`**: Current uniform random sampling dataset (prioritized by web server)
- **`data/nyc-images.json`**: Legacy grid-based collection dataset

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
- **`ring/ring-defaults` 0.5.0**: Security and middleware defaults with CSRF disabled
- **`ring/ring-middleware-params`**: Form parameter parsing for game interactions
- **`ring/ring-middleware-keyword-params`**: Keyword parameter conversion
- **`ring/ring-devel` 1.12.1**: Development tools and reloading
- **`compojure/compojure` 1.7.1**: Routing library
- **`hiccup/hiccup` 1.0.5**: HTML generation

### Data Science
- **`org.scicloj/noj` 2-beta18**: Mathematical operations and data analysis

## Available Tools & APIs

### NYC Borough Game
```clojure
;; Create new game session
(require '[border-blur.borough-game :as bg])
(def game (bg/new-game "Very familiar - I live/lived in NYC"))
(bg/save-game-session! game)

;; Process user answers
(bg/process-answer session-id "brooklyn") ; Returns result with scoring

;; Advance to next stage
(bg/advance-to-next-stage session-id) ; Returns game state or completion

;; Game features:
;; - 15 stages with progressive difficulty (easy → medium → hard)
;; - Scoring system with streak bonuses (1-10 points per correct answer)
;; - Multiple-choice interface with color-coded borough buttons
;; - Session management with in-memory storage
;; - Reveal maps with borough boundary overlays highlighting correct answers
;; - User familiarity tracking for analytics
;; - Responsive design with inline styling for button visibility
```

### Image Collection (Updated)

#### Pure Uniform Random Sampling (Recommended)
```clojure
;; Collect images with pure uniform random sampling (filters out unclassified points)
(collect-nyc-images-random :total-images 100)

;; Large-scale collection with automatic retry for sufficient borough-classified images
(collect-nyc-images-random :total-images 1000)

;; Generate pure random points (no distance constraints)
(generate-pure-random-points min-lng max-lng min-lat max-lat n)
```

#### Grid-Based Sampling (Legacy)
```clojure
;; Collect images with uniform grid sampling  
(collect-nyc-images :grid-size 10 :max-images-per-point 2)

;; Quality filtering function (shared by both methods)
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
(server/load-image-data) ; Returns raw image data with classifications (prioritizes random data)
```

### CLI Commands (Updated)
```bash
# Test collection (5×5 grid, ~25 images) - LEGACY
clj -M:run test

# Grid-based collection - LEGACY
clj -M:run collect [size]        # Grid-based collection with optional grid size

# Pure uniform random collection - RECOMMENDED  
clj -M:run collect-random [count]  # Random sampling collection with optional image count

# Fetch borough boundaries only
clj -M:run fetch-boroughs

# Start web visualization server
clj -M:run web [port]  # Default port 3000

# Simple test script
clj run_collection.clj
```

### Usage Examples
```bash
# Small random collection
clj -M:run collect-random 50

# Large-scale random collection  
clj -M:run collect-random 1000

# Compare methods
clj -M:run collect 10          # 10x10 grid (legacy)
clj -M:run collect-random 100  # 100 random points (recommended)
```

## Architecture & Data Flow

### Pure Uniform Random Collection Pipeline (Current)
1. **Random Point Generation** → Completely random coordinate pairs across NYC bounding box (no constraints)
2. **API Fetching** → Mapillary image metadata retrieval per random point  
3. **Quality Filtering** → Multi-criteria assessment (resolution, age, panoramic detection)
4. **Borough Classification** → JTS point-in-polygon analysis with corrected coordinate handling
5. **Data Storage** → JSON output with geographic metadata and borough assignments

### Grid-Based Collection Pipeline (Legacy)
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
3. **Data Loading** → Prioritizes `random-classified-images.json` over legacy `nyc-images.json`
4. **Map Interface** → Leaflet.js with Stadia AlidadeSmooth tiles and borough polygon overlays
5. **Interactive Features** → Color-coded markers, borough boundary polygons, popups, legend

### Key Design Patterns
- **Pure Randomness**: No artificial constraints that interfere with uniform distribution
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
- **Search Radius**: ~111 meters per point (optimized for uniform sampling)

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

# Game development workflow:
(require '[border-blur.borough-game :as bg])
(def test-game (bg/new-game "test"))
(bg/save-game-session! test-game)
```

### Testing & Debugging
```bash
# Small random collection test
clj -M:run collect-random 20

# Large-scale collection
clj -M:run collect-random 1000

# Check boundary data
clj -M:run fetch-boroughs

# Start web interface
clj -M:run web

# Validate collected data
head -20 data/random-classified-images.json
```

### Data Validation
- Geographic coordinates within NYC bounds
- Image quality metrics logged during collection
- Borough classification confidence scoring with corrected algorithm
- API rate limiting and error handling
- Success rates: 25-35% for random sampling across 1000 points

## Web Interface Features

### Interactive Map (Enhanced)
- **Base Tiles**: Stadia AlidadeSmooth for clean, professional appearance
- **Borough Polygons**: Color-coded boundary overlays with proper geometry (10% opacity, non-interactive)
- **Markers**: Large color-coded circle markers (8px radius) by borough classification
- **Image Viewer**: Dedicated floating panel in top-right corner for street view images
- **Click Interaction**: Click markers to view actual street view images with metadata
- **Legend**: Borough color key with classification counts matching actual point colors
- **Data Priority**: Automatically loads latest random sampling data when available

### Borough Color Scheme (Corrected)
- **Manhattan**: #DC2626 (Dark red)
- **Brooklyn**: #059669 (Dark green)  
- **Queens**: #1D4ED8 (Dark blue)
- **Bronx**: #7C2D12 (Dark brown)
- **Staten Island**: #A16207 (Dark amber)
- **Unclassified**: #4B5563 (Dark grey)

### API Endpoints
- **`/`**: Redirects to NYC Borough Game
- **`/images-map`**: Interactive map visualization page
- **`/borough-game`**: NYC borough identification game landing page
- **`/borough-game/{session-id}`**: Game interface with street view images and multiple-choice buttons
- **`/borough-game/{session-id}/reveal`**: Answer reveal page with interactive maps
- **`/borough-game/{session-id}/results`**: Final game results and statistics
- **`/api/images`**: GeoJSON FeatureCollection of all images with corrected borough data
- **`/api/boroughs`**: GeoJSON FeatureCollection of borough boundary polygons

## Extension Points

### Sampling Enhancements
- **Hybrid Approaches**: Combine random and grid-based methods
- **Stratified Sampling**: Borough-proportional random sampling
- **Temporal Sampling**: Time-based collection strategies
- **Density-Aware Sampling**: Population or infrastructure-weighted random points

### Scalability Enhancements
- **Multi-API Integration**: Add Google Street View, Bing Streetside APIs
- **Caching Layer**: Redis/database integration for large collections
- **Parallel Processing**: Concurrent API calls with threading
- **Batch Processing**: Handle collections of 10,000+ points

### Geographic Improvements  
- **Land Mask Integration**: Exclude water bodies from random sampling
- **Density Weighting**: Population-based point adjustment
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
- **Collection Comparison**: Side-by-side grid vs random visualization
- **Authentication**: User management for data contributions

## License & Compliance
- **Source Code**: Open development with Clojure
- **Image Data**: Mapillary CC BY-SA 4.0 license
- **Attribution Required**: Mapillary logo + link, CC license reference
- **Public Website Safe**: Full compliance for research and educational use

## Performance Metrics

### Pure Uniform Random Sampling (Current)
- **Small Collection**: 50 images from 100 points in ~2-3 minutes (50% success rate)
- **Medium Collection**: 100 images from 250 points in ~5-7 minutes (40% success rate)
- **Large Collection**: 253 images from 1000 points in ~15-20 minutes (25% success rate)
- **API Rate**: ~1-2 seconds per point including quality filtering
- **Success Rate**: 25-50% depending on sampling density and geographic coverage
- **Image Quality**: 1920×1080 to 5344×3006 resolution range
- **Time Coverage**: 2017-2025 capture dates
- **Geographic Distribution**: True uniform across all boroughs including Staten Island

### Grid-Based Sampling (Legacy)
- **Test Collection**: 100 images from 10×10 grid in ~3-5 minutes
- **API Rate**: ~2-3 seconds per grid point including quality filtering
- **Success Rate**: Near 100% grid point coverage
- **Clustering Issues**: Multiple images clustered around grid points

## Current Status
✅ **Pure Uniform Random Sampling**: Primary collection method with true uniform distribution  
✅ **Large-Scale Collection**: Proven at 1000+ points with 25% success rate
✅ **Quality Filtering**: Multi-criteria assessment working optimally
✅ **API Integration**: Mapillary integration with rate limiting and error handling
✅ **Borough Boundaries**: Complete OSM data with corrected relation IDs and proper way chaining
✅ **Classification System**: Fixed coordinate handling and accurate borough assignment
✅ **Web Visualization**: Interactive map with accurate borough polygons and prioritized data loading
✅ **NYC Borough Game**: Complete 15-stage geography game with progressive difficulty and scoring
✅ **Game Session Management**: In-memory session storage with proper form parameter handling
✅ **Interactive Reveal Maps**: Borough polygon overlays with location highlighting
✅ **Game UI Polish**: Color-coded borough buttons with inline styling and improved visibility
✅ **Form Parameter Debugging**: Resolved form submission issues with explicit Ring middleware
✅ **REPL Development**: Hot-reloadable server functions for development
✅ **Code Cleanup**: Removed obsolete distance-constraint functions and debug logging
✅ **Data Management**: Smart data file prioritization (random over grid)

### Latest Collection Results (1000-Point Random Sampling)
- **Total Images**: 253 images from 1000 random points (25.3% success rate)
- **Unknown**: 131 images (52%) - water bodies, parks, boundaries
- **Queens**: 66 images (26%) - largest geographic area
- **Brooklyn**: 24 images (9%) - urban density
- **Manhattan**: 14 images (6%) - small area, high density
- **Bronx**: 14 images (6%) - balanced coverage
- **Staten Island**: 4 images (2%) - lowest density

## Major Improvements Applied

### Pure Uniform Random Sampling Implementation (Latest)
- **Algorithm Simplification**: Removed all distance constraints and clustering prevention mechanisms
- **True Randomness**: Each point generated independently with equal probability across NYC
- **Code Cleanup**: Removed `haversine-distance`, `too-close-to-existing?`, `generate-random-points`, and `filter-images-by-distance` functions
- **Natural Distribution**: Points cluster and spread naturally without artificial constraints
- **Scalability**: Successfully tested at 1000-point scale with consistent performance

### Web Server Data Management (Latest)
- **Smart Data Loading**: Prioritizes `random-classified-images.json` over legacy `nyc-images.json`
- **Fresh Data Display**: Automatically loads latest collection results
- **Port Flexibility**: Easy server restart on different ports to bypass browser caching

### NYC Borough Game Implementation (December 2024)
- **Complete Game System**: 15-stage geography game with progressive difficulty (easy → medium → hard)
- **Interactive UI**: Color-coded multiple-choice buttons with inline styling for maximum visibility
- **Form Parameter Resolution**: Fixed Ring middleware configuration for proper form submission handling
- **Session Management**: In-memory game session storage with UUID-based session IDs
- **Reveal System**: Interactive maps showing correct answers with highlighted borough polygons
- **Scoring Algorithm**: Progressive scoring with streak bonuses (1-10 points per correct answer)
- **User Analytics**: Familiarity level tracking for educational research
- **Production Polish**: Removed debug logging, optimized button colors, improved font sizing

### Enhanced Search Algorithm & Web Interface (December 2024)
- **Unclassified Point Filtering**: Updated `collect-and-classify-random` to automatically filter out all `:unknown` classified points
- **Adaptive Collection**: Implements retry logic with increasing sample sizes to ensure sufficient borough-classified images
- **Large Interactive Markers**: Increased circle marker radius to 8px with thicker borders for better clickability
- **Dedicated Image Viewer**: Floating panel in top-right corner displays actual street view images on marker click
- **Non-Interactive Polygons**: Borough boundary polygons set to `interactive: false` to prevent click interference
- **Production-Ready Code**: Removed all debug logging and cleaned up event handling for optimal performance

### Borough Boundary Reconstruction (Previous)
- **OSM Relation ID Corrections**: Fixed Queens (369519), Bronx (2552450), Staten Island (962876) with verified OpenStreetMap relation IDs
- **Way Chaining Algorithm**: Implemented proper end-to-end connection of OSM way segments to form continuous boundaries
- **Multi-component Handling**: Algorithm selects largest connected boundary component for complex geometries
- **Coordinate Validation**: All borough boundaries properly span their expected geographic ranges

### Classification Accuracy (Previous)
- **Coordinate System Fix**: Ensured consistent lat/lng ordering throughout the classification pipeline
- **Algorithm Verification**: Confirmed current classification algorithm works correctly with proper coordinate handling
- **Color Mapping Fix**: Corrected borough keyword conversion for proper web visualization

## Known Strengths & Design Decisions

### Pure Uniform Random Sampling Benefits
1. **Mathematical Correctness**: True uniform distribution without artificial constraints
2. **Natural Patterns**: Points cluster and spread naturally as expected from random processes
3. **Scalability**: Linear performance scaling with point count
4. **Simplicity**: Clean, minimal codebase without complex distance calculations
5. **Geographic Realism**: Results reflect actual NYC street imagery availability patterns

### Current Architecture Advantages
- **Flexible Collection**: Support for both random and grid methods for comparison
- **REPL-Driven Development**: Hot-reloadable functions for rapid iteration
- **Data Prioritization**: Smart loading of most recent collection results
- **Clean Separation**: Distinct namespaces for collection, classification, and visualization
- **Error Resilience**: Comprehensive error handling throughout the pipeline

## Architecture Notes
- **Coordinate Systems**: OSM data is [lng, lat] but JTS spatial operations expect [lat, lng] - conversion handled correctly
- **Polygon Topology**: Complex boroughs (Manhattan, Queens) may have multiple boundary components (islands) - algorithm selects largest
- **Classification Confidence**: Distance-based confidence scoring provides fallback for edge cases near borough boundaries
- **Web Stack**: Pure Clojure approach with server-side HTML generation and client-side JavaScript for maps
- **Random Generation**: Uses Clojure's `rand` function for uniform distribution across geographic bounds
- **Data Persistence**: JSON format for cross-language compatibility and human readability