# Verification matrix for agents

Match the check to the claim. An exit code from an unrelated task is not evidence for runtime behavior.

| Change | Required checks |
| --- | --- |
| Pure kernel or DI logic | Focused unit tests, `build`, `checkKotlinAbi` when public declarations changed |
| Component lifecycle | Component test kit, rollback or shutdown assertion, full build |
| Ownership or scheduler code | Unit test plus the affected Paper and Folia integration fixtures |
| Command processor | Processor tests, generated-source compilation, command runtime tests |
| Command parsing, options, policies, or routes | Focused command tests and sample compilation |
| Native Minecraft argument or command registration | Real Paper server; Folia too when ownership changes |
| Response delivery or executor retirement | A real player connection and disconnect path |
| Event processor or generated binding | Processor tests, generated-source compilation, and `EventTestHarness` tests |
| Server-event registration, custom event, or lifecycle key | Real Paper server; Folia too when callback ownership or concurrency matters |
| Temporal event query or application event | Focused harness tests for completion, cancellation, failure, and cleanup |
| Interactive capture or player-facing observer | Real server with a connected player |
| Player input parsing, conflicts, or deadlines | `InputTestHarness` with accepted, retry, cancellation, and cleanup paths |
| Native chat consumption or prompt delivery | Real connected client on Paper; Folia too when ownership changes |
| Gradle plug-in | Gradle TestKit test and a shaded sample JAR |
| Documentation | Local-link check, code samples compared with current source, full build if samples changed |
| Performance contract or runtime hot path | Owning module benchmark, catalogue, semantic tests, and a paired run on the canonical worker before enforcing budgets |
| Native performance | Paper and Folia result; connected client too for packet-visible or rendered behavior |
| Build or KSP performance | `benchmarkBuild`, generated size, artifact size, and a same-worker paired comparison |

Repository commands:

```bash
./gradlew build checkKotlinAbi
./gradlew integrationTest
./gradlew benchmarkCatalogue
./gradlew benchmarkMerge -PbenchmarkProfiles=small -PbenchmarkWarmups=1 -PbenchmarkIterations=3 -PbenchmarkForks=1
python3 scripts/check_docs.py
```

Report the exact commands and runtime paths that passed. State any untested server implementation or player-only behavior.
