# Coroutines, Folia, and server ownership

A coroutine context answers where suspended work resumes. Paper and Folia ownership answers which server state may be accessed at that instant. They are related operationally but are not the same guarantee.

Ashlar tasks run in supervised, plug-in-owned coroutine scopes. This gives cancellation, naming, failure reporting, and bounded shutdown. It does not grant ownership of the global region, a chunk region, or an entity.

`withGlobal`, `withRegion`, and `withEntity` bridge that gap. Each schedules one non-suspending callback on the platform's correct scheduler and suspends the caller until the callback finishes. If the caller already owns the target, the block runs inline. Afterward, the coroutine resumes in its previous context.

The block is deliberately non-suspending. A suspension could outlive the scheduler callback and make a captured ownership proof false. Context parameters such as `EntityContext` make ownership a Kotlin API requirement, while `checkOwnership()` revalidates the runtime fact immediately before Paper access.

Entity retirement is normal concurrency, not an exception. An entity can disappear between resolution and scheduled work, so `withEntity` returns `Completed` or `Retired`. Stable command references carry UUIDs or keys instead of live server objects and expose the same explicit access operations.

This model works on Paper and Folia without pretending they have identical schedulers. Application code states the ownership domain it needs; the kernel selects the platform mechanism.
