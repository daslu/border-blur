# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Border Blur is a fast-paced geography game where players identify whether two street-view images are from the same city or different cities. The game focuses on border areas between cities where visual cues are subtle, making the game challenging. Built with Clojure, it uses Ring/Compojure for the web framework, factual/geo for GIS operations, and maintains cookie-free session management via URL paths.

## Common Development Commands

### Development with Hot Reloading (Recommended)

**Start REPL for Development:**
```bash
clojure -A:nrepl
```
Starts an nREPL server on port 7888 with hot reloading support.

**In the REPL:**
```clojure
;; Load development namespace
(require '[border-blur.dev :as dev])

;; Start server (default port 3001)
(dev/start)
;; Or with custom port
(dev/start 3000)

;; Reload changed code and restart server
(dev/rr)
;; Or just reload without restart
(dev/r)

;; Stop server
(dev/stop)

;; Reset everything (reload all code)
(dev/reset)
```

### Direct Server Start (without hot reloading)
```bash
clojure -M:run 3001
```
Starts the web server on port 3001 (default port is 3000 if not specified).

### Run Tests
```bash
clojure -A:test -m cognitect.test-runner
```

### Development Mode with Resources
```bash
clojure -A:dev
```
Includes the resources directory in the classpath for interactive development.

## Architecture

### Core Namespaces

**`border-blur.core`**
- Main server setup using Ring/Jetty
- Cookie-free routing with session IDs in URL paths (`/game/{session-id}`)
- Routes: home, start-game, game page, answer submission, results

**`border-blur.game`**
- Session management using atoms (no cookies)
- Score calculation with streak bonuses
- 20-stage game progression
- UUID-based session generation

**`border-blur.handlers`**
- HTTP request processing
- Session validation and redirection
- Game state transitions

**`border-blur.views`**
- Hiccup HTML generation
- Responsive UI with gradient backgrounds

### GIS & Image Components

**`border-blur.gis.core`**
- GIS operations using factual/geo library
- Point-in-polygon testing
- Distance calculations
- Important: Load with `(require '[geo [jts :as jts] [spatial :as spatial]])`

**`border-blur.gis.boundaries`**
- Border detection algorithms
- Finds closest approach points between city polygons
- Difficulty classification for border areas

**`border-blur.gis.cities`**
- City data loading from EDN files
- Neighbor relationships
- Polygon boundary management

**`border-blur.images.fetcher`**
- Multi-API support (Mapillary, OpenStreetCam, Flickr)
- Fallback chain for image fetching
- Note: OpenStreetCam requires no API key (good for testing)

**`border-blur.images.selector`**
- Smart image pair generation
- Progressive difficulty (easy < stage 5, medium < stage 15, hard >= stage 15)
- 60% different cities, 40% same city mix
- Caching for border points and images

## Key Algorithms & Features

**Border Detection**
- Finds "hotspots" where cities are closest
- Generates search grids around border points
- Classifies difficulty based on visual similarity

**Session Management**
- Cookie-free via URL paths: `/game/{session-id}`
- Sessions stored in `@game/game-sessions` atom
- Auto-generated UUIDs for session IDs

**Game Mechanics**
- 20 stages with progressive difficulty
- Scoring: 10 base points + streak bonus (max 10)
- Binary choice: Same City or Different Cities

## Data Files

**`resources/cities/israeli-cities.edn`**
- Initial city data (Tel Aviv, Ramat Gan, Jerusalem)

**`resources/cities/expanded-cities.edn`**
- 12+ cities with realistic polygon boundaries
- Each city has: `:name`, `:country`, `:center`, `:neighbors`, `:boundary`

## Development Workflow

### Testing GIS Functions
```clojure
;; Load GIS library (specific pattern required)
(require '[geo.jts :as jts] 
         '[geo.spatial :as spatial])

;; Test city boundaries
(require '[border-blur.gis.cities :as cities])
(def tel-aviv (cities/get-city cities/cities :tel-aviv))

;; Test border detection
(require '[border-blur.gis.boundaries :as boundaries])
(def border-points (boundaries/find-game-worthy-points 
                     tel-aviv ramat-gan 2000))
```

### Testing Game Logic
```clojure
(require '[border-blur.game :as game])
(def session (game/new-game "Tel Aviv"))
(game/save-game-session! session)
(game/process-answer (:session-id session) :same)
```

### Testing Image Selection
```clojure
(require '[border-blur.images.selector :as selector])
(selector/generate-smart-image-pair "Tel Aviv" 5 20)
```

## Current Implementation Status

**Phase 1-2 Complete:**
- Core game logic and session management
- GIS integration with factual/geo
- Border detection algorithms
- Multi-API image fetching framework
- Expanded city database

**Phase 3 In Progress:**
- Connecting image fetching to game flow
- Currently using placeholder images
- Need to integrate real API keys for production

## Configuration Requirements

**API Keys** (create `resources/api-keys.edn`):
- Mapillary: Best coverage, requires key
- OpenStreetCam: No key required
- Flickr: User photos, requires key

## Known Issues & Notes

- factual/geo requires specific namespace loading pattern shown above
- Some compilation warnings about docstrings (cosmetic)
- Image APIs may have rate limits
- Currently using placeholder images until API integration complete
- Sessions stored in memory (not persistent across server restarts)