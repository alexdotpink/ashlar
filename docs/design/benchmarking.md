# Benchmarking system

Status: design in progress; implementation has not started

This page is for framework contributors reviewing the performance architecture. It explains the intended contracts and evidence. It is not an API reference or a guide for writing a plug-in benchmark.

## Goal

The framework should make plug-ins easy to author without spending server time, heap, network bandwidth, build time, or client frame time carelessly. Performance must therefore be reviewable in the same way as behavior and ABI compatibility.

The primary mechanism is a regression guard. Every public capability owns a [performance contract](../../CONTEXT.md#performance) with named workload profiles and source-controlled budgets. A checked performance catalogue fails when a required capability has no contract.

The system also serves plug-in authors. They can describe their own cross-feature journeys with the same public scenario API without learning JMH, server orchestration, sampling policy, or result storage.

## What a contract contains

A framework contract names one capability and declares:

- explicit `small`, `typical`, and `stress` profiles with numeric workload sizes;
- cold and warmed paths where initialization can move cost between calls;
- a matched direct Kotlin, Paper, Folia, or toolchain control when framework overhead can be isolated;
- an end-to-end journey when the complete player or plug-in outcome matters;
- core time and allocation measurements;
- relevant measurements such as callback occupancy, scheduling latency, retained heap, throughput, disk I/O, generated size, packet volume, or client frame health;
- semantic verification outside the timed boundary;
- an explicit `EXPLORATORY`, `GUARDED`, or `CONTRACTUAL` status;
- relative pull-request budgets and, for contractual scenarios, absolute release ceilings.

Framework extension points use fixed fast, delayed, and failing fixtures. The catalogue does not claim to measure databases or networks that the repository does not ship.

## Authoring model

Plug-in authors use one generic scenario DSL with typed feature fixtures:

```kotlin
benchmarkScenario("chat search opens menu") {
    status = PerformanceContractStatus.GUARDED

    val alex by player("Alex")

    setup {
        waypoints.seed(10_000)
    }

    measure {
        alex.execute("/waypoint search")
        alex.chat("market")
        alex.menu.click(12)
    }

    verify {
        alex.menu.title isEqualTo "Market"
    }

    budgets {
        p99.regression atMost 5.percent
        allocation.regression atMost 256.bytesPerOperation
    }
}
```

The outer language stays the same when a scenario crosses commands, events, input, items, and menus. Typed fixtures expose feature-specific actions and assertions. The public API does not grow a separate top-level DSL for every framework module.

Framework contributors can declare isolated and platform measurements inside a contract:

```kotlin
performanceContract("commands.dispatch") {
    status = PerformanceContractStatus.CONTRACTUAL

    profiles {
        profile("small", routes = 10, options = 0, concurrency = 1)
        profile("typical", routes = 250, options = 4, concurrency = 16)
        profile("stress", routes = 2_000, options = 8, concurrency = 256)
    }

    micro {
        control { brigadier.execute(nativeCommand) }
        framework { commands.execute(frameworkCommand) }
    }

    platform(PAPER, FOLIA) {
        scenario {
            measure { players.executeConcurrently("/waypoint list") }
            verify { everyInvocationCompleted() }
        }
    }

    budgets {
        pullRequest {
            p99.regression atMost 5.percent
            allocation.regression atMost 256.bytesPerOperation
        }
        release {
            p99 atMost 150.microseconds
            allocation atMost 2.kibibytesPerOperation
        }
    }
}
```

The numeric values above illustrate the language. Contract budgets come from measured baselines and review, not from this design example.

## Evidence layers

No single runner can prove every performance claim.

| Layer | Proves | Does not prove |
| --- | --- | --- |
| JVM microbenchmark | Isolated latency, throughput, allocation, scaling, and matched-control overhead | Paper ownership, tick behavior, packets, or rendering |
| Paper and Folia workload | Callback occupancy, scheduling, native adapter cost, tick health, concurrency, and saturation | Client rendering or packet-to-visible completion |
| Connected-client scenario | Packet volume, visible completion latency, screen behavior, and frame health | Large player-count saturation |
| Build benchmark | KSP, Gradle, compilation, generated output, artifact size, startup, and shutdown | Runtime player behavior |
| Load and soak run | Tail latency, saturation, retained-memory growth, churn, and delayed cleanup | Precise single-operation overhead |

Pure Kotlin work runs once. Ownership and native-adapter contracts run on Paper and Folia. Deterministic server-side actors provide load. A smaller connected-client lane covers claims that require the client protocol.

## Measurement boundaries

Asynchronous framework operations report separate phases when applicable:

- native callback occupancy, from platform entry until control returns;
- admission latency, from entry until the framework accepts or rejects work;
- scheduling latency, from acceptance until the suspending handler begins;
- end-to-end latency, from entry until the contract's declared visible or persisted result.

This split prevents a fast callback with a stalled coroutine from looking healthy, and prevents slow plug-in code from being misreported as framework overhead.

Native callback occupancy and p99 latency take priority when metrics conflict. Allocation and retained memory come next. Peak throughput comes after server safety. Lifecycle-owned caches are acceptable only when they improve tail behavior and remain within per-plug-in or per-player memory budgets.

## Repository shape

The planned layout keeps infrastructure shared and contracts beside the code they protect:

```text
framework-benchmarks/                 public test-only DSL, result model, runners
kernel/src/benchmark/                 kernel contracts
framework-di/src/benchmark/           dependency-injection contracts
framework-commands/src/benchmark/     command contracts
framework-events/src/benchmark/       event contracts
framework-input/src/benchmark/        input contracts
framework-items/src/benchmark/        item contracts
framework-menus/src/benchmark/        menu and storage contracts
integration-test-fixture/             Paper and Folia workloads
samples/sample-plugin/src/benchmark/  plug-in author examples
```

Adding `benchmarkImplementation(framework("benchmarks"))` activates the benchmark source set and standard tasks through the existing Gradle plug-in. Benchmark dependencies never enter the plug-in's shipped JAR.

## Required catalogue

The checked performance catalogue covers these groups.

| Group | Required contracts |
| --- | --- |
| Kernel | Component discovery, start, rollback, task launch, cancellation and drain, resource teardown, execution-context fast paths, Paper and Folia scheduler handoff |
| Dependency injection | Cold graph construction, cached resolution, contribution discovery, plug-in and invocation scopes, factory bindings, generated constructor dispatch, large graphs |
| Commands | Registration, direct and scanned parsing, options, codecs, resolution, policies, suggestions, help, routes, responses, observers, admission, scheduling, completion |
| Events | Generated handlers, observer prefix and continuation, dynamic registration, queries, captures, streams and overflow, application-event fan-out, cancellation |
| Input | Prompt acquisition, conflicts, chat projection, parsing, retries, cancellation, deadlines, disconnects, answer bursts, composed prompts |
| Items | Specification construction, editing, materialization, capture, snapshot envelopes, checksums, fingerprints, canonical data, persistent codecs, migrations, HMAC, payload sizes |
| Menus | Render, state, Flow invalidation and conflation, validation, reconciliation, actions, concurrency, effects, locals, navigation, boundaries, focused input, inspection, feedback, viewers |
| Menu storage | Every gesture family, rules, proposals, locks, local and external storage, durable commits, journal I/O and replay, cursor settlement, mailbox delivery, conflicts, recovery |
| Native hosts | Creation, opening, full and partial writes, properties, remounts, close, input projection, every host, packet volume, visible latency, client frame health |
| Build and release | Every KSP processor, clean and incremental compilation, contribution scaling, generated size, Gradle configuration, dependency wiring, shaded size, startup, shutdown |
| Cross-feature journeys | Command to input to menu, event to coroutine to storage, startup to discovery to registration, multi-player load, churn, saturation, soak |
| Benchmark calibration | Empty boundaries, fixture and actor overhead, result serialization, runner self-checks |

The catalogue maps capabilities rather than individual getters. A trivial accessor does not earn a meaningless timing method, but a new public capability cannot ship without representative profiles.

## Comparison and gating

Pull requests compare the branch with current `main` on the same isolated worker. Authoritative runs use warmed, replicated pairs, a declared noise floor, statistical confidence, and one automatic confirmation run before failing.

The canonical measurement environment records hardware, operating system, JVM, framework toolchain, Paper or Folia build, and fixture versions. Absolute ceilings apply only to that versioned environment. A worker or Java, Kotlin, Paper, or Folia upgrade requires an old/new dual run against the same commit and a reviewed rebaseline.

CI work is tiered:

- pull requests run small and typical profiles;
- nightly jobs run stress and sustained load;
- weekly and release jobs run soak workloads;
- ordinary shared CI may run smoke measurements, but it cannot produce blocking results.

Each run emits a readable CLI comparison, stable JSON, and an annotated CI summary with links to raw evidence. A confirmed regression triggers a focused diagnostic rerun with JFR or equivalent profiling, allocation evidence, and GC evidence. Diagnostic recording never contaminates the timed samples used by the gate.

## Documentation package

Implementation must ship four separate reader paths:

- a tutorial that adds one benchmark to the sample plug-in and produces a visible comparison;
- a how-to guide for diagnosing and fixing a regression;
- a reference for the DSL, profiles, metrics, budgets, tasks, JSON schema, environment fingerprint, and errors;
- an agent workflow that selects focused contracts, reads comparison evidence, runs diagnostics, and updates budgets without hiding regressions.

The sample plug-in must exercise cross-feature scenarios with the same public API available to other plug-ins.

## Related decisions

- [ADR 0086](../adr/0086-gate-feature-performance-contracts-with-layered-evidence.md)
- [ADR 0087](../adr/0087-author-benchmarks-as-framework-scenarios.md)
- [ADR 0088](../adr/0088-enforce-relative-and-absolute-performance-budgets.md)
- [ADR 0089](../adr/0089-own-performance-contracts-beside-framework-modules.md)
- [ADR 0090](../adr/0090-optimize-server-safety-before-peak-throughput.md)
