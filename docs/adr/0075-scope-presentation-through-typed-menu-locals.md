# Scope presentation through typed menu locals

Kotlin context parameters will express the compile-time menu rendering capability, while typed menu locals will carry dynamic values through nested render subtrees. Locals are intended for theme, messages, locale-derived presentation, and similar cross-cutting concerns and are captured in the immutable render tree rather than thread-local state. Services and repositories remain explicit constructor or function dependencies so scoped presentation does not become ambient dependency injection.
