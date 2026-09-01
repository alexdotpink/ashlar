# Module catalogue

Ashlar plug-ins embed only the modules they use. There is no shared server-wide framework plug-in.

| Gradle project | Artifact | Purpose | Intended audience |
| --- | --- | --- | --- |
| `kernel` | `ashlar-kernel` | Plug-in lifecycle, component trees, coroutine ownership, cleanup, and Paper/Folia execution contexts | Every plug-in |
| `ashlar-di` | `ashlar-di` | Typed dependency graph and DI annotations | Every managed plug-in |
| `ashlar-commands` | `ashlar-commands` | Typed command runtime, native arguments, policies, help, routes, and test harness | Plug-ins with commands |
| `ashlar-events` | `ashlar-events` | Server handlers, coroutine observers, temporal queries, application events, lifecycle keys, and test harness | Plug-ins with events |
| `ashlar-input` | `ashlar-input` | Typed chat prompts, retries, conflicts, cancellation, deadlines, and test harness | Plug-ins collecting player input |
| `ashlar-items` | `ashlar-items` | Immutable item specs, lossless snapshots, typed custom items, codecs, migrations, and integrity | Plug-ins creating or persisting items |
| `ashlar-menus` | `ashlar-menus` | Declarative sessions, typed native hosts, actions, navigation, storage, recovery, and inspection | Plug-ins with inventory interfaces |
| `ashlar-menus-test` | `ashlar-menus-test` | Deterministic menu runtime, virtual time, host inspection, and transaction testing | Tests only |
| `ashlar-benchmarks` | `ashlar-benchmarks` | Scenario DSL, runners, comparison, catalogue, diagnostics, and result schema | Benchmark source sets only |
| `ashlar-testkit` | `ashlar-testkit` | Server-free component lifecycle tests | Tests only |
| `ashlar-incubator` | `ashlar-incubator` | Explicitly unstable experiments | Opt-in only |
| `ashlar-di-ksp` | `ashlar-di-ksp` | Direct constructor factories and contribution indexes | KSP processor, selected by the Gradle plug-in |
| `ashlar-commands-ksp` | `ashlar-commands-ksp` | Command metadata, direct bindings, and typed route generation | KSP processor, selected by `commands()` |
| `ashlar-events-ksp` | `ashlar-events-ksp` | Event metadata, direct bindings, and contribution linkage | KSP processor, selected by `events()` |
| `ashlar-gradle-plugin` | Gradle plug-in `pink.alex.ashlar` | Version alignment, compiler setup, shading, KSP, and descriptor generation | Build configuration |
| `ashlar-bom` | `ashlar-bom` | Aligns published framework module versions | Managed automatically |

`sample-plugin` is a playable command, event, input, item, and menu catalogue with a benchmark example. `integration-test-fixture` is an automated Paper/Folia workload. Neither is a published runtime artifact.

All coordinates and the plug-in ID are temporary while the Maven group is `pink.alex.ashlar`. Published stable modules follow SemVer; incubator declarations do not carry the stable compatibility promise.
