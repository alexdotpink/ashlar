# Why the framework is split this way

The kernel solves one problem: safely owning the lifetime and execution of a Minecraft plug-in. Optional modules solve player-facing capabilities such as commands. A plug-in embeds its selected modules, so two plug-ins cannot conflict over a shared framework version or global service.

The main boundary is between declaration and runtime behavior. Kotlin classes and annotations declare components, dependencies, and command grammar. KSP compiles only the static facts that Kotlin already knows: constructor calls, route metadata, direct handler calls, and typed route methods. Handwritten runtime code owns lifecycle, parsing, concurrency, policies, and Paper integration.

This keeps modules deep. A command handler sees resolved Kotlin domain values and returns a domain or presentation result. It does not know Brigadier nodes, token scanners, coroutine bookkeeping, or server registration. A component owns children, tasks, and resources without coordinating a shutdown state machine.

Paper and Folia share the same public execution model. Framework coroutines do not imply permission to touch server state. Small ownership blocks make the transition explicit and keep Paper objects from leaking across suspension points.

Dependency injection is plug-in-local and generated at constructor boundaries. It exists to connect modules and application code, not to become a general reflection container. Contributions provide controlled extension sets; automatic root components let modules install lifecycle state without making the plug-in entrypoint a manual registry.

The result is intentionally opinionated: typed interfaces over raw strings, one owner for every task and resource, startup failure for ambiguous static configuration, and explicit outcomes for expected server races.
