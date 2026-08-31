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
| Gradle plug-in | Gradle TestKit test and a shaded sample JAR |
| Documentation | Local-link check, code samples compared with current source, full build if samples changed |

Repository commands:

```bash
./gradlew build checkKotlinAbi
./gradlew integrationTest
python3 scripts/check_docs.py
```

Report the exact commands and runtime paths that passed. State any untested server implementation or player-only behavior.
