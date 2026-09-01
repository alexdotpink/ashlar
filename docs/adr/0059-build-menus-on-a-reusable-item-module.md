# Build menus on a reusable item module

Reusable item construction will live in `ashlar-items`, which owns immutable `ItemSpec` values, Kotlin construction, stack snapshots, persistent-data helpers, full metadata access, and typed support for Paper's current data-component API. `ashlar-menus` will depend on it and apply a decorative presentation profile by default only to virtual action-slot items; storage, purchasable, hotbar, and domain items keep neutral vanilla presentation unless explicitly changed.
