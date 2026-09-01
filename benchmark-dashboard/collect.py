#!/usr/bin/env python3
"""Collect bounded Ashlar benchmark evidence into a browser-friendly snapshot."""

from __future__ import annotations

import argparse
import io
import json
import os
import statistics
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

BENCHMARK_WORKFLOWS = {
    "Paired performance gate",
    "Scheduled performance evidence",
    "Benchmark smoke",
}
RESULT_SCHEMA_VERSION = 2
COMPARISON_SCHEMA_VERSION = 1


def github_json(url: str, token: str) -> Any:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "ashlar-benchmark-dashboard",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def github_bytes(url: str, token: str) -> bytes:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "ashlar-benchmark-dashboard",
        },
    )
    opener = urllib.request.build_opener(CrossHostRedirectHandler())
    with opener.open(request, timeout=60) as response:
        return response.read()


class CrossHostRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(
        self,
        request: urllib.request.Request,
        file_pointer: Any,
        code: int,
        message: str,
        headers: Any,
        new_url: str,
    ) -> urllib.request.Request | None:
        redirected = super().redirect_request(request, file_pointer, code, message, headers, new_url)
        if redirected is None:
            return None
        old_host = urllib.parse.urlparse(request.full_url).netloc
        new_host = urllib.parse.urlparse(new_url).netloc
        if old_host != new_host:
            redirected.remove_header("Authorization")
        return redirected


def compact_case(case: dict[str, Any]) -> dict[str, Any]:
    identity = case.get("id", {})
    scenario = identity.get("scenario", {})
    metrics = {
        metric["metric"]: metric["value"]
        for metric in case.get("metrics", [])
        if "metric" in metric and "value" in metric
    }
    return {
        "scenario": scenario.get("value", "unknown"),
        "profile": identity.get("profile", "unknown"),
        "layer": identity.get("layer", "unknown"),
        "temperature": identity.get("temperature", "unknown"),
        "status": case.get("status", "EXPLORATORY"),
        "metrics": metrics,
    }


def compact_result(result: dict[str, Any], source: dict[str, Any], path: str) -> dict[str, Any]:
    return {
        "source": source,
        "path": path,
        "runId": result.get("runId"),
        "revision": result.get("revision", "unknown"),
        "startedAtEpochMillis": result.get("startedAtEpochMillis"),
        "environment": result.get("environment", {}),
        "configuration": result.get("configuration", {}),
        "cases": [compact_case(case) for case in result.get("cases", [])],
    }


def compact_comparison_case(case: dict[str, Any]) -> dict[str, Any]:
    identity = case.get("id", {})
    scenario = identity.get("scenario", {})
    return {
        "scenario": scenario.get("value", "unknown"),
        "profile": identity.get("profile", "unknown"),
        "layer": identity.get("layer", "unknown"),
        "temperature": identity.get("temperature", "unknown"),
        "status": case.get("status", "EXPLORATORY"),
        "metrics": case.get("metrics", []),
        "evaluations": case.get("evaluations", []),
    }


def compact_comparison(
    comparison: dict[str, Any], source: dict[str, Any], path: str
) -> dict[str, Any]:
    return {
        "source": source,
        "path": path,
        "baselineRevision": comparison.get("baselineRevision", "unknown"),
        "candidateRevision": comparison.get("candidateRevision", "unknown"),
        "environmentCompatible": comparison.get("environmentCompatible", False),
        "status": comparison.get("status", "EXPLORATORY"),
        "missingBaselineCases": comparison.get("missingBaselineCases", []),
        "missingCandidateCases": comparison.get("missingCandidateCases", []),
        "cases": [compact_comparison_case(case) for case in comparison.get("cases", [])],
    }


def parse_documents(
    documents: Iterable[tuple[str, bytes]], source: dict[str, Any]
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    results: list[dict[str, Any]] = []
    comparisons: list[dict[str, Any]] = []
    for path, contents in documents:
        if not path.endswith(".json"):
            continue
        try:
            document = json.loads(contents)
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
        if not isinstance(document, dict) or not isinstance(document.get("cases"), list):
            continue
        if document.get("schemaVersion") == RESULT_SCHEMA_VERSION and "environment" in document:
            results.append(compact_result(document, source, path))
        elif document.get("schemaVersion") == COMPARISON_SCHEMA_VERSION and "baselineRevision" in document:
            comparisons.append(compact_comparison(document, source, path))
    return results, comparisons


def artifact_documents(archive: bytes) -> Iterable[tuple[str, bytes]]:
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        for entry in bundle.infolist():
            if not entry.is_dir() and entry.filename.endswith(".json"):
                yield entry.filename, bundle.read(entry)


def local_documents(root: Path) -> Iterable[tuple[str, bytes]]:
    for path in sorted(root.rglob("*.json")):
        yield path.relative_to(root).as_posix(), path.read_bytes()


def source_for_run(run: dict[str, Any]) -> dict[str, Any]:
    return {
        "runId": run["id"],
        "workflow": run["name"],
        "event": run.get("event"),
        "branch": run.get("head_branch"),
        "sha": run.get("head_sha"),
        "createdAt": run.get("created_at"),
        "updatedAt": run.get("updated_at"),
        "url": run.get("html_url"),
    }


def collect_github(repository: str, token: str, limit: int) -> tuple[list[Any], list[Any], list[Any]]:
    api_root = f"https://api.github.com/repos/{repository}"
    payload = github_json(f"{api_root}/actions/runs?status=completed&per_page=100", token)
    runs = [
        run
        for run in payload.get("workflow_runs", [])
        if run.get("name") in BENCHMARK_WORKFLOWS and run.get("conclusion") == "success"
    ][:limit]
    results: list[dict[str, Any]] = []
    comparisons: list[dict[str, Any]] = []
    sources: list[dict[str, Any]] = []
    for run in runs:
        source = source_for_run(run)
        sources.append(source)
        artifacts = github_json(f"{api_root}/actions/runs/{run['id']}/artifacts?per_page=100", token)
        for artifact in artifacts.get("artifacts", []):
            if artifact.get("expired"):
                continue
            archive = github_bytes(artifact["archive_download_url"], token)
            found_results, found_comparisons = parse_documents(artifact_documents(archive), source)
            results.extend(found_results)
            comparisons.extend(found_comparisons)
    return results, comparisons, sources


def comparison_regressions(comparison: dict[str, Any], metric_name: str) -> list[float]:
    return [
        metric["regressionFraction"]
        for case in comparison.get("cases", [])
        for metric in case.get("metrics", [])
        if metric.get("metric") == metric_name and isinstance(metric.get("regressionFraction"), (int, float))
    ]


def summary(comparisons: list[dict[str, Any]], results: list[dict[str, Any]]) -> dict[str, Any]:
    latest = comparisons[0] if comparisons else None
    p50 = comparison_regressions(latest, "LATENCY_P50") if latest else []
    allocations = comparison_regressions(latest, "ALLOCATION") if latest else []
    statuses = {
        case.get("status", "EXPLORATORY")
        for comparison in comparisons
        for case in comparison.get("cases", [])
    }
    return {
        "latestStatus": latest.get("status", "NO_EVIDENCE") if latest else "NO_EVIDENCE",
        "caseCount": len(latest.get("cases", [])) if latest else 0,
        "resultCount": len(results),
        "comparisonCount": len(comparisons),
        "medianP50Change": statistics.median(p50) if p50 else None,
        "allocationStableShare": (
            sum(abs(value) < 0.001 for value in allocations) / len(allocations)
            if allocations else None
        ),
        "contractStatuses": sorted(statuses),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--token", default=os.environ.get("GITHUB_TOKEN"))
    parser.add_argument("--local", type=Path)
    parser.add_argument("--limit", type=int, default=16)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if args.local:
        source = {
            "runId": "local",
            "workflow": "Local evidence",
            "event": "local",
            "branch": None,
            "sha": None,
            "createdAt": None,
            "updatedAt": None,
            "url": None,
        }
        results, comparisons = parse_documents(local_documents(args.local), source)
        sources = [source]
    else:
        if not args.repository or not args.token:
            parser.error("--repository and --token are required unless --local is used")
        results, comparisons, sources = collect_github(args.repository, args.token, args.limit)

    comparisons.sort(key=lambda item: item["source"].get("updatedAt") or "", reverse=True)
    results.sort(key=lambda item: item.get("startedAtEpochMillis") or 0, reverse=True)
    generated_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    snapshot = {
        "schemaVersion": 1,
        "generatedAt": generated_at,
        "repository": args.repository,
        "summary": summary(comparisons, results),
        "sources": sources,
        "comparisons": comparisons,
        "results": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(snapshot, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
