# Build your first framework plug-in

This tutorial builds a small Paper and Folia plug-in with one lifecycle component and one typed command. It uses a local framework publication because the repository still has temporary Maven coordinates.

## Publish the framework locally

From the framework repository:

```bash
./gradlew publishToMavenLocal
```

The command publishes the aligned `0.1.0-SNAPSHOT` artifacts and Gradle plug-in into your local Maven repository.

## Create the project

Create an empty directory with this `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "greeting-plugin"
```

Add `build.gradle.kts`:

```kotlin
plugins {
    id("dev.placeholder.framework") version "0.1.0-SNAPSHOT"
}

group = "dev.example"
version = "1.0.0"
description = "A first framework plug-in"

frameworkPlugin {
    pluginName.set("GreetingPlugin")
    mainClass.set("dev.example.greeting.GreetingPlugin")
    authors.add("Example")
    foliaSupported()
    commands(strictDocumentation = true)
}
```

The managed plug-in selects Java 25, Kotlin, Paper, framework dependencies, KSP processors, Shadow packaging, and `plugin.yml` generation.

## Add the plug-in entrypoint

Create `src/main/kotlin/dev/example/greeting/GreetingPlugin.kt`:

```kotlin
package dev.example.greeting

import dev.placeholder.framework.ComponentContext
import dev.placeholder.framework.FrameworkPlugin
import dev.placeholder.framework.PluginComponent

class GreetingPlugin : FrameworkPlugin() {
    private val greeter by component { GreetingComponent() }

    override fun ComponentContext.enable() {
        logger.info("GreetingPlugin is ready")
    }
}

class GreetingComponent : PluginComponent() {
    override fun ComponentContext.start() {
        logger.info("Started $componentName")
        own(AutoCloseable { logger.info("Closed greeting resources") })
    }

    override fun ComponentContext.stop() {
        logger.info("Stopped $componentName")
    }
}
```

The kernel starts `GreetingComponent` before `enable()`. It calls `stop()` and closes owned resources when Paper disables the plug-in.

## Add a typed command

Create `src/main/kotlin/dev/example/greeting/GreetingCommands.kt`:

```kotlin
package dev.example.greeting

import dev.placeholder.framework.commands.Commands
import dev.placeholder.framework.commands.GreedyText
import kotlinx.coroutines.delay
import net.kyori.adventure.text.Component
import kotlin.time.Duration.Companion.milliseconds

@Commands(name = "greeting", aliases = ["hello"])
class GreetingCommands {
    /** Greets one name, or the command sender's world when no name is supplied. */
    fun greet(name: String = "there"): String = "Hello, $name!"

    /** Repeats a complete message after a short coroutine suspension. */
    suspend fun announce(message: GreedyText): Component {
        delay(100.milliseconds)
        return Component.text(message)
    }
}
```

KSP generates direct bindings and typed routes. The runtime registers `/greeting greet [name]` and `/greeting announce <message...>`.

## Build the server JAR

```bash
./gradlew shadowJar
```

The unclassified JAR under `build/libs` contains the plug-in, Kotlin, coroutines, kernel, command runtime, and generated contribution indexes. Paper remains a compile-only dependency.

Start a Paper or Folia 26.2 server with the JAR and run:

```text
/greeting greet Alex
/hello announce framework commands are live
```

You should receive `Hello, Alex!` and then the announcement. The tutorial is complete when both routes work and server shutdown logs the component stop and resource close messages.

Continue with [Access Paper safely](../how-to/access-paper-safely.md) before touching worlds, blocks, entities, or players from a coroutine.
