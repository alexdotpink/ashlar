# Diagnose a performance regression

Start from the comparison JSON produced by `benchmarkCompare` or CI. Do not profile the gated run itself.

## Confirm that the comparison is valid

Check these fields before changing code:

- `environmentCompatible` is `true`.
- `missingBaselineCases` and `missingCandidateCases` are empty.
- baseline and candidate have the same scenario, profile, layer, and temperature.
- the failing evaluation is `FAILED`, not `INCONCLUSIVE`.

An incompatible fingerprint needs a dual rebaseline. An inconclusive result needs another paired run. It is not evidence of a regression by itself.

## Reduce the run to the failing case

```bash
./gradlew :ashlar-menus:benchmarkJmh \
  -PbenchmarkScenarios=menus.storage \
  -PbenchmarkProfiles=typical \
  -PbenchmarkWarmups=5 \
  -PbenchmarkIterations=20 \
  -PbenchmarkForks=3
```

Use `benchmark` for a quick edit loop. Use `benchmarkJmh` when isolated JVM measurements need fork and GC-profiler evidence. Use `benchmarkPlatforms` for scheduler, callback, load, or soak problems.

## Record diagnostics separately

```bash
./gradlew :ashlar-menus:benchmarkDiagnose \
  -PbenchmarkScenarios=menus.storage \
  -PbenchmarkProfiles=typical
```

Open `build/reports/benchmarks/diagnostic.jfr` in JDK Mission Control. The recording includes execution samples, allocations, garbage collections, and monitor contention. The accompanying `diagnostic.json` identifies the scenario and environment, but its timings do not participate in the gate.

For Paper or Folia, use the server's profiler after the benchmark result shows whether the problem is native callback occupancy, scheduling latency, or end-to-end latency. For client regressions, inspect `client.json` before recording video: packet bytes distinguish excess synchronization from slow rendering, while `CLIENT_FRAME_P99` identifies frame health.

## Verify the fix

Measure the unchanged baseline and candidate on the same worker, then compare them:

```bash
./gradlew benchmarkCompare \
  -PbenchmarkBaseline=/absolute/path/baseline.json \
  -PbenchmarkCandidate=/absolute/path/candidate.json
```

Run the semantic tests for the changed module too. A faster result that changes behavior is a bug.

If the regression is intentional, change the source budget in the same review and attach paired evidence plus the reason. Do not remove the contract, rename the case to evade its baseline, or switch it back to `EXPLORATORY`.

