# Bolt's Performance Journal

## 2026-11-07 - Pre-sizing large collections from bundled assets
**Learning:** Loading static asset files with tens of thousands of lines (such as `assets/blocking-domains.txt` with ~47.5k domains) into a default `HashSet` causes repeated table resizing and rehashing (~12 cycles), inflating memory allocations and doubling initial loading time.
**Action:** When populating sets/maps from large bundled resources with known approximate sizes, always pre-size the collection (`new HashSet<>(65536)`) and enlarge the `BufferedReader` buffer (e.g. 32KB).
