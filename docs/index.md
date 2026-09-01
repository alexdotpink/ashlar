# Ashlar documentation

Ashlar is a Kotlin foundation for Paper 26.2 and Folia plug-ins. Each plug-in embeds its own selected framework modules.

## Learn the framework

- [Build your first plug-in](tutorials/first-plugin.md)
- [Benchmark a plug-in feature](tutorials/benchmark-a-plugin.md)
- [Understand the architecture](explanation/architecture.md)
- [Understand coroutine and Folia ownership](explanation/coroutines-and-ownership.md)
- [Understand the KSP boundary](explanation/code-generation.md)

## Complete a task

- [Add a lifecycle component](how-to/add-a-component.md)
- [Access Paper safely](how-to/access-paper-safely.md)
- [Inject dependencies](how-to/inject-dependencies.md)
- [Build a command set](how-to/build-a-command-set.md)
- [Add custom command behavior](how-to/customize-commands.md)
- [Compose a large command root](how-to/compose-command-roots.md)
- [Handle server events](how-to/handle-server-events.md)
- [Wait for and collect server events](how-to/wait-for-events.md)
- [Publish application events](how-to/publish-application-events.md)
- [Collect typed player input](how-to/collect-player-input.md)
- [Test a plug-in](how-to/test-a-plugin.md)
- [Diagnose a performance regression](how-to/diagnose-a-performance-regression.md)

## Look up an API

- [Module catalogue](reference/modules.md)
- [Managed Gradle plug-in](reference/gradle-plugin.md)
- [Kernel](reference/kernel.md)
- [Dependency injection](reference/dependency-injection.md)
- [Commands](reference/commands.md)
- [Command arguments and options](reference/command-arguments.md)
- [Native Minecraft arguments](reference/native-arguments.md)
- [Command policies](reference/command-policies.md)
- [Command results and failures](reference/command-results.md)
- [Typed routes, fragments, and graphs](reference/command-routes.md)
- [Events](reference/events.md)
- [Input](reference/input.md)
- [Testing APIs](reference/testing.md)
- [Benchmarks](reference/benchmarks.md)
- [Public API index](reference/api-index.md)

## Agent entrypoints

- [Agent documentation index](agents/index.md)
- [Plug-in authoring workflow](agents/plugin-authoring.md)
- [Command authoring workflow](agents/command-authoring.md)
- [Event authoring workflow](agents/event-authoring.md)
- [Input authoring workflow](agents/input-authoring.md)
- [Item and menu implementation workflow](agents/menu-implementation.md)
- [Verification matrix](agents/verification.md)
- [Benchmark workflow](agents/benchmarking.md)

Architecture decisions are catalogued in the [ADR index](adr/README.md). They record why an API has its shape. They are not usage instructions.

## Module designs

- [Benchmarking system](design/benchmarking.md) - implemented; contracts remain exploratory pending canonical baselines
- [Configuration module](design/config-module.md) - planned
- [Events module](design/events-module.md)
- [Input module](design/input-module.md)
- [Items module](design/items-module.md)
- [Menus module](design/menus-module.md)
