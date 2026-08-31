# Module catalogue

Framework plug-ins embed only the modules they use. There is no shared server-wide framework plug-in.

| Gradle project | Artifact | Purpose | Intended audience |
| --- | --- | --- | --- |
| `kernel` | `framework-kernel` | Plug-in lifecycle, component trees, coroutine ownership, cleanup, and Paper/Folia execution contexts | Every plug-in |
| `framework-di` | `framework-di` | Typed dependency graph and DI annotations | Every managed plug-in |
| `framework-commands` | `framework-commands` | Typed command runtime, native arguments, policies, help, routes, and test harness | Plug-ins with commands |
| `framework-events` | `framework-events` | Server handlers, coroutine observers, temporal queries, application events, lifecycle keys, and test harness | Plug-ins with events |
| `framework-input` | `framework-input` | Typed chat prompts, retries, conflicts, cancellation, deadlines, and test harness | Plug-ins collecting player input |
| `framework-items` | `framework-items` | Immutable item specs, lossless snapshots, typed custom items, codecs, migrations, and integrity | Plug-ins creating or persisting items |
| `framework-menus` | `framework-menus` | Declarative sessions, typed native hosts, actions, navigation, storage, recovery, and inspection | Plug-ins with inventory interfaces |
| `framework-menus-test` | `framework-menus-test` | Deterministic menu runtime, virtual time, host inspection, and transaction testing | Tests only |
| `framework-benchmarks` | `framework-benchmarks` | Scenario DSL, runners, comparison, catalogue, diagnostics, and result schema | Benchmark source sets only |
| `framework-testkit` | `framework-testkit` | Server-free component lifecycle tests | Tests only |
| `framework-incubator` | `framework-incubator` | Explicitly unstable experiments | Opt-in only |
| `framework-di-ksp` | `framework-di-ksp` | Direct constructor factories and contribution indexes | KSP processor, selected by the Gradle plug-in |
| `framework-commands-ksp` | `framework-commands-ksp` | Command metadata, direct bindings, and typed route generation | KSP processor, selected by `commands()` |
| `framework-events-ksp` | `framework-events-ksp` | Event metadata, direct bindings, and contribution linkage | KSP processor, selected by `events()` |
| `framework-gradle-plugin` | Gradle plug-in `dev.placeholder.framework` | Version alignment, compiler setup, shading, KSP, and descriptor generation | Build configuration |
| `framework-bom` | `framework-bom` | Aligns published framework module versions | Managed automatically |

`sample-plugin` is a playable command, event, input, item, and menu catalogue with a benchmark example. `integration-test-fixture` is an automated Paper/Folia workload. Neither is a published runtime artifact.

All coordinates and the plug-in ID are temporary while the Maven group is `dev.placeholder.framework`. Published stable modules follow SemVer; incubator declarations do not carry the stable compatibility promise.
