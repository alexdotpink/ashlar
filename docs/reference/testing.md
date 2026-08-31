# Testing API reference

## Component test kit

`componentTest(name, drainTimeout) { component }` creates a server-free `ComponentTestHarness`. Call `start()` to construct and start the tree. Call `stop()` or `close()` to cancel tasks, drain them, stop the tree, and close resources.

`ComponentTestResult` reports:

- whether tasks drained within the configured timeout
- ordinary task failures
- critical task failures
- lifecycle failures

`checkSuccessful()` throws when any category is unsuccessful. The harness supplies a minimal test plug-in context; do not use it to claim Bukkit or scheduler behavior.

## Command test harness

`CommandTestHarness(contribution, target, dependencyGraph)` executes a generated command plan without Paper. `execute(command, invocation)` tokenizes the complete command, selects exactly one route, resolves built-in or contributed codecs, scans options, invokes the generated direct binding, and converts its return value to `CommandResult`.

This focused harness does not run the production policy, observer, help, Paper registration, response-delivery, or executor-retirement pipeline. Test those runtime units directly where possible and use a real server for their integrated behavior. Paper-native argument types are not available server-free.

## Repository fixtures

```bash
./gradlew build checkKotlinAbi
./gradlew :integration-test-fixture:paperIntegrationTest
./gradlew :integration-test-fixture:foliaIntegrationTest
./gradlew :sample-plugin:runSamplePaper
./gradlew :sample-plugin:runSampleFolia
```

The integration fixture is automated and asserts server startup and plug-in behavior. The sample is interactive and has a client checklist in its [README](../../samples/sample-plugin/README.md).

Use a real Paper and Folia server for command registration, native Minecraft inputs, server ownership, scheduling, lifecycle disable, and command-tree refresh. Use a connected player for response delivery, suggestions, click events, and executor retirement.
