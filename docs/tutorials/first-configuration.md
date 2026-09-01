# Create your first configuration

This tutorial adds one YAML settings document to an Ashlar plug-in. Starting the plug-in creates the file before application components are constructed. The component receives a ready `ConfigHandle<WelcomeSettings>`.

## Enable the module

Add `config()` to the managed Ashlar block:

```kotlin
ashlar {
    pluginName.set("WelcomePlugin")
    mainClass.set("dev.example.welcome.WelcomePlugin")
    authors.add("Example")
    foliaSupported()
    config()
}
```

`config()` adds the configuration runtime, Kotlin Serialization, and the small KSP processor that links declarations to the runtime.

## Declare the settings

Create `src/main/kotlin/dev/example/welcome/WelcomeSettings.kt`:

```kotlin
package dev.example.welcome

import kotlinx.serialization.Serializable
import pink.alex.ashlar.config.Config

/** Messages sent when a player joins. */
@Config(path = "welcome.yml")
@Serializable
data class WelcomeSettings(
    /** Whether join messages are enabled. */
    val enabled: Boolean = true,

    /** Text sent to a joining player. */
    val message: String = "Welcome to the server!",
)
```

The root is a final serializable data class. Every constructor property has a default, so Ashlar can create a complete document when none exists.

## Inject the handle

Create `src/main/kotlin/dev/example/welcome/WelcomeComponent.kt`:

```kotlin
package dev.example.welcome

import pink.alex.ashlar.AshlarComponent
import pink.alex.ashlar.ComponentContext
import pink.alex.ashlar.PluginComponent
import pink.alex.ashlar.config.ConfigHandle
import pink.alex.ashlar.di.Inject

@AshlarComponent
@Inject
class WelcomeComponent(
    private val settings: ConfigHandle<WelcomeSettings>,
) : PluginComponent() {
    override fun ComponentContext.start() {
        logger.info("Join message: ${settings.current.message}")
    }
}
```

The handle already contains an accepted value when Ashlar constructs `WelcomeComponent`. No load call or registry lookup belongs in the component.

## Start the server

Build the shaded JAR and start Paper or Folia with it:

```bash
./gradlew shadowJar
```

On first enable, the server log contains:

```text
Join message: Welcome to the server!
```

Ashlar creates `plugins/WelcomePlugin/welcome.yml` with the complete defaults and KDoc comments:

```yaml
_ashlar-schema: 1
# Whether join messages are enabled.
enabled: true
# Text sent to a joining player.
message: "Welcome to the server!"
```

Edit `message`, restart the plug-in, and check the log again. The new text is available through `settings.current`. Unknown keys, invalid YAML, and values that cannot decode stop enable before `WelcomeComponent` starts.

Continue with [validate configuration values](../how-to/validate-configuration.md) when fields depend on each other. Use [watched reload](../how-to/watch-configuration.md) when changes must take effect without a restart.
