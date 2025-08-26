# Border Blur - Implementation Plan

## Game Concept (Redesigned)

**"Is it in Tel Aviv?"** - A geography learning game where players view a single street-view image and decide whether it's located within Tel Aviv city boundaries. After each answer, the game immediately reveals the truth with a map showing the actual location.

### Core Gameplay
- **Single image per screen** with binary choice: "Yes, it's in Tel Aviv" / "No, it's not in Tel Aviv"
- **Immediate reveal** showing correct answer + interactive map with actual location
- **Progressive difficulty** from obvious city centers to tricky boundary areas
- **Educational focus** on Tel Aviv geography and neighboring city recognition

## Architecture Overview

### Reusable Components (80% of existing code)
- ✅ **Web Stack**: Ring/Compojure/Hiccup server architecture
- ✅ **Session Management**: Cookie-free URL-based sessions (`/game/{session-id}`)
- ✅ **Image Infrastructure**: Mapillary API integration with quality filtering
- ✅ **Spatial Algorithms**: Diversity sampling for border point generation
- ✅ **City Data**: Israeli city boundaries and neighbor relationships
- ✅ **GIS Operations**: Point-in-polygon testing with factual/geo

### New Components
- 🆕 **Map Integration**: Leaflet.js for location reveal
- 🆕 **Binary Game Logic**: Yes/No scoring instead of pair comparison
- 🆕 **Reveal UI**: Answer feedback with animated map transitions
- 🆕 **Tel Aviv Focus**: Specialized image selection for boundary learning

## Implementation Phases

### Phase 1: Core Game Logic Redesign (2-3 hours)
**Files to modify:**
- `src/border_blur/game.clj` - Binary scoring system
- `src/border_blur/handlers.clj` - Answer → reveal → next flow

**Changes:**
```clojure
;; New game state structure
{:session-id "uuid"
 :current-stage 5
 :score 85
 :streak 3
 :current-image {:url "..." :coords [34.85 32.05] :is-in-tel-aviv true}
 :answer-revealed false
 :total-stages 20}

;; New scoring logic
(defn calculate-points [correct? difficulty streak]
  (let [base-points (if correct? 10 0)
        difficulty-bonus (case difficulty :easy 0 :medium 5 :hard 10)
        streak-multiplier (min 2.0 (+ 1.0 (* streak 0.2)))]
    (* (+ base-points difficulty-bonus) streak-multiplier)))
```

### Phase 2: Image Selection Strategy (1-2 hours)
**Files to modify:**
- `src/border_blur/images/selector.clj` - Single image focus
- `src/border_blur/images/pair_curator.clj` - Adapt for Tel Aviv boundaries

**Strategy:**
- **60% Tel Aviv images** (within city boundaries)
- **40% Non-Tel Aviv images** (from Ramat Gan, Holon, Givatayim, Bnei Brak)
- **Progressive difficulty**:
  - Easy (stages 1-7): Clear city centers vs distant neighbors
  - Medium (stages 8-15): Suburban areas vs close neighbors  
  - Hard (stages 16-20): Border zones and tricky boundary cases

### Phase 3: UI & Map Integration (3-4 hours)
**Files to modify:**
- `src/border_blur/views.clj` - Single image layout + map component
- `resources/public/style.css` - Map styling and reveal animations
- Add `resources/public/js/map.js` - Leaflet.js integration

**Map Features:**
```javascript
// Reveal animation sequence
1. Show answer (correct/incorrect feedback)
2. Fade in map with Tel Aviv boundaries
3. Animate to actual image location
4. Show marker with neighborhood info
5. "Next Image" button appears
```

**UI Layout:**
```
┌─────────────────────────────────────┐
│ Stage 12/20    Score: 165    🔥 x3  │
├─────────────────────────────────────┤
│                                     │
│        [Street View Image]          │
│                                     │
├─────────────────────────────────────┤
│        Is it in Tel Aviv?           │
│    [Yes, it is] [No, it isn't]     │
└─────────────────────────────────────┘

// After answer:
┌─────────────────────────────────────┐
│ ✓ Correct! +15 points               │
├─────────────────────────────────────┤
│ [Map showing actual location]       │ 
│ 📍 Rothschild Blvd, Tel Aviv       │
├─────────────────────────────────────┤
│           [Next Image]              │
└─────────────────────────────────────┘
```

### Phase 4: Enhanced Location Data (1-2 hours)
**Files to modify:**
- `resources/cities/israeli-cities.edn` - Add neighborhood boundaries
- `src/border_blur/gis/cities.clj` - Location name resolution

**Data Enhancements:**
- Tel Aviv district boundaries (Dizengoff, Rothschild, Florentin, etc.)
- Street-level address resolution for images
- Landmark and POI identification near image locations

## Technical Specifications

### Map Integration (Leaflet.js)
```html
<!-- In HTML head -->
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
```

```javascript
// Map configuration
const map = L.map('reveal-map').setView([32.0853, 34.7818], 12);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png').addTo(map);

// Tel Aviv boundary overlay
const telAvivBoundary = L.geoJSON(boundaryData, {
  style: { color: '#2196F3', weight: 3, fillOpacity: 0.1 }
}).addTo(map);

// Reveal animation
function revealLocation(coords, isInTelAviv) {
  const marker = L.marker(coords).addTo(map);
  const markerColor = isInTelAviv ? 'green' : 'red';
  map.flyTo(coords, 16, { duration: 1.5 });
}
```

### Game Flow State Machine
```clojure
;; State transitions
:showing-image → (user answers) → :revealing-answer → (user clicks next) → :showing-image

;; Session progression
(defn process-answer [session-id answer]
  (let [session (get-session session-id)
        correct? (= answer (:correct-answer (:current-image session)))
        new-score (calculate-score session correct?)
        next-stage? (< (:current-stage session) 20)]
    (if next-stage?
      (-> session
          (update-score new-score correct?)
          (advance-stage)
          (load-next-image))
      (complete-game session new-score))))
```

### Difficulty Progression Algorithm
```clojure
(defn select-image-by-difficulty [stage]
  (let [difficulty (cond (< stage 8) :easy
                        (< stage 16) :medium  
                        :else :hard)
        tel-aviv-probability (case difficulty
                              :easy 0.6    ; Clear cases
                              :medium 0.6  ; Suburban mix
                              :hard 0.5)]  ; Boundary confusion
    (if (< (rand) tel-aviv-probability)
      (select-tel-aviv-image difficulty)
      (select-neighbor-image difficulty))))
```

## Development Timeline

- **Phase 1** (Core Logic): 2-3 hours
- **Phase 2** (Image Selection): 1-2 hours  
- **Phase 3** (UI/Map): 3-4 hours
- **Phase 4** (Enhanced Data): 1-2 hours
- **Testing & Polish**: 2-3 hours

**Total Estimated Time**: 9-14 hours

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
;; Load GIS functions (specific pattern required)
(require '[geo [jts :as jts] [spatial :as spatial]])

;; Test city boundaries
(require '[border-blur.gis.cities :as cities])
(def tel-aviv (cities/get-city cities/cities :tel-aviv))

;; Test new binary game logic
(require '[border-blur.game :as game])
(def session (game/new-game "Tel Aviv"))
(game/save-game-session! session)

;; Test single image selection
(require '[border-blur.images.selector :as selector])
(selector/generate-single-image "Tel Aviv" 5 20)
```

## 🔌 Extension Points

### Add New Cities for Non-Tel Aviv Images
1. Edit `resources/cities/israeli-cities.edn`
2. Focus on Tel Aviv neighbors: Ramat Gan, Holon, Givatayim, Bnei Brak
3. Ensure good boundary data for accurate point-in-polygon testing

### Add Map Features
1. Tel Aviv neighborhood boundaries overlay
2. Historical/cultural points of interest markers
3. Street name and district information
4. Photo attribution and metadata display

### Customize Difficulty Progression
Edit `selector.clj`:
- Adjust stage thresholds for easy/medium/hard
- Fine-tune Tel Aviv vs neighbor image ratios
- Add complexity scoring based on visual similarity

## 📝 Implementation Notes

### GIS Library (factual/geo)
- Load with: `(require '[geo [jts :as jts] [spatial :as spatial]])`
- Test Tel Aviv containment: `(spatial/intersects? tel-aviv-polygon point)`
- Calculate border distances for difficulty scoring

### Session Management  
- Maintain cookie-free URL sessions: `/game/{session-id}`
- New state: `:answer-revealed` for reveal screen
- Track Tel Aviv geography learning progress

### Map Integration
- **Leaflet.js**: Lightweight, well-documented mapping library
- **OpenStreetMap tiles**: Free base layer, good Tel Aviv coverage
- **Reveal animations**: Smooth pan/zoom to actual location
- **Responsive design**: Works on mobile devices

## Success Metrics

### Gameplay Experience
- ✅ Smooth answer → reveal → next flow (< 2 second transitions)
- ✅ Educational value: Players learn Tel Aviv geography
- ✅ Progressive challenge: 70%+ accuracy on easy, 40%+ on hard
- ✅ Visual appeal: Clean single-image layout with satisfying reveals

### Technical Performance  
- ✅ Fast image loading (cached street-view images)
- ✅ Responsive map integration (works on mobile)
- ✅ Maintained session reliability (no data loss)
- ✅ Reuses 80% of existing infrastructure

## 🎯 Next Steps
1. **Phase 1**: Modify game logic for binary Yes/No scoring
2. **Phase 2**: Adapt image selection for single-image Tel Aviv focus
3. **Phase 3**: Integrate Leaflet.js map with reveal animations
4. **Phase 4**: Enhance location data with neighborhoods and landmarks
5. **Testing**: Verify educational value and difficulty progression

This redesigned Border Blur focuses on educational Tel Aviv geography recognition while leveraging the robust infrastructure already built for image collection, spatial diversity, and game session management.