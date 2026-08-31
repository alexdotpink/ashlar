# Plug-in authoring workflow for agents

Follow this sequence when creating or changing a framework plug-in.

## 1. Establish the target

Confirm the plug-in package, main class, required modules, Paper-only or Folia support, and externally visible commands. Read the existing build and plug-in class before adding files.

The step is complete when each requested capability maps to one framework module or a direct Paper API.

## 2. Configure the build

Use the managed Gradle plug-in when consuming published artifacts:

```kotlin
plugins {
    id("dev.placeholder.framework") version "0.1.0-SNAPSHOT"
}

group = "dev.example"
version = "1.0.0"

frameworkPlugin {
    pluginName.set("ExamplePlugin")
    mainClass.set("dev.example.ExamplePlugin")
    authors.add("Example")
    foliaSupported()
    commands(strictDocumentation = true)
    events()
}
```

The repository sample uses project dependencies because it tests unpublished source. Copy its structure, not its dependency declarations, into an external plug-in.

The step is complete when `generateFrameworkPluginYaml` produces one descriptor and no hand-written `src/main/resources/plugin.yml` exists.

## 3. Choose lifecycle ownership

Use `FrameworkPlugin` for the entrypoint. Use a `PluginComponent` for state with start, stop, tasks, children, or closeable resources. Use an injected plain class for stateless behavior.

Use `@FrameworkComponent` with `@Inject` when the graph should install a root component automatically. Use a delegated `component { ... }` when a parent component owns a specific child explicitly.

The step is complete when every long-lived task and resource has one lifecycle owner.

## 4. Keep server access explicit

Command handlers and component tasks run away from server ownership by default. Enter `withGlobal`, `withRegion`, or `withEntity` immediately around Paper access. Copy immutable values out of the ownership block before suspending again.

The step is complete when a search for direct Bukkit entity, block, world, and scheduler access shows either a framework ownership block or a documented Paper-only reason.

## 5. Add commands through types

Use `@Commands`, nested `@Group` or `@Scope` classes, Kotlin parameter types, and generated routes. Add a codec for a domain type instead of parsing strings in handlers. Use a policy for reusable invocation behavior instead of copying checks between handlers.

Read [command-authoring.md](command-authoring.md) for the full command workflow.

The step is complete when handlers receive valid domain values and contain domain behavior rather than token parsing.

## 6. Add events through the matching dispatch model

Use synchronous `@On` handlers for live server mutation, `@Observe` for coroutine follow-up, temporal queries for one-off or bounded input, and application events for plug-in-local notifications.

Read [event-authoring.md](event-authoring.md) for the full event workflow.

The step is complete when event ownership, cancellation, pressure, and cleanup are explicit.

## 7. Verify the actual path

Run the smallest focused test during development. Finish with `build` and `checkKotlinAbi` for framework changes. Run a Paper or Folia server when the change touches registration, native arguments, ownership, scheduling, or response delivery.

The step is complete when the evidence listed in [verification.md](verification.md) supports every reported result.
