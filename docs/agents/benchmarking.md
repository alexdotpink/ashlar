# Benchmark workflow for agents

Use performance evidence when changing framework runtime work, generated code, native adapters, build wiring, or a path that runs per player, event, command, item, or menu render.

## Choose the smallest truthful layer

| Claim | Start with | Escalate when |
| --- | --- | --- |
| Pure Kotlin overhead | Module `benchmark` | Use `benchmarkJmh` for a stable paired result |
| Allocation or GC | `benchmarkJmh` | Use `benchmarkDiagnose` after a comparison fails |
| Paper callback or coroutine handoff | Paper integration result | Run Folia too when ownership or concurrency matters |
| Player-visible screen behavior | `benchmarkClient` | Record client video only when timing and inspection disagree |
| KSP, Gradle, generated or JAR size | `benchmarkBuild` | Inspect task profiles and generated directories |
| Concurrency or saturation | `benchmarkPlatforms` load case | Increase the deterministic actor profile |
| Retained state or delayed cleanup | Soak case | Use the weekly duration and heap diagnostics |

## Edit a contract

Keep the contract in the owning module's `src/benchmark`. Use the existing generic DSL. Define concrete profile numbers, both temperatures, semantic verification, and the smallest fixture that exercises production code. Do not time assertions, fixture construction on a warm path, logging, or unrelated plug-in work.

Use a matched control when framework overhead can be isolated. Keep end-to-end evidence when the player or persistence outcome is the claim. Do not replace one with the other.

Run `./gradlew benchmarkCatalogue` after adding or renaming a public capability. A missing contract is work to complete, not a reason to add an external exemption.

## Investigate evidence

1. Read the case identity and environment before the headline number.
2. Check callback occupancy and p99 first, allocation and retained heap second, throughput third.
3. Treat `INCONCLUSIVE` as a request for another pair.
4. Run the exact scenario and profile, then use `benchmarkDiagnose` for JFR.
5. Keep behavior tests in the verification set.

Never compare results from different worker fingerprints. Never use a busy local VPS result as an absolute release ceiling.

## Change a budget

A budget change must include the old and new source values, paired baseline/candidate JSON, the environment fingerprint, and a plain reason. Preserve the scenario ID unless its semantics changed. If semantics changed, add a new ID and retain migration context in the review.

Do not hide a regression by removing a profile, weakening verification, moving work outside the timed boundary when callers still pay for it, changing `CONTRACTUAL` to a lower status, or adding a skip path.

## Completion checklist

- The owning contract compiles and all profiles execute.
- Semantic tests pass independently of timing.
- `benchmarkCatalogue` passes.
- The selected evidence layer matches the claim.
- Paper and Folia both ran for ownership changes.
- Client evidence exists for packet-visible claims.
- A failed comparison has a separate diagnostic recording.
- Public benchmark API changes updated ABI and reference docs.
- The final report names commands, environments, and any unverified layer.

