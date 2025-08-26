# Border Blur - Development Plan

## Project Overview
**Border Blur** is a fast-paced geography game where players identify whether two street-view images are from the same city or different cities. The game focuses on border areas between cities where visual cues are subtle, making it genuinely challenging.

## Core Game Concept
- **Binary Choice**: Players see two images and decide: Same City or Different Cities?
- **Border Focus**: Images are deliberately chosen from areas near city boundaries
- **Progressive Difficulty**: Starts with obvious differences, progresses to subtle distinctions
- **Personalized Content**: Prioritizes the user's known city and its neighbors
- **Cookie-Free**: Session management via URL paths (`/game/{session-id}`)
- **20 Stages**: Quick gameplay with scoring and streak bonuses

## ✅ Phase 1: Foundation (COMPLETE)
### What We Built
- **Project Structure**: Clean MVC architecture with separate namespaces
- **GIS Integration**: Factual/geo library working with point-in-polygon and distance calculations
- **Game Logic**: Session management, scoring system, streak tracking
- **Web Framework**: Ring/Compojure with cookie-free session handling
- **UI**: Beautiful responsive design with gradient backgrounds
- **City Data**: Initial Israeli cities (Tel Aviv, Ramat Gan, Jerusalem)

### Key Files Created
- `deps.edn` - Dependencies including factual/geo
- `src/border_blur/core.clj` - Main server and routes
- `src/border_blur/game.clj` - Game state and scoring logic
- `src/border_blur/handlers.clj` - HTTP request handlers
- `src/border_blur/views.clj` - Hiccup HTML generation
- `src/border_blur/gis/core.clj` - GIS functions
- `src/border_blur/gis/cities.clj` - City data loading
- `resources/cities/israeli-cities.edn` - City polygons
- `resources/public/css/style.css` - Responsive styling

## ✅ Phase 2: Border Detection & Enhanced Placeholders (COMPLETE)
### What We Built
- **Smart Border Detection**: Algorithm to find interesting border points between cities
- **Enhanced Placeholder System**: Color-coded difficulty with real city names
- **Progressive Difficulty Visual Cues**: Blue→Orange→Red for easy→medium→hard
- **Improved Game Logic**: Same/different city detection with visual feedback
- **Expanded City Database**: Tel Aviv and neighboring cities with realistic data

### Key Files Created
- `src/border_blur/gis/boundaries.clj` - Border detection algorithms
- `src/border_blur/images/fetcher.clj` - Multi-API image fetching framework
- `src/border_blur/images/selector.clj` - Smart image pair selection with placeholders
- `resources/cities/expanded-cities.edn` - Israeli cities with real boundaries

### Key Algorithms
1. **Border Hotspot Detection**: Finds closest approach points between city polygons
2. **Difficulty-Based Visual Design**: Color coding for game stages
3. **City Pair Intelligence**: Real neighboring city combinations
4. **Fallback Strategy**: Enhanced placeholders when APIs unavailable

## ✅ Phase 3: Integration & Core Game (COMPLETE)
### What We Built
- **Full Game Integration**: All components working together
- **Session Management**: Cookie-free URL-based sessions
- **Error Handling**: Graceful fallbacks and polygon creation fixes
- **Enhanced Placeholders**: Realistic game experience without external APIs
- **Complete Game Flow**: 20 stages with scoring and streak bonuses

### Key Achievements
- Fixed GIS library integration (factual/geo namespace issues)
- Resolved handler arity mismatches
- Implemented complete game session flow
- Enhanced placeholder system for better UX
- Real city data integration

## 🚀 Phase 4: Curated Tel Aviv Border Image Collection (NEXT)
### New Strategy: Fixed Image Pairs
Instead of dynamic API fetching, create a curated collection of high-quality image pairs around Tel Aviv borders.

### Goals
1. **Curate 50-100 High-Quality Image Pairs**
   - Tel Aviv ↔ Ramat Gan border areas
   - Tel Aviv ↔ Holon boundary zones
   - Tel Aviv ↔ Givatayim transition areas
   - Tel Aviv ↔ Bnei Brak border regions

2. **Image Collection Strategy**
   - Use Google Street View API for specific coordinates
   - Focus on visually interesting border transition zones
   - Include both obvious and subtle differences
   - Ensure high image quality and consistency

3. **Content Categories**
   - **Easy**: Clear architectural/landscape differences
   - **Medium**: Subtle neighborhood character changes  
   - **Hard**: Nearly identical border areas

### Implementation Plan
1. **Border Point Research**
   - Map exact Tel Aviv city boundaries
   - Identify interesting transition zones
   - Research architectural/urban planning differences
   - Find GPS coordinates for optimal viewpoints

2. **Image Collection**
   - Use Google Street View Static API
   - Collect images at specific coordinates and headings
   - Store images locally in `resources/public/images/`
   - Create metadata file linking images to locations

3. **Game Integration**
   - Replace placeholder system with curated images
   - Add location metadata for post-game learning
   - Implement image preloading for smooth gameplay
   - Add "reveal location" feature after answers

### Data Structure
```
resources/
├── images/
│   ├── tel-aviv-ramat-gan/
│   │   ├── easy/
│   │   ├── medium/
│   │   └── hard/
│   └── tel-aviv-holon/
│       ├── easy/
│       ├── medium/
│       └── hard/
└── image-metadata.edn  ; Coordinates, difficulty, correct answers
```

## 📦 Phase 5: Polish & Educational Features
### Goals
- Add educational value with location reveals
- Implement achievement system
- Add map visualization
- Performance optimization

### Features
1. **Educational Elements**
   - Show actual locations on map after each answer
   - Neighborhood history and facts
   - "Learn more" links to local information

2. **Gamification**
   - Streak achievements
   - Difficulty completion badges
   - Local knowledge scoring
   - Leaderboard for Tel Aviv experts

3. **User Experience**
   - Tutorial for new players
   - Hint system for difficult pairs
   - Progress tracking across sessions
   - Social sharing of scores

## 🛠️ Development Workflow

### Start Development Server
```bash
cd border-blur
clojure -M:run 3001
# Visit http://localhost:3001
```

### Run REPL for Testing
```bash
clojure -A:nrepl
```

### Key REPL Commands
```clojure
;; Load GIS functions
(require '[geo.jts :as jts] 
         '[geo.spatial :as spatial])

;; Test city boundaries
(require '[border-blur.gis.cities :as cities])
(def tel-aviv (cities/get-city cities/cities :tel-aviv))

;; Test game logic
(require '[border-blur.game :as game])
(def session (game/new-game "London"))
(game/save-game-session! session)

;; Test image selection
(require '[border-blur.images.selector :as selector])
(selector/generate-smart-image-pair "Tel Aviv" 5 20)
```

## 🔌 Extension Points

### Add New Cities
1. Edit `resources/cities/expanded-cities.edn`
2. Add city with `:name`, `:country`, `:center`, `:neighbors`, `:boundary`
3. Boundary should be closed polygon `[[lng lat] ...]`

### Add New Image APIs
1. Add configuration to `fetcher.clj` `api-configs`
2. Implement new `fetch-images-near` method
3. Add to fallback chain in `fetch-from-multiple-sources`

### Customize Difficulty
Edit `selector.clj`:
- Change difficulty thresholds (stages 5, 15)
- Adjust same/different ratio (currently 60/40)
- Modify user city prioritization

## 📝 Implementation Notes

### GIS Library (factual/geo)
- Load with: `(require '[geo [jts :as jts] [spatial :as spatial]])`
- Create points: `(jts/point lng lat)`
- Calculate distance: `(spatial/distance point1 point2)` (returns meters)
- Test containment: `(spatial/intersects? polygon point)`

### Session Management
- No cookies - sessions tracked by URL
- Session ID in path: `/game/{session-id}`
- Sessions stored in atom: `@game/game-sessions`
- Auto-generate UUID: `(str (java.util.UUID/randomUUID))`

### Image APIs
- **OpenStreetCam**: No API key required, good for testing
- **Mapillary**: Requires API key, best coverage
- **Flickr**: Requires API key, user photos

### Architecture Principles
- **Immutable State**: All game state updates return new state
- **Pure Functions**: Border detection and GIS calculations are pure
- **Fail Gracefully**: Multiple API fallbacks, placeholder images
- **Cache Aggressively**: Border points and image URLs cached

## 🎯 Next Steps
1. Create `resources/api-keys.edn` with your API keys
2. Test image fetching with real coordinates
3. Update handlers to use real images instead of placeholders
4. Add loading states and error handling
5. Test with real users

## 🚧 Known Issues
- Factual/geo requires specific namespace loading pattern
- Some compilation warnings about docstrings (cosmetic)
- Image APIs may have rate limits
- Need real polygon data for accurate borders

## 📊 Success Metrics
- Game loads in < 2 seconds
- Images load in < 1 second
- 90% of border points have available images
- Players achieve 60-80% accuracy (not too easy, not too hard)
- Sessions persist for at least 1 hour