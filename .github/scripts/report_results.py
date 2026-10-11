#!/usr/bin/env python3
"""Publish build results as GitHub annotations.

Annotations can be read through the GitHub API by tools that cannot download
raw job logs, so every failure a developer needs to act on is repeated here:
test results, compiler errors, the Gradle failure summary and lint findings.

Usage: report_results.py <gradle-log-file>
"""

import glob
import re
import sys
import xml.etree.ElementTree as ET

MAX_BODY = 6000
MAX_ITEMS = 25


def emit(level: str, title: str, body: str) -> None:
    body = body.strip()
    if len(body) > MAX_BODY:
        body = body[:MAX_BODY] + "\n... truncated"
    escaped = body.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::{level} title={title}::{escaped}")


def listing(items: list[str]) -> str:
    shown = items[:MAX_ITEMS]
    more = len(items) - len(shown)
    return "\n".join(shown) + (f"\n... and {more} more" if more > 0 else "")


def report_tests() -> None:
    total = skipped = 0
    failures: list[str] = []
    for path in glob.glob("**/build/test-results/**/*.xml", recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            total += 1
            if case.find("skipped") is not None:
                skipped += 1
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                detail = (problem.get("message") or problem.text or "").strip()
                failures.append(f"{case.get('classname')}.{case.get('name')}: {detail[:500]}")
    if total == 0:
        emit("warning", "Tests", "No test results were found.")
        return
    summary = f"{total} tests, {len(failures)} failed, {skipped} skipped"
    if failures:
        emit("error", "Test failures", summary + "\n" + listing(failures))
    else:
        emit("notice", "Tests", summary)


def report_build_log(path: str) -> None:
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except OSError:
        emit("warning", "Build log", f"Could not read {path}.")
        return

    errors = [line for line in lines if line.startswith("e: ") or re.search(r"\berror:", line, re.I)]
    warnings = [line for line in lines if line.startswith("w: ")]
    if errors:
        emit("error", "Compiler errors", listing(list(dict.fromkeys(errors))))
    if warnings:
        emit("warning", "Compiler warnings", listing(list(dict.fromkeys(warnings))))

    # Gradle's own summary: everything from "FAILURE:" up to the "* Try:" hints.
    for index, line in enumerate(lines):
        if line.startswith("FAILURE:"):
            block: list[str] = []
            for following in lines[index:]:
                if following.startswith("* Try:") or following.startswith("* Exception is:"):
                    break
                block.append(following)
            emit("error", "Gradle failure", "\n".join(block))
            break


def report_lint() -> None:
    for path in glob.glob("**/build/reports/lint-results-*.xml", recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        found: dict[str, list[str]] = {}
        for issue in root.iter("issue"):
            location = issue.find("location")
            where = ""
            if location is not None:
                file_name = (location.get("file") or "").split("/")[-1]
                where = f" ({file_name}:{location.get('line')})" if location.get("line") else f" ({file_name})"
            entry = f"{issue.get('id')}: {(issue.get('message') or '')[:300]}{where}"
            found.setdefault((issue.get("severity") or "").lower(), []).append(entry)
        errors = found.get("error", []) + found.get("fatal", [])
        warnings = found.get("warning", [])
        if errors:
            emit("error", "Lint errors", listing(errors))
        if warnings:
            emit("warning", "Lint warnings", f"{len(warnings)} warnings\n" + listing(warnings))
        if not errors and not warnings:
            emit("notice", "Lint", "No lint errors or warnings.")


def main() -> None:
    report_tests()
    if len(sys.argv) > 1:
        report_build_log(sys.argv[1])
    report_lint()


if __name__ == "__main__":
    main()
