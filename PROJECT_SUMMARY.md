# Border Blur - Project Summary

## Overview
**Border Blur** is a fast-paced geography game built in Clojure where players identify whether two street-view images are from the same city or different cities. The game focuses on subtle visual differences around city borders, particularly Tel Aviv and its neighboring cities. Players progress through 20 stages with increasing difficulty, earning points and streak bonuses.

## Core Architecture

### Web Framework
- **Ring/Jetty**: HTTP server with middleware
- **Compojure**: Routing with RESTful endpoints
- **Hiccup**: HTML generation with Clojure data structures
- **Cookie-free sessions**: URL-based session management (`/game/{session-id}`)

### Game Logic
- **Atom-based state**: In-memory session storage
- **Progressive difficulty**: Easy (blue) → Medium (orange) → Hard (red)
- **Scoring system**: Base points + streak bonuses (max 20 points per stage)
- **20-stage gameplay**: Complete game sessions with persistent state

## Key Files & Components

### Core Application (`src/border_blur/`)
- **`core.clj`**: Main server, routes, Ring middleware setup
- **`game.clj`**: Session management, scoring logic, game state
- **`handlers.clj`**: HTTP request processing, form handling, redirects
- **`views.clj`**: HTML templates and UI components

### GIS & Geography (`src/border_blur/gis/`)
- **`cities.clj`**: City data loading, neighbor relationships
- **`core.clj`**: Point-in-polygon testing, distance calculations
- **`boundaries.clj`**: Border detection algorithms (future use)

### Image System (`src/border_blur/images/`)
- **`selector.clj`**: Smart image pair generation with fallbacks
- **`fetcher.clj`**: Multi-API framework (Mapillary, OpenStreetCam, Flickr)

### Resources
- **`cities/israeli-cities.edn`**: City boundary data and metadata
- **`api-keys.edn`**: API configuration template
- **`public/`**: Static assets (CSS, images, JS)

## Key Dependencies

```clojure
;; Web Framework
ring/ring-core "1.12.1"           ; HTTP request/response
ring/ring-jetty-adapter "1.12.1"  ; Web server
compojure/compojure "1.7.1"       ; Routing
hiccup/hiccup "1.0.5"            ; HTML generation

;; GIS & Geography
factual/geo "3.0.1"              ; Geometric calculations, polygons

;; Data Processing
cheshire/cheshire "5.12.0"        ; JSON parsing
org.clojure/data.json "2.5.0"    ; JSON handling

;; HTTP Clients
clj-http/clj-http "3.12.3"       ; External API calls
```

## Development Workflow

### Start Server
```bash
cd border-blur
clojure -M:run 3001    # Starts on port 3001
```

### Start REPL
```bash
clojure -A:nrepl       # Port 7888
```

### Test Components
```clojure
;; Load GIS library (specific pattern required)
(require '[geo [jts :as jts] [spatial :as spatial]])

;; Test game logic
(require '[border-blur.game :as game])
(def session (game/new-game "Tel Aviv"))
(game/save-game-session! session)

;; Test image generation
(require '[border-blur.images.selector :as selector])
(selector/generate-smart-image-pair "Tel Aviv" 5 20)
```

## Implementation Patterns

### Session Management
```clojure
;; Cookie-free sessions via URL paths
GET "/game/:session-id" -> handlers/game-page
;; Sessions stored in atoms
(def game-sessions (atom {}))
;; UUID-based session IDs
(str (java.util.UUID/randomUUID))
```

### Image Pair Generation
```clojure
;; Progressive difficulty
(cond (< current-stage 5) :easy
      (< current-stage 15) :medium  
      :else :hard)

;; Same/different city mix (60% different, 40% same)
(< (rand) 0.6) ; determines pair type
```

### Error Handling
```clojure
;; Graceful fallbacks with enhanced placeholders
(try
  ;; Attempt real image fetching
  (fetch-real-images coords)
  (catch Exception e
    ;; Fall back to color-coded placeholders
    (create-fallback-image-pair city-a city-b difficulty answer-type)))
```

## Current Game Features

### Gameplay Mechanics
- **20 progressive stages** with increasing difficulty
- **Color-coded visual cues**: Blue (easy) → Orange (medium) → Red (hard)
- **Smart scoring**: 10 base points + streak bonus (max 10)
- **Real city pairs**: Tel Aviv, Ramat Gan, Jerusalem, Holon, etc.
- **Visual feedback**: Same city pairs use matching colors

### Session Flow
1. Home page: User enters known city
2. Game creation: New session with UUID
3. Image pair display: Two images with binary choice
4. Answer processing: Score calculation and progression
5. Results page: Final score and statistics

## Image System Architecture

### Current: Enhanced Placeholder System
```clojure
;; Difficulty-based styling
(case difficulty-level
  :easy "4A90E2/FFFFFF"    ; Blue
  :medium "F39C12/FFFFFF"  ; Orange  
  :hard "E74C3C/FFFFFF")   ; Red

;; Same vs different visual cues
(if (= answer-type :same)
  style-a                  ; Same color for same city
  "2ECC71/FFFFFF")        ; Green for different city
```

### Future: Curated Image Collection
- **Target**: 50-100 high-quality image pairs
- **Focus**: Tel Aviv border areas (Ramat Gan, Holon, Givatayim, Bnei Brak)
- **Storage**: Local images in `resources/public/images/`
- **Metadata**: GPS coordinates, difficulty ratings, educational content

## Extension Points

### Image Collection (Phase 4 - Planned)
```clojure
;; Image metadata structure
{:id "ta-rg-001"
 :pair-type :different
 :difficulty :medium
 :left {:url "/images/tel-aviv-001.jpg"
        :city "Tel Aviv"
        :coords [34.7818 32.0853]
        :neighborhood "Florentin"}
 :right {:url "/images/ramat-gan-001.jpg"
         :city "Ramat Gan" 
         :coords [34.8131 32.0853]
         :neighborhood "City Center"}}
```

### Educational Features (Phase 5 - Planned)
- Location reveal after answers
- Neighborhood history and facts
- Achievement system and leaderboards
- Map visualization of actual locations

## Known Issues & Notes

### GIS Library (factual/geo)
- **Critical**: Must load with `[geo [jts :as jts] [spatial :as spatial]]`
- **Functions**: Use `spatial/intersects?` not `contains?`
- **Polygon creation**: Currently bypassed due to format issues

### API Integration
- **Status**: Framework built, not yet integrated
- **Fallback**: Enhanced placeholder system provides full gameplay
- **Future**: Google Street View Static API for curated collection

## Development Status

✅ **Complete**: Core game mechanics, session management, enhanced placeholders  
🚀 **Next**: Curated Tel Aviv border image collection  
📦 **Future**: Educational features, achievements, real-time multiplayer

The game is fully playable with realistic placeholder images while the curated image collection is developed.