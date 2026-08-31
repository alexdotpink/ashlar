# Render menus declaratively

The menu module will rerun a declarative render function when local menu state changes and reconcile the resulting inventory description against the open view. Plug-in code will describe slots and interactions rather than mutate inventory objects or manually refresh handlers. Reducer-style state remains an optional Kotlin organization pattern instead of a required framework interface.
