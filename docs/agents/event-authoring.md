# Event authoring workflow for agents

Use this sequence when adding event-driven behavior.

## 1. Choose the event family

- Use `@On` for synchronous Bukkit/Paper inspection, cancellation, or mutation.
- Use `@Observe` for asynchronous follow-up to a server callback.
- Use `await` for one non-cancelling signal, `capture` for one cancellable input, and `stream` for a bounded sequence.
- Use `ApplicationEvent` and `@OnApplication` for immutable notifications inside one plug-in.
- Use `@ConfigureLifecycleEvents` only for Paper's typed plug-in lifecycle keys.

Do not turn application events into wrappers around Bukkit events. Do not return a raw server event from a temporal selector.

## 2. Project before suspension

An `@Observe` body and every temporal selector begin in the native callback. Copy UUIDs, framework references, snapshots, and immutable text before suspension. After suspension, use explicit global, region, or entity ownership blocks for server access.

## 3. Give every registration an owner

Prefer `@On` for fixed behavior. Put `ServerEvents.listen` inside `ComponentContext.start`; the context owns cleanup. Await, capture, stream collection, and application streams must run in lifecycle-owned coroutine work.

## 4. Make pressure and cancellation explicit

Choose a timeout for interactive waits. Choose a positive capacity and overflow policy for every stream. Use `skip()` for unrelated events and `retry {}` only when the selected cancellable event should be consumed and feedback should run outside the callback.

## 5. Keep KSP declarations small

Put event behavior in event-set methods and injected collaborators. Do not call or implement `events.codegen` linkage. Use an abstract event-set base only when several concrete implementations intentionally share handler policy; use `@DisableEventHandler`, `@DisableEvents`, or plug-in-level exclusion for explicit removal.

## 6. Verify the matching boundary

Use `EventTestHarness` for generated dispatch, priority, cancellation filtering, observers, temporal queries, application publication, and failures. Use real Paper for native registration, custom events, and lifecycle keys. Run Folia when callback ownership or concurrency matters. Test interactive chat capture with a connected player.

The feature is complete when accepted, skipped, failed, cancelled, and shutdown paths have evidence appropriate to the primitive.
