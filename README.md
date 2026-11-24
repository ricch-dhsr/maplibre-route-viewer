# MapLibre Route Viewer v0.1 — initial source prototype

This is a **source project, not a compiled or device-tested APK**.

## What it tests
- 20 numbered marker images inside circles, including very close pairs.
- `iconAllowOverlap(true)` and `iconIgnorePlacement(true)` in a MapLibre SymbolLayer.
- A route polyline from `geometry` in `route_demo.json`.
- Separate standalone Android package; does not change OsmAnd or Tasker.

## Important limitations
- **Offline maps are NOT implemented in v0.1.** The public MapLibre demo style needs internet. Do not interpret a cached display as an offline success.
- `geometry` is presently a straight demo polyline joining sample stops, **not HERE road geometry**.
- HERE data import and Tasker intent integration are not implemented yet.
- Offline test will require an explicit tile download/region management step and validation after app restart with Wi-Fi and mobile data off.

## Build
1. Upload contents of this project to a new GitHub repository (or use Android Studio).
2. GitHub Actions → Build MapLibre Route Viewer → Run workflow.
3. Download the debug APK from the workflow artifact and install on a test tablet.
4. Check whether paired numbered icons remain visible when zooming and panning.

## Data format for next stage
`app/src/main/assets/route_demo.json`:
- `stops`: array of `{number,address,lat,lon}`
- `geometry`: array of `[longitude,latitude]` coordinates along the road, in travel order (GeoJSON order)

Do not replace `geometry` with stop coordinates and call it road-following. The next version will accept a HERE GPX or GeoJSON route and store it locally.
