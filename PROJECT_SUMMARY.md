# Project Summary: NYC Street View Collection System

## Overview
A Clojure-based system for collecting street view images uniformly distributed across New York City's five boroughs and classifying them by geographic location. The system uses the Mapillary API for high-quality street imagery and OpenStreetMap data for borough boundary classification.

## Key Features
- **Uniform Grid Sampling**: Creates configurable grids (10×10 to 20×20) across NYC's geographic bounds
- **Quality Filtering**: Multi-criteria image quality assessment excluding panoramic and low-resolution images  
- **Borough Classification**: Point-in-polygon geometric analysis using JTS spatial libraries
- **API Integration**: Mapillary API with rate limiting, error handling, and authentication
- **License Compliance**: CC BY-SA 4.0 compliant for public website usage

## Project Structure

### Core Namespaces
```
src/border_blur/
├── core.clj                    # Main orchestrator and CLI interface
├── images/
│   └── collector.clj          # Mapillary API integration and image collection
└── boroughs/
    ├── fetcher.clj           # OpenStreetMap boundary data fetching
    └── classifier.clj        # Geographic point-in-polygon classification
```

### Key Files
- **`deps.edn`**: Project dependencies and build configuration
- **`run_collection.clj`**: Simple collection script for testing
- **`.env`**: Mapillary API token configuration
- **`resources/boroughs/nyc-boroughs.edn`**: Cached NYC borough boundary data
- **`data/nyc-images.json`**: Collected image dataset with metadata

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

### Web Framework (Future Use)
- **`ring/ring-core` 1.12.1**: HTTP server foundation
- **`ring/ring-jetty-adapter` 1.12.1**: Jetty web server integration
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
;; Fetch boundaries from OpenStreetMap
(fetch-and-save-boroughs) ; Downloads and caches NYC borough polygons

;; Load cached boundary data
(load-borough-data) ; Returns borough boundary coordinates
```

### Geographic Classification
```clojure
;; Classify point by borough
(classify-point lat lng boroughs) ; Returns borough keyword or :unknown

;; Classification with confidence scoring
(classify-with-confidence lat lng boroughs) 
; Returns {:borough :manhattan :confidence :high :distance 0}
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

# Simple test script
clj run_collection.clj
```

## Architecture & Data Flow

### Collection Pipeline
1. **Grid Generation** → Geographic coordinate pairs across NYC bounding box
2. **API Fetching** → Mapillary image metadata retrieval per grid point
3. **Quality Filtering** → Multi-criteria assessment (resolution, age, panoramic detection)
4. **Data Storage** → JSON output with geographic metadata

### Geographic Classification
1. **Boundary Loading** → OSM polygon data via Overpass API
2. **Polygon Creation** → JTS geometry objects from coordinate arrays
3. **Point Testing** → Spatial intersection analysis with confidence scoring
4. **Buffer Zones** → 10-meter tolerance for boundary ambiguity handling

### Key Design Patterns
- **Keyword-based Configuration**: Flexible parameter passing with defaults
- **Error-first Design**: Comprehensive error handling and graceful degradation
- **Data-driven Architecture**: EDN configuration files for boundaries and settings
- **Functional Composition**: Pure functions with minimal side effects

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

### Testing & Debugging
```bash
# Small test collection
clj -M:run test

# Check boundary data
clj -M:run fetch-boroughs

# Validate collected data
head -20 data/nyc-images.json
```

### Data Validation
- Geographic coordinates within NYC bounds
- Image quality metrics logged during collection
- Borough classification confidence scoring
- API rate limiting and error handling

## Extension Points

### Scalability Enhancements
- **Multi-API Integration**: Add Google Street View, Bing Streetside APIs
- **Caching Layer**: Redis/database integration for large collections
- **Parallel Processing**: Concurrent API calls with threading

### Geographic Improvements  
- **Land Mask Integration**: Exclude water bodies from grid sampling
- **Density Weighting**: Population-based grid adjustment
- **Temporal Filtering**: Specific time period or season selection

### Classification Enhancements
- **Machine Learning**: Automated image content classification
- **Confidence Tuning**: Dynamic buffer zone adjustment
- **Multi-level Geography**: Neighborhood, zip code, census tract classification

### Web Interface
- **Visualization Dashboard**: Interactive maps with collected images
- **Real-time Collection**: Live progress monitoring
- **Image Annotation**: Crowdsourced labeling interface

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

## Current Status
✅ **Core Collection System**: Fully functional uniform image collection  
✅ **Quality Filtering**: Multi-criteria assessment working  
✅ **API Integration**: Mapillary integration with rate limiting  
✅ **Borough Boundaries**: OSM data fetching for Manhattan, Queens, Bronx  
🚧 **Classification**: Point-in-polygon logic needs polygon creation fixes  
🚧 **Brooklyn/Staten Island**: Additional OSM boundary IDs needed  
📋 **Web Interface**: Future enhancement for visualization and interaction