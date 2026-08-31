# Framework documentation

Framework is a Kotlin foundation for Paper 26.2 and Folia plug-ins. Each plug-in embeds its own selected framework modules.

## Learn the framework

- [Build your first plug-in](tutorials/first-plugin.md)
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
- [Test a plug-in](how-to/test-a-plugin.md)

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
- [Testing APIs](reference/testing.md)
- [Public API index](reference/api-index.md)

## Agent entrypoints

- [Agent documentation index](agents/index.md)
- [Plug-in authoring workflow](agents/plugin-authoring.md)
- [Command authoring workflow](agents/command-authoring.md)
- [Event authoring workflow](agents/event-authoring.md)
- [Verification matrix](agents/verification.md)

Architecture decisions live under [docs/adr](adr/). They record why the current API has its shape. They are not usage instructions.

## Module designs

- [Events module](design/events-module.md)
