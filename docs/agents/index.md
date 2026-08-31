# Documentation for coding agents

Use this section when an agent is writing a plug-in, reviewing generated framework code, or deciding how to verify a change.

## Route by task

| Task | Read first | Completion condition |
| --- | --- | --- |
| Create or extend a plug-in | [Plug-in authoring](plugin-authoring.md) | The shaded JAR builds and the selected Paper or Folia path runs. |
| Add commands | [Command authoring](command-authoring.md) | Every route has KDoc, typed inputs, and focused tests. |
| Add event-driven behavior | [Event authoring](event-authoring.md) | Dispatch semantics, ownership, pressure, cleanup, and the relevant server path are verified. |
| Collect player input | [Input authoring](input-authoring.md) | Parsing, consumption, conflicts, cancellation, and cleanup have focused evidence. |
| Implement items or menus | [Item and menu implementation](menu-implementation.md) | One approved vertical slice has model, native evidence where required, docs, samples, and ABI coverage. |
| Change framework runtime code | [Architecture](../explanation/architecture.md) | Unit tests, ABI checks, and affected real-server fixtures pass. |
| Touch Paper objects from a coroutine | [Access Paper safely](../how-to/access-paper-safely.md) | Every access occurs inside the correct ownership block. |
| Add DI bindings or contributions | [Dependency injection reference](../reference/dependency-injection.md) | Generated factories compile and lifetime boundaries remain valid. |
| Verify work | [Verification](verification.md) | Evidence matches the claim being made. |
| Measure or optimize framework work | [Benchmarking](benchmarking.md) | The owning contract and correct evidence layer pass. |

## Sources of truth

The Kotlin source and committed `.api` dumps define the current public API. Reference pages describe that API. The sample plug-in provides compile-checked command, event, input, item, menu, and benchmark examples. The integration fixture proves behavior on real Paper and Folia servers.

If prose and source disagree, follow source, fix the prose in the same change, and add a regression test when behavior was ambiguous.
