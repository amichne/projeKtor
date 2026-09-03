#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: .github/scripts/test-native-executable.sh \
  --executable <path> --tag <vX.Y.Z>

Exercise the produced native executable without a configured JVM and prove
its identity, success behavior, output contracts, and typed failure behavior.
USAGE
}

executable=""
tag=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --executable)
      [[ $# -ge 2 ]] || die "Missing value for --executable"
      executable="$2"; shift 2 ;;
    --executable=*)
      executable="${1#--executable=}"; shift ;;
    --tag)
      [[ $# -ge 2 ]] || die "Missing value for --tag"
      tag="$2"; shift 2 ;;
    --tag=*)
      tag="${1#--tag=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$executable" ]] || { usage; die "--executable is required"; }
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "--tag must match vX.Y.Z: ${tag:-<empty>}"
[[ -f "$executable" ]] || die "native executable is missing: ${executable}"
[[ -x "$executable" ]] || die "native executable is not executable: ${executable}"
executable="$(cd -- "$(dirname -- "$executable")" && pwd -P)/$(basename -- "$executable")"

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
distribution_name="$(sed -n 's/^distributionName=//p' "${repo_root}/gradle.properties")"
[[ -n "$distribution_name" && "$distribution_name" != *$'\n'* ]] || \
  die "gradle.properties must define distributionName exactly once"

proof_root="$(mktemp -d "${TMPDIR:-/tmp}/${distribution_name}-native-smoke.XXXXXX")"
cleanup() {
  rm -rf -- "$proof_root"
}
trap cleanup EXIT

native_run() {
  env -u JAVA_HOME -u JDK_HOME -u GRAALVM_HOME "$executable" "$@"
}

native_run --help >"${proof_root}/help.out"
grep -Fq 'Project provider-neutral agent tooling' "${proof_root}/help.out" || \
  die "native --help output does not describe projeKtor"

native_run --version >"${proof_root}/version.out"
[[ "$(tr -d '\r\n' < "${proof_root}/version.out")" == "${distribution_name} version ${tag}" ]] || \
  die "native --version output does not match ${distribution_name} version ${tag}"

fixture="${repo_root}/.github/fixtures/source-projection"
for harness in codex github-copilot; do
  output="${proof_root}/${harness}"
  native_run project --source "$fixture" --harness "$harness" --out "$output" \
    >"${proof_root}/${harness}.out" 2>"${proof_root}/${harness}.err"
  [[ ! -s "${proof_root}/${harness}.err" ]] || die "native ${harness} projection wrote to stderr"
done
[[ -f "${proof_root}/codex/.agents/plugins/marketplace.json" ]] || \
  die "native Codex projection is missing its marketplace manifest"
[[ -f "${proof_root}/github-copilot/.github/plugin/marketplace.json" ]] || \
  die "native GitHub Copilot projection is missing its marketplace manifest"

if native_run project >"${proof_root}/failure.out" 2>"${proof_root}/failure.err"; then
  die "native executable accepted an incomplete project command"
fi
grep -Fq 'code: SOURCE_REQUIRED' "${proof_root}/failure.out" || \
  die "native expected failure did not retain its typed code"
[[ ! -s "${proof_root}/failure.err" ]] || die "native expected failure wrote to stderr"

printf 'OK native executable contract (%s, %s bytes)\n' \
  "$tag" "$(wc -c < "$executable" | tr -d '[:space:]')"
