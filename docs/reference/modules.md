# Module catalogue

Framework plug-ins embed only the modules they use. There is no shared server-wide framework plug-in.

| Gradle project | Artifact | Purpose | Intended audience |
| --- | --- | --- | --- |
| `kernel` | `framework-kernel` | Plug-in lifecycle, component trees, coroutine ownership, cleanup, and Paper/Folia execution contexts | Every plug-in |
| `framework-di` | `framework-di` | Typed dependency graph and DI annotations | Every managed plug-in |
| `framework-commands` | `framework-commands` | Typed command runtime, native arguments, policies, help, routes, and test harness | Plug-ins with commands |
| `framework-testkit` | `framework-testkit` | Server-free component lifecycle tests | Tests only |
| `framework-incubator` | `framework-incubator` | Explicitly unstable experiments | Opt-in only |
| `framework-di-ksp` | `framework-di-ksp` | Direct constructor factories and contribution indexes | KSP processor, selected by the Gradle plug-in |
| `framework-commands-ksp` | `framework-commands-ksp` | Command metadata, direct bindings, and typed route generation | KSP processor, selected by `commands()` |
| `framework-gradle-plugin` | Gradle plug-in `dev.placeholder.framework` | Version alignment, compiler setup, shading, KSP, and descriptor generation | Build configuration |
| `framework-bom` | `framework-bom` | Aligns published framework module versions | Managed automatically |

`sample-plugin` is a playable feature catalogue. `integration-test-fixture` is an automated Paper/Folia fixture. Neither is a published framework artifact.

All coordinates and the plug-in ID are temporary while the Maven group is `dev.placeholder.framework`. Published stable modules follow SemVer; incubator declarations do not carry the stable compatibility promise.
