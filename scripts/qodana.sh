#!/usr/bin/env bash
#
# Run Qodana (the IntelliJ inspection engine) over the whole project, headlessly, in Docker.
#
# This is the tool `scripts/ide-diagnostics.sh` points at for whole-project coverage. The difference
# matters and is not cosmetic:
#
#   ide-diagnostics.sh  one FOCUSED file at a time, on-the-fly analysis, only files changed vs HEAD.
#                       Cannot find a global problem — "no caller anywhere", "resource referenced by
#                       nothing" — because it never looks at more than one file.
#   qodana.sh (this)    whole tree, batch mode, the profile in qodana.yaml. Finds the global ones.
#
# Neither is `./gradlew lint` / detekt / ktlint: those are different engines with different rules.
#
# COST. The linter image is several GB and the first run also does a full Gradle sync inside the
# container, so budget ~15-30 min cold. Later runs reuse .qodana/cache. Give Docker >= 8 GB of RAM;
# the engine is an IDE and will OOM below roughly 6.
#
# Usage:
#   scripts/qodana.sh                 Scan; write the HTML report + SARIF under .qodana/results.
#   scripts/qodana.sh --show          ...then open the HTML report in a browser.
#   scripts/qodana.sh --baseline      Scan, then adopt the result as the baseline (qodana.sarif.json)
#                                     so later runs report only what is NEW. Commit that file.
#   scripts/qodana.sh -- <args>       Pass the rest straight to `qodana scan`.
#
# Env overrides:
#   QODANA_RESULTS  Results dir (default: .qodana/results)
#   QODANA_CACHE    Cache dir   (default: .qodana/cache)
#   QODANA_HEAP     Engine heap        (default: 6g)
#   QODANA_MEMORY   Container ceiling  (default: 8g; must exceed QODANA_HEAP)
#   QODANA_TOKEN    Optional. Only needed to publish to Qodana Cloud; the Community linter this
#                   project pins runs fine without one.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

RESULTS="${QODANA_RESULTS:-$REPO_ROOT/.qodana/results}"
CACHE="${QODANA_CACHE:-$REPO_ROOT/.qodana/cache}"
# Heap for the engine, and a hard container ceiling above it. BOTH matter, and the ceiling is not
# belt-and-braces: uncapped, the IDE grew to 29 GB RSS and tripped the *global* OOM killer, which on
# this host picks a victim from everything running — the GitLab runner and the spool DB included.
# --memory turns that into a contained failure that kills only this scan. Keep HEAP under MEMORY:
# the JVM should hit its own limit and GC, not get SIGKILLed by the cgroup.
HEAP="${QODANA_HEAP:-6g}"
MEMORY="${QODANA_MEMORY:-8g}"
SHOW=0
BASELINE=0
EXTRA=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --show)     SHOW=1; shift ;;
    --baseline) BASELINE=1; shift ;;
    --)         shift; EXTRA=("$@"); break ;;
    -h|--help)  sed -n '19,32p' "$0"; exit 0 ;;
    *)          EXTRA+=("$1"); shift ;;
  esac
done

command -v docker >/dev/null 2>&1 || { echo "ERROR: docker is required." >&2; exit 3; }
docker info >/dev/null 2>&1 || { echo "ERROR: the Docker daemon is not reachable." >&2; exit 3; }

# The image is whatever qodana.yaml pins, so the local run and CI cannot drift apart. `image:` is the
# container; `linter:` next to it is the linter NAME and is not a docker reference.
LINTER="$(python3 -c "import yaml;print(yaml.safe_load(open('qodana.yaml'))['image'])")"

mkdir -p "$RESULTS" "$CACHE"

# Drop the IDE's cached PROJECT STATE, keeping the expensive downloads (android-sdk, jdk21, gradle).
#
# This is not hygiene, it is correctness. Reusing that state silently loses the Android facets: the
# module graph still resolves, so the run looks perfectly normal and exits 0 — but no module is an
# Android module any more, every AndroidLint inspection has nothing to attach to, and 38 findings
# become 0 with no warning anywhere. Measured 2026-09-02: warm 190 findings / 0 Lint, cold 228 / 38.
# A CI job in that state reports green while inspecting materially less than it claims, which is the
# worst failure mode an analyser has. Suspect the AGP forward-compat property (see qodana.yaml) makes
# the facet attachment too fragile to survive serialisation; either way, re-deriving it costs ~2 min
# against ~3 GB of downloads kept, so the trade is not close.
rm -rf "${CACHE:?}"/idea "${CACHE:?}"/android "${CACHE:?}"/262

# --user keeps the report files owned by you instead of root; the image expects to write as the
# caller when told to. Project is mounted read-write because the engine writes .idea scratch state.
ARGS=(--save-report --results-dir /data/results --cache-dir /data/cache
      # Gradle otherwise fetches a -sources.jar for every dependency; the engine inspects OUR
      # sources, so that is minutes of download for nothing.
      --property=idea.gradle.download.sources=false)
# --baseline READS a SARIF to diff against; it never writes one. So --baseline here means "run
# clean, then adopt this run's output as the new baseline" (the copy happens after the scan), and a
# normal run diffs against the committed qodana.sarif.json when one exists.
[[ $BASELINE -eq 0 && -f qodana.sarif.json ]] && ARGS+=(--baseline /data/project/qodana.sarif.json)

echo "Linter : $LINTER"
echo "Results: $RESULTS"
echo "Memory : heap $HEAP, container cap $MEMORY"
echo

# -it only when there is a terminal to attach to: with it, a run from CI, a background shell or an
# agent dies on "the input device is not a TTY".
TTY_FLAGS=()
[[ -t 0 && -t 1 ]] && TTY_FLAGS=(-it)

# Shadow the host's local.properties with a scratch file. It is Studio-generated, gitignored, and pins
# `sdk.dir` to a path that exists on this machine and nowhere else — inside the container Gradle reads
# it and dies with "The SDK path '<host path>' does not belong to a directory". Worse, AGP's resolver
# then REWRITES the file to point at the container's SDK, which would leave your next local Gradle
# build looking for /opt/android-sdk.
#
# The mount is deliberately WRITABLE. `:ro` looks safer and is not: the resolver treats writing
# local.properties as mandatory and fails the sync outright with "Unable to save 'local.properties':
# Read-only file system". So let it write — into a throwaway temp file that the trap deletes, while the
# real one is never opened. Gradle finds no sdk.dir in the empty shadow and falls back to
# ANDROID_SDK_ROOT below. CI clones never have this file (gitignored), so this is local-run only.
MOUNTS=(-v "$REPO_ROOT":/data/project -v "$RESULTS":/data/results -v "$CACHE":/data/cache)
if [[ -f "$REPO_ROOT/local.properties" ]]; then
  MASK="$(mktemp)"; trap 'rm -f "$MASK"' EXIT
  MOUNTS+=(-v "$MASK":/data/project/local.properties)
fi

docker run --rm "${TTY_FLAGS[@]}" \
  --memory="$MEMORY" --memory-swap="$MEMORY" \
  "${MOUNTS[@]}" \
  -e _JAVA_OPTIONS="-Xmx$HEAP" \
  -e ANDROID_HOME=/data/cache/android-sdk \
  -e ANDROID_SDK_ROOT=/data/cache/android-sdk \
  -e GRADLE_USER_HOME=/data/cache/gradle \
  ${QODANA_TOKEN:+-e QODANA_TOKEN="$QODANA_TOKEN"} \
  --user "$(id -u):$(id -g)" \
  "$LINTER" \
  "${ARGS[@]}" "${EXTRA[@]}"

echo
if [[ $BASELINE -eq 1 ]]; then
  cp "$RESULTS/qodana.sarif.json" "$REPO_ROOT/qodana.sarif.json"
  echo "Baseline written: qodana.sarif.json ($(python3 -c "import json,sys;print(len(json.load(open(sys.argv[1]))['runs'][0]['results']))" "$REPO_ROOT/qodana.sarif.json") findings accepted)."
  echo "Commit it — later runs report only what is NEW."
fi
echo "Report : $RESULTS/report/index.html"
echo "SARIF  : $RESULTS/qodana.sarif.json"
if [[ $SHOW -eq 1 ]] && command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$RESULTS/report/index.html" >/dev/null 2>&1 || true
fi
