# Managed Gradle plug-in reference

Apply `pink.alex.ashlar` to a Kotlin JVM plug-in project:

```kotlin
plugins {
    id("pink.alex.ashlar") version "0.1.0-SNAPSHOT"
}

ashlar {
    pluginName.set("Homes")
    mainClass.set("dev.example.homes.HomesPlugin")
    description.set("Player homes")
    authors.add("Example")
    website.set("https://example.invalid")
    foliaSupported()
    commands(strictDocumentation = true)
    events()
    input()
}
```

## Managed behavior

The plug-in applies Kotlin/JVM and Shadow, selects the Java 25 toolchain and JVM target, enables Kotlin progressive mode and Java parameter metadata, adds Paper's repository, and adds the framework BOM, kernel, DI runtime, Paper API, and required KSP processor. `commands()` adds the command runtime and processor. `events()` adds the event runtime and processor. `input()` adds typed input and enables events transitively; input itself generates no code. `items()` and `menus()` add their runtimes without code generation.

The ordinary `jar` receives the `plain` classifier. The unclassified `shadowJar` is the distributable plug-in JAR and merges service files so generated contribution indexes remain discoverable. Paper is compile-only.

## Extension

| Member | Meaning |
| --- | --- |
| `pluginName` | Required Paper plug-in name |
| `mainClass` | Required fully qualified `AshlarPlugin` subclass |
| `description` | Optional descriptor description |
| `authors` | Descriptor author list |
| `website` | Optional descriptor website |
| `foliaSupported()` | Writes `folia-supported: true`; call only after testing Folia |
| `commands(strictDocumentation)` | Enables commands; strict mode turns missing route summaries into KSP errors |
| `events()` | Enables server, application, and lifecycle events plus generated direct bindings |
| `input()` | Enables typed player input and its event dependency |
| `items()` | Enables immutable item specifications, snapshots, and custom items |
| `menus()` | Enables declarative menus and items transitively |
| `allowVersionOverrides(reason)` | Enables deliberate version overrides and records why |
| `ashlarVersion(version)` | Overrides the aligned framework version after overrides are enabled |
| `paperApiVersion(version)` | Overrides the Paper API version after overrides are enabled |

Versions are intentionally locked by default. Call `allowVersionOverrides` with a non-blank reason before either override.

## Descriptor generation

`generateAshlarPluginYaml` generates `plugin.yml` from the extension and project metadata. It writes name, project version, main class, API version, Folia support, description, website, and authors when present.

Do not add `src/main/resources/plugin.yml`. The task fails when a handwritten descriptor exists, preventing two conflicting sources of truth.

## Useful tasks

```bash
./gradlew generateAshlarPluginYaml
./gradlew shadowJar
./gradlew benchmark
./gradlew benchmarkJmh
./gradlew benchmarkDiagnose
```

The generated descriptor is included in the shaded JAR. Use the unclassified JAR from `build/libs` on the server.

The plug-in also creates an isolated `benchmark` source set and adds the test-only benchmark artifact. Benchmark tasks and properties are listed in the [benchmark reference](benchmarks.md). Benchmark dependencies never enter `shadowJar`.
