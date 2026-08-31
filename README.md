# Framework

Framework is a Kotlin-only foundation for Paper and Folia plug-ins. The kernel owns plug-in lifecycle, structured coroutine scopes, nested stateful components, deterministic cleanup, and explicit server-ownership transitions. Optional command, event, and input modules provide typed player-facing interfaces without expanding the kernel.

The project currently uses a temporary name and Maven group. Do not publish plug-ins against `dev.placeholder.framework` as a permanent coordinate.

## Start here

- [Documentation home](docs/index.md)
- [Build a minimal plug-in](docs/tutorials/first-plugin.md)
- [Agent entrypoint](AGENTS.md)
- [Run the sample plug-in](samples/sample-plugin/README.md)
- [Events reference](docs/reference/events.md)
- [Input reference](docs/reference/input.md)
- [Project language](CONTEXT.md)
- [Architecture decisions](docs/adr/)

## Build the repository

```bash
./gradlew build
```

Gradle runs on Java 21 and provisions Java 25 for framework and plug-in compilation. Real-server verification is separate:

```bash
./gradlew integrationTest
```

That task builds a self-contained fixture plug-in and boots the pinned Paper and Folia versions.

## Status

The foundation is pre-release software. Stable APIs follow SemVer once published, while declarations from `framework-incubator` require explicit opt-in and are not covered by the stable compatibility promise.

Licensed under Apache-2.0.
