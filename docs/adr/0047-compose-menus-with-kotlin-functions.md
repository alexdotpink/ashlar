# Compose menus with Kotlin functions

Reusable menu pieces will be ordinary Kotlin functions with a `MenuScope` context parameter; extension helpers remain possible when they read better. Stateless functions may emit declarations directly, while `component(key)` supplies stable identity only where local state or repeated children need it. The module will not require annotated composables, generated wrappers, compiler plug-ins, or framework component classes.
