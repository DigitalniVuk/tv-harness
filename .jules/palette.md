# Palette's Journal - Critical Learnings Only

## 2026-11-11 - Interactive Focus Scale and Accessible Context for 10-Foot Grid Buttons
**Learning:** In 10-foot TV interfaces navigated via D-pad remotes from a distance, flat text buttons in dense discovery grids (such as genre/category grids) are hard to distinguish without depth-based focus scaling, and screen readers read isolated label tokens without context on the interaction outcome.
**Action:** Pair subtle scale transitions (`scaleX`/`scaleY` on focus) with explicit contextual `contentDescription` attributes ("Category: [title], browse titles") to give remote users both instant visual depth feedback and screen-reader clarity.

## 2026-11-10 - Dynamic Status Announcements in 10-Foot TV UIs
**Learning:** In 10-foot TV interfaces where navigation focus resides within remote-controlled grids or action bars, status subheadings that dynamically update (such as catalog loading progress, category changes, or error states) are invisible to screen readers unless configured with an accessibility live region.
**Action:** Configure dynamic persistent status indicators with `setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)` so assistive technologies announce loading transitions and error notices without hijacking user focus.

## 2026-11-09 - Overlay Sheet Async Loading and Accessibility
**Learning:** In TV / 10-foot modal sheets (like player episode pickers), asynchronous web evaluation or network fetches can leave horizontal scroll areas visually empty or showing static plain text without an active spinner or screen-reader announcement, confusing remote users as to whether the sheet is responsive.
**Action:** When an overlay container opens with pending data, immediately mount an indeterminate ProgressBar with explicit content description alongside a status label so users receive clear visual and assistive feedback.

## 2026-11-08 - Accessible Async Loading Feedback in TV Navigation
**Learning:** In TV / 10-foot interfaces, clearing scroll container content during network fetches without an in-viewport progress indicator causes disorientation, as users cannot easily perceive background loading status compared to desktop/mobile.
**Action:** Always provide an in-content indeterminate ProgressBar with semantic accessibility description (`status.getText()`) and contextual feedback alongside top-bar status changes.
