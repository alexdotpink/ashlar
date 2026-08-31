# Give custom items typed identity, not gameplay behavior

The item module will define custom items through namespaced identity, typed persistent payloads, schema versions, migrations, rendering, recognition, and corruption diagnostics. Gameplay handlers, recipes, cooldowns, and similar behavior will compose through their owning framework modules around those definitions. This keeps item identity reusable across menus, hotbars, storage, and events without creating a second event framework inside the item registry.
