# Share storage without sharing menu sessions

Each viewer will retain an independent menu session, including navigation and component state, while a menu storage model may expose one stable identity and revision across sessions. Transaction locks and commits operate on that shared identity, accepted snapshots notify every attached session, and stale revisions are rejected. Per-viewer rendering remains available for locale, permissions, and presentation without duplicating authoritative storage state.
