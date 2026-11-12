# ZOHAR Walk AR (parked)

Walking-only AR navigation: pick a destination, walk with arrows on the street in the camera view and a map when the
phone lies flat; turn-by-turn voice and haptics, re-routing, Mapbox walking routes, optional ARCore Geospatial (Street
View VPS) for metre-level accuracy. Not a game, no play money.

Taken out of the casino build on purpose; the code is kept here as it was, to be picked up again later:
- `src/main/java/com/zohar/slotar/walk/` — the app (screens, route, navigator, sensors, AR maths)
- `src/walk/` — its manifest (location permission, ARCore geo key)
- `src/test/.../WalkTest.kt` — its unit tests
- `flavor.gradle.kts.txt` — the build flavor it had (needs the MAPBOX_TOKEN secret, optional GOOGLE_AR_KEY)

To build it again: move the three folders back under `app/`, paste the flavor back into `app/build.gradle.kts`, and add
the `walk` branch back in `MainActivity` (`WalkApp(...)`).
