# Benchmark reference

`ashlar-benchmarks` is a test-only artifact. It contains the scenario DSL, local runner, forked JMH runner, comparison engine, JSON model, catalogue, build runner, and JFR diagnostics. It does not enter a plug-in's shaded JAR.

## Source set and discovery

The managed Gradle plug-in creates `src/benchmark/kotlin` and these configurations:

- `benchmarkImplementation`
- `benchmarkCompileOnly`
- `benchmarkRuntimeOnly`

It adds `ashlar-benchmarks` to `benchmarkImplementation`. A discovered declaration must be a public top-level or Java-static zero-argument getter whose return type is exactly `BenchmarkSuite`. Scenario IDs are lowercase dot-separated values. Suite namespaces and profile names use lowercase letters, digits, and hyphens.

## Scenario DSL

```kotlin
public val cacheContracts = benchmarkSuite("cache") {
    benchmarkScenario("lookup") {
        status = PerformanceContractStatus.EXPLORATORY
        evidence(BenchmarkLayer.JVM)
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)
        profiles {
            profile("small", "entries" to 10)
            profile("typical", "entries" to 1_000)
            profile("stress", "entries" to 100_000)
        }
        val cache by fixture("cache", close = { it.close() }) { Cache(profile["entries"]) }
        setup { cache.resetCounters() }
        measure { cache.lookup("target") }
        verify { result -> check(result != null) }
        cleanup { cache.clearTransientState() }
    }
}
```

| Member | Boundary |
| --- | --- |
| `fixture` | Created once per warm fork or once per cold operation; closed in reverse declaration order |
| `setup` | Runs before timing |
| `measure` | Timed operation; its result is consumed to prevent dead-code removal |
| `verify` | Runs after timing and must prove the intended result |
| `cleanup` | Runs after verification and outside timing |
| `record(metric, value)` | Adds one non-negative supplemental metric for the operation |
| `consume(value)` | Explicitly retains another value needed by the measurement |

Every framework contract declares numeric `small`, `typical`, and `stress` profiles and both temperatures. `BenchmarkProfile.get(name)` returns a required `Long` parameter.

## Maturity and budgets

| Status | Enforcement |
| --- | --- |
| `EXPLORATORY` | Records evidence; budgets do not fail a gate |
| `GUARDED` | Requires and enforces at least one relative budget |
| `CONTRACTUAL` | Requires relative and absolute budgets; release checks enforce both |

Relative limits use `p99.regression atMost 5.percent`. Absolute duration limits accept Kotlin `Duration`; byte limits accept `bytesPerOperation` or `kibibytesPerOperation`; throughput uses `atLeast(Double)`.

Supported selectors are mean, p50, p95, p99, throughput, allocation, retained heap, native callback, admission, scheduling, end-to-end, tick p99, client frame p99, packet bytes, and artifact bytes.

The comparator applies the configured noise floor and bootstrap confidence interval. A confidently exceeded limit is `FAILED`. A point estimate outside the limit without enough confidence is `INCONCLUSIVE`. The authoritative workflow repeats a failed or inconclusive pair once before returning failure.

## Evidence layers

`BenchmarkLayer` values are `JVM`, `PAPER`, `FOLIA`, `CLIENT`, `BUILD`, `LOAD`, `SOAK`, and `CALIBRATION`.

- JVM scenarios measure isolated Kotlin work. `benchmarkJmh` only executes JVM and calibration cases.
- Paper and Folia fixtures split callback occupancy from asynchronous completion.
- The load case uses concurrent server-side actors.
- Soak duration comes from `-PbenchmarkSoakSeconds`; values below 60 seconds are small, 60 through 3599 are typical, and 3600 or more are stress.
- The client case opens the real native-host catalogue through DebugBridge and records visible latency, reported frame time, and TCP bytes.
- The build case measures KSP/compilation work plus generated and shaded output size.

## Gradle tasks

Every module using the benchmark source set has:

| Task | Output |
| --- | --- |
| `benchmark` | `build/reports/benchmarks/run.json` |
| `benchmarkJmh` | `build/reports/benchmarks/jmh.json` |
| `benchmarkDiagnose` | `diagnostic.json` and `diagnostic.jfr` |
| `benchmarkCompare` | `comparison.json` and `summary.md` |
| `benchmarkReport` | Renders an existing comparison |

Repository tasks add:

| Task | Purpose |
| --- | --- |
| `benchmarkCatalogue` | Checks all 132 mapped capabilities and required contract shape |
| `benchmarkMerge` | Merges compatible module results into `build/reports/benchmarks/local.json` |
| `benchmarkBuild` | Runs small, typical, and stress Gradle/KSP cases |
| `benchmarkPlatforms` | Boots pinned Paper and Folia for scheduler, command, load, and soak cases |
| `benchmarkClient` | Uses the configured Minecraft 26.2 DebugBridge client |
| `benchmarkAll` | Runs local module, build, merge, and catalogue tasks |

Common properties:

| Property | Default | Meaning |
| --- | ---: | --- |
| `benchmarkProfiles` | all | Comma-separated profile selection |
| `benchmarkScenarios` | all | Comma-separated exact scenario IDs |
| `benchmarkWarmups` | 5 | Warmup iterations |
| `benchmarkIterations` | 20 | Measurement iterations |
| `benchmarkForks` | 3 | Replicated forks |
| `benchmarkWarmupMillis` | 250 | JMH warmup time per iteration |
| `benchmarkMeasurementMillis` | 500 | JMH measurement time per iteration |
| `benchmarkRevision` | `working-tree` | Revision stored in results |
| `benchmarkBaseline` | none | Baseline JSON for comparison |
| `benchmarkCandidate` | task result | Candidate JSON where supported |
| `benchmarkSoakSeconds` | 1 | Paper/Folia soak duration |
| `benchmarkClientPort` | 9877 | DebugBridge WebSocket port |
| `benchmarkClientServerPort` | 25565 | TCP connection used for byte counters |
| `benchmarkClientConfig` | VPS client path | DebugBridge config containing the local auth token |

## Result JSON

`BenchmarkRunResult.schemaVersion` is `2`. A run contains revision, start time, environment, configuration, and unique cases. A case identity is scenario, profile, layer, and temperature. Cases retain aggregate metrics, raw duration/allocation samples, supplemental samples, maturity, and serialized budgets.

The environment fingerprint includes worker ID, OS, architecture, processor count and model, JVM, relevant JVM arguments, collectors, Kotlin and framework versions, platform versions, and sorted attributes. Comparison rejects mismatched fingerprints. Runner selection properties and benchmark class directories are excluded because they identify the case, not the machine.

`BenchmarkGateStatus` is `PASSED`, `FAILED`, `INCONCLUSIVE`, `INCOMPATIBLE`, or `MISSING_CASES`. CLI exit codes are 0 for success, 1 for a failed/incompatible/missing comparison, 2 for invalid input or execution failure, and 3 for an inconclusive comparison.

## Catalogue

`AshlarPerformanceCatalogue` maps public capabilities to 19 representative contracts. Validation requires every discovered contract to declare all three profiles and both temperatures. Platform, client, build, load, and soak contracts are marked as externally executed. Passing the ordinary catalogue means coverage declarations are complete. `-PbenchmarkReleaseReady=true` additionally requires every discovered scenario to be `CONTRACTUAL`.

The current contracts remain `EXPLORATORY` until the dedicated worker is provisioned, paired baselines establish noise floors, and reviewed absolute ceilings are committed.
