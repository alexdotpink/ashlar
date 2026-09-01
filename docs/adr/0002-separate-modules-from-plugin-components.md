---
status: partially superseded by ADR-0008 and ADR-0023
---

# Separate artifact modules from plug-in components

Each framework capability will ship as its own module, but only stateful parts with startup or shutdown behavior will extend `PluginComponent`. A component is the object plug-in code uses, not a factory for a second capability object. `PluginComponent` is an abstract class with lifecycle-aware child delegates; `AshlarPlugin` provides the same declaration mechanism at the root. This creates a static, named component tree without reflection. The kernel starts children in declaration order before their parent, then stops the parent before its children in reverse order. Each component owns a supervised coroutine scope beneath its parent scope, so rollback cancels and drains the affected subtree. A start failure triggers best-effort rollback and fails plug-in enable. Components own their tasks and cleanup, and receive long-lived dependencies through constructors, including earlier siblings. `start` and `stop` are synchronous, and a component must be usable when `start` returns. Stateless APIs can register behavior directly. This keeps the kernel from growing wrapper factories, classpath discovery, generated entrypoints, or dependency injection merely to make unrelated capabilities look uniform.
