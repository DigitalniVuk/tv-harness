# Palette's Journal - Critical Learnings Only

## 2026-11-09 - Overlay Sheet Async Loading and Accessibility
**Learning:** In TV / 10-foot modal sheets (like player episode pickers), asynchronous web evaluation or network fetches can leave horizontal scroll areas visually empty or showing static plain text without an active spinner or screen-reader announcement, confusing remote users as to whether the sheet is responsive.
**Action:** When an overlay container opens with pending data, immediately mount an indeterminate ProgressBar with explicit content description alongside a status label so users receive clear visual and assistive feedback.

## 2026-11-08 - Accessible Async Loading Feedback in TV Navigation
**Learning:** In TV / 10-foot interfaces, clearing scroll container content during network fetches without an in-viewport progress indicator causes disorientation, as users cannot easily perceive background loading status compared to desktop/mobile.
**Action:** Always provide an in-content indeterminate ProgressBar with semantic accessibility description (`status.getText()`) and contextual feedback alongside top-bar status changes.
