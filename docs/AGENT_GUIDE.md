# Agent guide — plug any website into the harness

Read `docs/API.md` (endpoints + JS contract) and `docs/REQUIREMENTS.md`
(site prerequisites) before starting. The whole job is editing
`src/tv/harness/SiteConfig.java`. Do not edit any other file. If you think
you must, stop: the harness already handles your case, or you have found
a real limitation — note it in the harness plan under "Phase 4".

## Step 0 — Copy the template

`src/tv/harness/SiteConfig.java` is already the template; it ships filled
with `example.com` TODOs. Keep a pristine copy (e.g. `git checkout
SiteConfig.java` restores it). Never commit a filled-in real site's values
back as the template.

> **Fork discipline (learned the hard way).** Every site test lives in
> its own repo (seeded copy of this harness `main`; GitHub forbids a
> same-account fork — see `docs/HARNESS_PLAN.md`). Always resolve
> **absolute paths** before writing into a fork dir, and verify after
> each edit that this main repo is still clean
> (`git -C <main> status` must be empty).

## Step 0.5 — Classify the site first: HTML cards or RSC JSON?

Before filling any selector, fetch the listing page with `curl` and look
at the raw HTML (learned from a Next.js site fork):

- Items in HTML `<div>` blocks → the bundled HTML scraper works.
- No cards in HTML, but a 1 MB+ page full of `self.__next_f` payloads
  (React Server Components flight data) → the catalogue lives in JSON.
  The bundled scraper cannot read that; a fork may add a JSON mode
  (bracket-match the rail's `"items":[...]` array, parse with
  `org.json.JSONArray`). Propose upstreaming it as a general
  `ITEM_FORMAT` mode rather than keeping fork code.

**Unescape order (the trick):** RSC payloads encode backslash *and*
quote (`\\\""`). Decode as `\\` → NUL, `\"` → `"`, NUL → `\`.
Decoding `\"` first leaves a stray backslash that re-escapes the next
quote and corrupts the JSON (mid-array parse errors).

**Verify candidate routes with a real HTTP request** before trusting
them: JSON fields named `homepage` (e.g. Netflix `/title/<id>` ids) and
studio links routinely look like site routes but 404. `sitemap.xml`
is a better source of truth.

## Step 1 — Fix identity and origin

1. `DISPLAY_NAME` -> the site's public name.
2. `BASE_URL` -> `https://<host>` (no trailing slash). `HOME_PATH` -> the
   site's home page path.
3. Add every first-party host (site + any media/CDN host the player uses)
   to `FIRST_PARTY_HOSTS`, and every poster host prefix to
   `ALLOWED_IMAGE_PREFIXES`. Missing a media host = blocked playback;
   missing a poster host = blank cards.

## Step 2 — Fill the catalogue endpoints

For each page you want a tab for, note its path and add it to `TABS`
(keep `genres` with `path == null`, keep the `home` id first — see the
tab-id contract in `API.md`). Two tabs may share a path; the harness
fetches each tab separately.

Verify each page: `curl -s -A '<USER_AGENT>' <BASE_URL><path>` and check
items appear in the raw HTML.

## Step 3 — Fill item extraction

From one saved listing page, find one item's HTML in the browser
(View Source, search for a known title) and set:

- `ITEM_CLASS` — the whole-word class on the item's outer `<div>`
  (harness scans `<div>` blocks by balanced depth; `<li>`/`<article>`
  items are NOT supported — Phase 4).
- `ITEM_LINK_PATH` — the substring every item link contains (e.g.
  `/watch/`). The first such anchor in the block becomes the item URL.
- `ITEM_TITLE_CLASSES` — anchor class(es) holding the title.
- Poster: first `<img>` in the block (`data-src` then `src`).
- `ITEM_TYPE_REGEX` / `ITEM_RATING_REGEX` / `ITEM_EPISODES_REGEX` — copy
  the surrounding HTML, replace it with a regex whose group 1 is the text
  you want, using `(?is)` and non-greedy `.*?`. Or set `""` and remove
  the element from the card entirely. Keep `ITEM_DEFAULT_TYPE` as the
  fallback label.

Validation: paste one item block + each regex into any regex tester
(Python `re` with `re.I|re.S` matches Java here) and confirm group 1.

## Step 4 — Categories (skip if the site has none)

Set `CATEGORY_DISCOVERY_PATH` (usually `/home` or a genre index page)
and `CATEGORY_PATH_PREFIX` (e.g. `/genre/`). The harness reads slugs and
labels off anchors on that page. Category pages are parsed with the same
item rules as Step 3. No categories? Delete everything except the
`path == null` tab trick, or point the discovery path at a page with no
matching anchors (empty grid is handled).

## Step 5 — Fill the player

Open the item/player page in a desktop browser and inspect. **Prefer
live DOM inspection** (element class chains on the target) over reading
minified JS chunks — for generated-class (Tailwind) sites the most
specific *stable class combination* is usually the best hook, and
labels that change per state (`Play` / `Resume` / `Play in 4K`) are not.

Also check what the site does to `window.open` / first click (ad
scripts commonly wrap it) before assuming popups are off — the
harness stubs `window.open`/`alert`/`confirm`, and that is load-bearing
on sites with popup hijackers.

- `PLAYER_HIDE_CSS` — selectors for header/footer/sidebar/comments/ads.
  Everything hidden is rebuilt by the harness's own OSD.
- `PLAYER_KEEP_SELECTORS` / `PLAYER_ELEMENT_SELECTOR` /
  `PLAYER_FRAME_SELECTOR` — the player container chain. The harness
  force-fills and 16:9-centres these.
- `PLAYER_FOCUS_SELECTOR` — the site's play button (harness focuses it
  on load so D-pad OK starts playback).
- `EPISODE_*` / `PREV_` / `NEXT_` / `AUTOPLAY_` selectors — the section
  picker and transport controls. If the site has no sections, point
  `EPISODE_LIST_SELECTOR` at something nonexistent (e.g. `"#none"`) —
  the picker stays inert instead of guessing.
- `PAUSE_TOGGLE_SELECTOR` — clickable pause control if the site has one,
  else `""` (the harness dispatches Space; verify the site pauses on
  Space in a real WebView).

Then actually test in the WebView (build + `adb install`, or the
emulator): catalogue renders, card OK opens the player, video plays,
prev/next and the picker (if any) work, back returns to the catalogue.

## Step 5.5 — Cross-origin players: go direct + watchdog

The hardest case so far (`docs/HARNESS_PLAN.md` Phase 4 task T6): the
site plays video in a **cross-origin iframe** (e.g. a separate embed
host). Page JS can *click nothing* inside it — not the player's Play,
server switch, or mute. The proven bypass, in order:

1. **Read `iframe.src`** from the wrapping page (attribute reads are
   always allowed cross-origin) and **navigate the WebView straight to
   that URL**. The embed is then the top document, every control is
   same-origin, and the harness's own `evaluateJavascript` clicks and
   state probes just work. Embed URLs contain everything they need
   (`autoplay=true`, `server=...`). BACK still returns to the details
   page (WebView history), and BROWSE exits to the catalogue.
2. **First-party hosts:** add the embed host *and its media-API host*
   to `FIRST_PARTY_HOSTS` (plus subtitle/thumbnail hosts). If the media
   API is ad-blocked, playback dies silently — no error, just black.
3. **Watchdog:** poll the embed page every ~2 s for video progress
   (`video.currentTime > 0.2`). While it is stuck and the player's own
   "try another" control is offered, click it (cycle server) with
   backoff (first after ~20 s, then ~12 s), bounded (≤10), with toasts
   — then one honest *"no working server found"* and stop.
4. **Sound:** embed players start muted (autoplay policy). Auto-click
   the volume control once playback starts, and add an OSD **SOUND**
   button for the manual toggle. Verification trick: `video.muted`
   state via a JS probe, not screenshots.
5. Drive pause via the embed page's own play/pause control, and keep
   the OSD PAUSE/PLAY button pointing at the site's start button on
   the *wrapper* page (the OSD consumes D-pad centre — it can only see
   top-document controls).

This mode shipped as a config-driven fork patch
(`DIRECT_EMBED_HOST`, `STALL_*`, `SOUND_CONTROL_SELECTOR`,
`EMBED_PLAY_SELECTOR`); upstreaming it as generic harness code is
Phase 4 in `docs/HARNESS_PLAN.md`.

## Step 6 — Validate the no-leak rule

Run `./scripts/check-site-refs.sh`. It must pass. Any failure means a
site string leaked outside `SiteConfig.java` — move it into the config;
if the config has no home for it, that is a Phase-4 harness change, not
a local hack.

## Common failures

| Symptom | Likely cause |
|---|---|
| Empty catalogue, no error | Listing page renders items via JS, or `ITEM_CLASS`/`ITEM_LINK_PATH` wrong. Re-check with `curl` raw HTML. If JSON mode needed, see Step 0.5. |
| Remote OK does nothing on the player | Harness OSD consumes D-pad centre (by design). Drive the site's start button through `PAUSE_TOGGLE_SELECTOR` (for modal-detail sites, point it at the site's Play button). |
| Details page focuses nothing | SPA renders late: the focus watcher now retries ~4 min and scrolls, but only if `PLAYER_FOCUS_SELECTOR` matches the real Play control. |
| Player stalls black (no video) | Check the media API response first (desktop browser + `curl`) before blaming the WebView — geo-gated media servers return empty sources per region. |
| Cards without posters | Poster host missing from `ALLOWED_IMAGE_PREFIXES`, or poster uses `srcset` (unsupported — pick a different `<img>` or extend the scraper; Phase 4). |
| Player shows site chrome | `PLAYER_KEEP_SELECTORS`/`PLAYER_HIDE_CSS` incomplete. |
| Video does not play | Missing media host in `FIRST_PARTY_HOSTS`, or `PLAYER_FRAME_SELECTOR` empty/wrong so the video sits at 0×0. |
| Pause button dead | Site ignores Space; set `PAUSE_TOGGLE_SELECTOR` to its pause control. |
| Episode picker empty forever | Wrong `EPISODE_LIST_SELECTOR`/`EPISODE_NUM_ATTR`; devtools the anchors and re-copy. |
| Video plays but muted, controls unreachable | Embed starts muted (autoplay policy) and lives cross-origin: go direct (Step 5.5), then OSD SOUND button + watchdog auto-unmute. |
| Black video that never starts | Media API geo-gate or dead source. Check the API response from the *test* network first; remember the TV's residential IP may be served while your datacenter/VPN IP is not. |
| Brand label missing in top bar | >5 tabs squeeze it out (fixed-width tabs) — keep tabs ≤5 until the scrollable-tab-bar fix lands (Phase 4). |
| TV seasons not switchable | Sites often drive seasons from a `<select>`; the harness has no control seam yet (Phase 4 task T4). Start-of-playback default applies — document it in the fork. |

## Field notes — bypasses, tricks, workarounds (from the 67movies.st fork)

Distilled from 14+ investigated issues on one real plug-in. Each entry
is what actually broke, and the shortcut that fixed it. The full log
lives in that fork's `docs/ISSUES.md`.

**Catalogue**
- RSC/Next.js sites render cards client-side from flight JSON in the
  HTML. `curl` the raw HTML first: no cards + `self.__next_f` payloads
  = JSON mode needed (step 0.5), *not* more selector guessing.
- Ordered unescape `\\` → NUL, `\"` → `"`, NUL → `\`. Wrong order
  corrupts JSON mid-array ("Expecting ',' delimiter").
- Bracket-match `"items":[` string-aware (mind escaped quotes), then
  `org.json.JSONArray`. Some rails in the payload are *references*
  (`"items":"$3:props:..."`) with no data — pick inline rails only.
- TRAP: JSON fields named `homepage` (Netflix `/title/<id>` ids) and
  studio links masquerade as site routes and 404. Verify every
  candidate route with a real HTTP request; `sitemap.xml` is truth.
- Posters: construct the image-CDN URL (`image.tmdb.org/t/p/w500`)
  from the path field; allowlist that host. RSC pages may also proxy
  images (`/_next/image?url=...`) — prefer the direct CDN.
- Detail URLs can be **data-driven** (`/details/{type}/{id}` from JSON
  `id` + `type`): one rule set covers the whole catalogue.

**Player**
- Cross-origin iframe == no clicks inside, ever. Direct-embed
  navigation (read `iframe.src`, load it in the WebView) is *the* fix.
  Reading `src` is never blocked.
- The OSD consumes D-pad centre by design. Route every "start" action
  through `PAUSE_TOGGLE_SELECTOR` (site Play button) or a dedicated
  OSD button. For direct-embed pages, point pause at the player's own
  play/pause (`button[title='Play / pause']`).
- Stuck-media watchdog: poll `video.currentTime`; when the player's
  "try another" control is offered and nothing progresses, click it
  (cycle server), bounded, with toasts. Honest failure beats a black
  screen forever.
- Sound: players start muted. Auto-click the volume control once
  playback starts + give users an OSD SOUND button. Prove sound state
  with a `video.muted` probe, not screenshots.
- First-party hosts must cover *everything the player touches*:
  embed host, media API host, subtitle/thumbnail hosts. A blocked API
  costs you the video with no error anywhere.
- Some sites detect datacenter/VPN IPs and withhold media (`source:
  null` from curl, a "VPN can block this player" banner). Debug from
  the *TV* (residential IP), not the desktop. Always validate the
  media API response before blaming the WebView.

**UI/focus**
- The focus watcher retries ~4 min + scrolls (SPA pages render late).
  Still pin it to the real Play control's most stable class combo —
  play-button *labels* mutate (`Play`/`Resume`/`Play in 4K`).
- Tab bar overflow: >5 tabs squeeze the brand to zero. Keep ≤5 tabs
  until the scrollable row lands.
- Episode selectors empty = disabled (OSD Episodes button hides — the
  harness implements this now). Don't point at a fake selector.

**Process**
- Fork discipline: absolute paths when writing in a fork dir, and
  verify main `git status` is clean after *every* edit — relative
  paths poison main once (recovered this way).
- Same-account GitHub forks are impossible; site tests are copies of
  harness `main` with a seed commit. Improvements flow one way:
  generic fix + docs, never site config.
- DevTools class chains on the *live* DOM beat reading minified chunks
  for generated-class (Tailwind) sites.
- Check what the site does to `window.open` before assuming popups
  are off — the harness stub is load-bearing against hijackers.
- 401 from an account API (`/api/watchlist`) is expected without
  login; don't chase it.
