# Key menu state by property and component

Delegated state properties will keep their values by property name within a stable menu-component key rather than by hook call order. Repeated menu components require domain keys, and duplicate state names or child keys fail immediately. This preserves concise local-state syntax while avoiding React's conditional-hook and declaration-reordering failures.
