# Benchmark a plug-in feature

This tutorial adds one server-free benchmark to a managed framework plug-in. It produces raw JSON, a terminal comparison, and a Markdown report. The example measures a small in-memory lookup so the first run stays quick.

## Add the scenario

Create `src/benchmark/kotlin/dev/example/homes/HomeBenchmarks.kt`:

```kotlin
package dev.example.homes

import pink.alex.ashlar.benchmarks.BenchmarkTemperature
import pink.alex.ashlar.benchmarks.PerformanceContractStatus
import pink.alex.ashlar.benchmarks.benchmarkSuite
import pink.alex.ashlar.benchmarks.percent

public val homeBenchmarks = benchmarkSuite("homes") {
    benchmarkScenario("lookup") {
        status = PerformanceContractStatus.GUARDED
        profiles {
            profile("small", "homes" to 10)
            profile("typical", "homes" to 1_000)
            profile("stress", "homes" to 100_000)
        }
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)
        budgets {
            p99.regression atMost 20.percent
        }

        val homes by fixture("homes") {
            List(profile["homes"].toInt()) { index -> "home-$index" }.associateWith { it.length }
        }

        measure {
            homes["home-${profile["homes"] - 1}"]
        }

        verify { result ->
            check(result == "home-${profile["homes"] - 1}".length)
        }
    }
}
```

Keep the top-level property public. Benchmark discovery looks for public static getters that return `BenchmarkSuite`. The managed Gradle plug-in supplies the `benchmark` source set and its test-only framework dependency.

## Run a quick measurement

```bash
./gradlew benchmark \
  -PbenchmarkProfiles=small \
  -PbenchmarkWarmups=2 \
  -PbenchmarkIterations=5 \
  -PbenchmarkForks=1
```

The task prints the number of cases it wrote. Inspect `build/reports/benchmarks/run.json`. It contains separate cold and warm cases with raw samples, p50, p95, p99, throughput, allocation when the JVM supports it, the environment fingerprint, and the source budget.

## Create and compare a baseline

Preserve the first result outside `build`:

```bash
mkdir -p benchmarks/local
cp build/reports/benchmarks/run.json benchmarks/local/baseline.json
```

Run the comparison:

```bash
./gradlew benchmarkCompare \
  -PbenchmarkBaseline="$PWD/benchmarks/local/baseline.json" \
  -PbenchmarkProfiles=small \
  -PbenchmarkWarmups=2 \
  -PbenchmarkIterations=5 \
  -PbenchmarkForks=1
```

The task measures a candidate on the same machine, prints each budget decision, and writes:

- `build/reports/benchmarks/comparison.json`
- `build/reports/benchmarks/summary.md`

Local measurements are useful while coding, but they are not release evidence. The repository's authoritative job pairs baseline and candidate runs on its fingerprinted worker.

## Run the sample scenario

This repository already has a cross-feature example:

```bash
./gradlew :sample-plugin:benchmark \
  -PbenchmarkProfiles=small \
  -PbenchmarkWarmups=1 \
  -PbenchmarkIterations=3 \
  -PbenchmarkForks=1
```

The sample drives a real `PlayerInput` prompt through `InputTestHarness`, answers it, and verifies the typed result outside the timed boundary.

Next, use the [benchmark reference](../reference/benchmarks.md) to select metrics and the [regression guide](../how-to/diagnose-a-performance-regression.md) when a budget fails.

