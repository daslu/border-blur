# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Clojure project called "border-blur" that appears to be a GIS/geospatial application with web capabilities. It uses deps.edn for dependency management and includes libraries for geographic operations, data processing, HTTP communication, and web serving.

## Key Dependencies

- **GIS/Geospatial**: factual/geo for geographic operations
- **Data Science**: org.scicloj/noj for mathematical and data science operations
- **Web Framework**: Ring/Compojure for HTTP server and routing, Hiccup for HTML generation
- **Data Handling**: Cheshire for JSON processing, clj-http for HTTP client functionality

## Development Commands

### Start a REPL
```bash
clj -M:nrepl
```
This starts an nREPL server on port 7888 with test and resource paths included.

### Run the Application
```bash
clj -M:run
```
This runs the main application via the `border-blur.core` namespace.

### Run Tests
```bash
clj -M:test
```
Uses cognitect.test-runner to execute tests.

### Development REPL
```bash
clj -M:dev
```
Includes resources and tools.namespace for interactive development with code reloading.

## Project Structure

- `src/` - Source code directory (currently empty, needs to be populated)
- `test/` - Test directory
- `resources/` - Resource files (included in :dev and :run aliases)
- `deps.edn` - Project dependencies and aliases configuration

## Architecture Notes

This appears to be a greenfield project focused on geospatial/GIS operations with web capabilities. The dependency choices suggest:

1. **Geospatial Processing**: The factual/geo library indicates geographic calculations and spatial operations are central to the application
2. **Data Science Integration**: NOJ (org.scicloj/noj) suggests mathematical computations and potentially data visualization
3. **Web Service**: Ring/Compojure setup indicates this will serve HTTP endpoints, possibly for a geospatial API or web application
4. **Data Formats**: Support for JSON through multiple libraries (Cheshire, data.json) suggests JSON API endpoints or data exchange

## Development Workflow

1. Start development REPL with `clj -M:nrepl` for interactive development
2. Use tools.namespace for code reloading during development
3. The main entry point is `border-blur.core` namespace (to be created)
4. Test using `clj -M:test` with cognitect.test-runner