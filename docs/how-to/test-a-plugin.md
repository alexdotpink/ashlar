# Test a framework plug-in

## Test a component without Paper

Use `ComponentTestHarness` to install a component tree, inspect lifecycle events, advance coroutine work, and close the tree. Supply fake dependencies through the test graph rather than mocking the kernel.

## Test commands without a server

Use `CommandTestHarness` with the generated command binding. It executes the same immutable route plan used in production. Test defaults, quoted and greedy input, named options, custom codecs, direct handler invocation, rejections, and returned responses.

The focused harness does not run the production policy, observer, help, delivery, or executor-retirement pipeline. Test those units directly and cover their integration on a real server. Paper-native arguments are intentionally absent from this test API because they require a real command tree and server-owned objects.

## Run repository checks

```bash
./gradlew build checkKotlinAbi
```

Run real servers for platform behavior:

```bash
./gradlew :integration-test-fixture:paperIntegrationTest
./gradlew :integration-test-fixture:foliaIntegrationTest
```

Run the playable sample while developing commands:

```bash
./gradlew :sample-plugin:runSamplePaper
./gradlew :sample-plugin:runSampleFolia
```

Use the command checklist in [the sample README](../../samples/sample-plugin/README.md) for manual client verification.
