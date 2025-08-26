# Border Blur - Project Summary

## Overview
**Border Blur** is a geography learning game built in Clojure where players view single street-view images and decide whether they are located within Tel Aviv city boundaries. After each answer, the game reveals the truth with an interactive map showing the actual location. The game focuses on Tel Aviv geography education with 3 progressive difficulty stages.

## Game Concept (Redesigned)
- **Single Image Display**: Players see one street-view image per stage
- **Binary Choice**: "Yes, it's in Tel Aviv" / "No, it's not in Tel Aviv"
- **Immediate Reveal**: Answer feedback with interactive map showing true location
- **Educational Focus**: Learn Tel Aviv boundaries and neighboring cities
- **Progressive Difficulty**: Easy (clear cases) → Medium (suburban areas) → Hard (border zones)

## Core Architecture

### Web Framework
- **Ring/Jetty**: HTTP server with middleware
- **Compojure**: Routing with RESTful endpoints
- **Hiccup**: HTML generation with Clojure data structures
- **Cookie-free sessions**: URL-based session management (`/game/{session-id}`)
- **Leaflet.js**: Interactive maps for location reveals

### Game Logic
- **Atom-based state**: In-memory session storage
- **Binary scoring**: Yes/No answers with difficulty bonuses
- **Progressive difficulty**: Easy → Medium → Hard (stages 1-8, 9-16, 17-20)
- **Scoring system**: 10 base points + difficulty bonus + streak multiplier (max 2x)
- **3-stage gameplay**: Shortened for testing (originally 20 stages)

## Key Files & Components

### Core Application (`src/border_blur/`)
- **`core.clj`**: Main server, routes, Ring middleware setup
- **`game.clj`**: Binary scoring system, Tel Aviv game state management
- **`handlers.clj`**: Answer → reveal → next flow, Tel Aviv choice processing
- **`views.clj`**: Single image layout, reveal page with map integration

### GIS & Geography (`src/border_blur/gis/`)
- **`cities.clj`**: City data loading, Tel Aviv and neighbor relationships
- **`core.clj`**: Point-in-polygon testing for Tel Aviv boundaries
- **`boundaries.clj`**: Border detection algorithms (legacy)

### Image System (`src/border_blur/images/`)
- **`selector.clj`**: Single Tel Aviv image generation with real street-view photos
- **`fetcher.clj`**: Multi-API framework (legacy pair system)
- **Real Image Collections**: Tel Aviv + neighbor cities (Ramat Gan, Givatayim, Bnei Brak)

### Spatial Sampling (`src/border_blur/sampling/`)
- **`spatial_diversity.clj`**: Advanced spatial sampling (legacy)
- **`diversity_simple.clj`**: Simplified diversity algorithms (legacy)

### Resources
- **`cities/israeli-cities.edn`**: Tel Aviv and neighbor city boundaries
- **`public/images/`**: Real street-view image collections
  - `border-collection/tel-aviv/`: 18 real Tel Aviv images
  - `border-collection/ramat-gan/`: Ramat Gan neighbor images
  - `border-collection/givatayim/`: Givatayim neighbor images
  - `manual-testing/bnei-brak/`: Bnei Brak neighbor images
- **`public/js/map.js`**: Leaflet.js map integration and reveal animations
- **`public/css/style.css`**: Complete styling including reveal page layout

## Key Dependencies

```clojure
;; Web Framework
ring/ring-core "1.12.1"           ; HTTP request/response
ring/ring-jetty-adapter "1.12.1"  ; Web server
compojure/compojure "1.7.1"       ; Routing
hiccup/hiccup "1.0.5"            ; HTML generation

;; GIS & Geography
factual/geo "3.0.1"              ; Tel Aviv boundary testing

;; Mathematical Operations
generateme/fastmath "3.0.0-alpha2" ; Spatial sampling (legacy)

;; Data Processing
cheshire/cheshire "5.12.0"        ; JSON parsing
org.clojure/data.json "2.5.0"    ; JSON handling

;; HTTP Clients
clj-http/clj-http "3.12.3"       ; External API calls (legacy)

;; Frontend
;; Leaflet.js 1.9.4 (CDN)          ; Interactive maps
;; OpenStreetMap tiles             ; Base map layer
```

## Development Workflow

### Start Server
```bash
clojure -M:run [PORT]     # Default port 3000, supports custom ports
clojure -M:run 3001       # Start on port 3001
clojure -M:run 8080       # Start on port 8080
```

### Start REPL
```bash
clojure -A:nrepl          # Port 7888 with hot reloading
```

### Test Tel Aviv Game Components
```clojure
;; Load GIS library (specific pattern required)
(require '[geo [jts :as jts] [spatial :as spatial]])

;; Test binary game logic
(require '[border-blur.game :as game])
(def session (game/new-game "Yes, I know Tel Aviv well"))
(game/save-game-session! session)

;; Test single image generation with real photos
(require '[border-blur.images.selector :as selector])
(selector/get-single-tel-aviv-image {:user-city "Tel Aviv" :current-stage 1 :total-stages 3})

;; Test answer processing
(def image (game/get-or-generate-current-image (:session-id session)))
(def result (game/process-answer (:session-id session) true)) ; true = "Yes, in Tel Aviv"
```

## Implementation Patterns

### Tel Aviv Game Flow
```clojure
;; New game state structure
{:session-id "uuid"
 :current-stage 1
 :total-stages 3
 :score 0
 :streak 0
 :current-image {...}      ; Single image with Tel Aviv answer
 :answer-revealed false}   ; Reveal screen state

;; Game flow: show → answer → reveal → next
:showing-image → (user answers) → :revealing-answer → (user clicks next) → :showing-image
```

### Binary Scoring System
```clojure
;; New scoring with difficulty bonuses
(defn calculate-score [correct? difficulty streak]
  (let [base-points (if correct? 10 0)
        difficulty-bonus (case difficulty :easy 0 :medium 5 :hard 10)
        streak-multiplier (min 2.0 (+ 1.0 (* streak 0.2)))]
    (int (* (+ base-points difficulty-bonus) streak-multiplier))))
```

### Real Image Integration
```clojure
;; Tel Aviv image probability by difficulty
(def tel-aviv-probability 
  (case difficulty
    :easy 0.6    ; 60% Tel Aviv for clear cases
    :medium 0.6  ; 60% Tel Aviv for suburban mix  
    :hard 0.5))  ; 50% Tel Aviv for boundary confusion

;; Real image paths with coordinate parsing
"/images/border-collection/tel-aviv/32.06695_34.82289_ta-border-south_113269.jpg"
"/images/border-collection/ramat-gan/32.06857_34.82639_rg-border-west_119347.jpg"
```

## Current Game Features

### Gameplay Mechanics
- **3 progressive stages** (easy, medium, hard)
- **Real street-view images** from collected Tel Aviv border areas
- **Binary Tel Aviv recognition** with immediate feedback
- **Interactive map reveals** showing actual image locations
- **Tel Aviv boundary visualization** with Leaflet.js
- **Difficulty progression** with color-coded visual indicators

### Session Flow
1. **Home page**: "Have you lived or worked in Tel Aviv?" (3 options)
2. **Game creation**: New session with UUID
3. **Single image display**: Street-view with "Is it in Tel Aviv?" choice
4. **Answer reveal**: Correct/incorrect + map showing true location + Tel Aviv boundaries
5. **Next image**: Continue to next stage
6. **Results page**: Final score and statistics (after 3 stages)

### Map Integration
- **Leaflet.js**: Interactive maps with smooth animations
- **Tel Aviv boundaries**: Polygon overlay showing city limits
- **Location markers**: Color-coded (green for Tel Aviv, red for outside)
- **Reveal animations**: Pan and zoom to actual image location
- **Legend**: Tel Aviv boundary line and location marker explanation

## Image System Architecture

### Current: Real Street-View Image Collection
```clojure
;; Real image collections by city
(def tel-aviv-images [...])     ; 18 Tel Aviv images
(def neighbor-images 
  {:ramat-gan [...]             ; Ramat Gan images
   :givatayim [...]             ; Givatayim images  
   :bnei-brak [...]})           ; Bnei Brak images

;; Coordinate parsing from filenames
(parse-coords-from-filename "32.06695_34.82289_ta-border-south_113269.jpg")
;; => [34.82289 32.06695]
```

### Image Selection Strategy
- **60% Tel Aviv images** (within city boundaries)
- **40% Neighbor images** (Ramat Gan, Givatayim, Bnei Brak)
- **Progressive difficulty** based on stage number
- **Fallback system**: Real images preferred, placeholders if errors
- **Coordinate extraction**: GPS coordinates parsed from filenames

## Map & UI Architecture

### Leaflet.js Integration
```javascript
// Map initialization in map.js
const map = L.map('reveal-map').setView([32.0853, 34.7818], 11);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png').addTo(map);

// Tel Aviv boundary polygon
const telAvivBoundary = L.polygon(boundaryCoords, {
  color: '#2196F3', weight: 3, fillOpacity: 0.1
}).addTo(map);

// Reveal animation with flyTo
map.flyTo([lat, lng], 14, { duration: 1.5 });
```

### CSS Architecture
- **Single image layout**: `.single-image-container` with difficulty indicators
- **Reveal page**: Two-column grid (image + map) with `.reveal-container`
- **Difficulty indicators**: Color-coded badges (blue/orange/red)
- **Responsive design**: Mobile-friendly with stacked layout
- **Map styling**: Rounded corners, shadows, legend positioning

## Development Status & Recent Changes

✅ **Major Redesign Complete (Current Session)**:
- **Game concept**: Transformed from pair comparison to single-image Tel Aviv geography
- **Scoring system**: Binary Yes/No with difficulty bonuses and streak multipliers  
- **UI redesign**: Single image layout + reveal page with map integration
- **Real images**: Integrated existing street-view photo collection (50+ images)
- **Map integration**: Leaflet.js with Tel Aviv boundaries and reveal animations
- **CLI enhancement**: Improved port configuration with validation and usage info
- **Game length**: Shortened to 3 stages for easier testing

🚀 **Ready Features**:
- Complete Tel Aviv geography learning experience
- Real street-view image collection with GPS coordinates
- Interactive map reveals with boundary visualization
- Progressive difficulty with visual indicators
- Mobile-responsive design

🔧 **Known Issues**:
- Reveal page layout needs debugging (CSS/HTML structure issues)
- Map component integration may need refinement

## Extension Points

### Educational Enhancements
```clojure
;; Potential additions
- Neighborhood information display
- Historical facts about locations
- Achievement system for geography mastery
- Difficulty analytics and progress tracking
```

### Technical Improvements
- Enhanced map interactions (zoom controls, layer toggles)
- Image preloading for smoother gameplay
- Session persistence across browser restarts
- Multi-city support beyond Tel Aviv

## Known Issues & Notes

### GIS Library (factual/geo)
- **Critical**: Must load with `(require '[geo [jts :as jts] [spatial :as spatial]])`
- **Tel Aviv boundaries**: Used for point-in-polygon testing
- **Coordinate format**: [longitude, latitude] format required

### CLI Configuration
- **Port validation**: Ensures ports are in 1024-65535 range
- **Error handling**: Clear messages for invalid inputs
- **Usage examples**: Multiple format options documented

### Map Integration
- **Leaflet.js**: Loaded via CDN for map functionality
- **OpenStreetMap**: Free tile layer with good Tel Aviv coverage
- **Reveal timing**: 500ms delay before animation starts

The game is now a focused Tel Aviv geography learning tool with real street-view images and educational map reveals, successfully redesigned from the original pair-comparison format.