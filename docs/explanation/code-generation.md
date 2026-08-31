# The KSP boundary

KSP removes reflection and handwritten registration without becoming the framework runtime.

The DI processor reads injected constructors, lifetimes, qualifiers, contributions, and root components. It generates small direct factories plus service indexes. The command processor reads command structure and KDoc. It generates immutable definitions, direct handler dispatch, typed route builders, and command contribution indexes.

Generated code does not parse command lines, execute policies, manage coroutines, render help, access Paper, or implement dependency lifetimes. Those behaviors remain ordinary Kotlin code with ordinary unit tests.

KotlinPoet is used for syntax-safe generation. Generated names are deterministic, public only where plug-in code needs them, and treated as build output. Do not edit generated sources or commit `build/generated`.

When adding a feature, put only compile-time facts and type validation in KSP. Put behavior in a handwritten interface or runtime class. A useful test is whether the generated output would need branching, state, clocks, schedulers, or error recovery. If it would, that behavior belongs in the runtime.

This boundary keeps compiler failures precise while preventing string-built generated business logic from becoming a second, harder-to-debug implementation of the framework.
