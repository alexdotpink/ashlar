#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$script_directory/.."

if [[ "${FRAMEWORK_BENCHMARK_WORKER_ID:-}" != "framework-benchmark-v1" ]]; then
  echo "FRAMEWORK_BENCHMARK_WORKER_ID must identify the canonical framework-benchmark-v1 worker." >&2
  exit 1
fi

java_version="$(java -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
if [[ "$java_version" != 25* ]]; then
  echo "The canonical worker requires Java 25, found '$java_version'." >&2
  exit 1
fi

if [[ -r /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ]]; then
  governor="$(</sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
  if [[ "$governor" != "performance" ]]; then
    echo "CPU governor must be performance, found '$governor'." >&2
    exit 1
  fi
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "The authoritative benchmark checkout must be clean." >&2
  exit 1
fi

echo "Canonical benchmark worker checks passed for $FRAMEWORK_BENCHMARK_WORKER_ID on Java $java_version."
