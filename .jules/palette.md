# Palette's Journal - Critical Learnings Only

## 2026-11-08 - Accessible Async Loading Feedback in TV Navigation
**Learning:** In TV / 10-foot interfaces, clearing scroll container content during network fetches without an in-viewport progress indicator causes disorientation, as users cannot easily perceive background loading status compared to desktop/mobile.
**Action:** Always provide an in-content indeterminate ProgressBar with semantic accessibility description (`status.getText()`) and contextual feedback alongside top-bar status changes.
