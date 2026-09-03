#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd -- "$repo_root"

configured_name="$(sed -n 's/^distributionName=//p' gradle.properties)"
[[ -n "$configured_name" && "$configured_name" != *$'\n'* ]] || {
  printf 'error: gradle.properties must define distributionName exactly once\n' >&2
  exit 1
}
expected_name=projeKtor
[[ "$configured_name" == "$expected_name" ]] || {
  printf 'error: configured distribution name must be %s; found %s\n' "$expected_name" "$configured_name" >&2
  exit 1
}

action_name="$(sed -n 's/^name: //p' action.yml)"
[[ "$action_name" == "$configured_name" ]] || {
  printf 'error: action name must match distributionName exactly; found %s\n' "$action_name" >&2
  exit 1
}

proof_name=rename-proof
proof_version=v0.0.0
[[ "$proof_name" != "$configured_name" ]] || {
  printf 'error: rename proof must differ from the configured distribution name\n' >&2
  exit 1
}

./gradlew :cli:distTar \
  -PdistributionName="$proof_name" \
  -PdistributionVersion="$proof_version" \
  --no-daemon

archive="cli/build/distributions/${proof_name}.tar.gz"
[[ -f "$archive" ]] || {
  printf 'error: renamed archive is missing: %s\n' "$archive" >&2
  exit 1
}

proof_root="$(mktemp -d "${TMPDIR:-/tmp}/distribution-rename-proof.XXXXXX")"
trap 'rm -rf "$proof_root"' EXIT
tar -xzf "$archive" -C "$proof_root"
launcher="${proof_root}/${proof_name}/bin/${proof_name}"
[[ -x "$launcher" ]] || {
  printf 'error: renamed launcher is missing: %s\n' "$launcher" >&2
  exit 1
}
[[ "$("$launcher" --version)" == "${proof_name} version ${proof_version}" ]] || {
  printf 'error: renamed launcher did not inherit the centralized identity\n' >&2
  exit 1
}

if "$launcher" project >"${proof_root}/failure.stdout" 2>"${proof_root}/failure.stderr"; then
  printf 'error: renamed launcher accepted an incomplete project command\n' >&2
  exit 1
fi
grep -Fq 'code: SOURCE_REQUIRED' "${proof_root}/failure.stdout" || {
  printf 'error: renamed launcher did not preserve structured failures\n' >&2
  exit 1
}
[[ ! -s "${proof_root}/failure.stderr" ]] || {
  printf 'error: domain failure leaked to stderr\n' >&2
  exit 1
}

./gradlew :cli:distTar --no-daemon
configured_archive="cli/build/distributions/${configured_name}.tar.gz"
[[ -f "$configured_archive" ]] || {
  printf 'error: configured archive is missing after rename proof: %s\n' "$configured_archive" >&2
  exit 1
}
tar -tzf "$configured_archive" >"${proof_root}/configured.members"
if grep -Fq "${proof_name}/" "${proof_root}/configured.members"; then
  printf 'error: renamed launcher leaked into the configured distribution\n' >&2
  exit 1
fi
mkdir -p "${proof_root}/configured"
tar -xzf "$configured_archive" -C "${proof_root}/configured"
configured_launcher="${proof_root}/configured/${configured_name}/bin/${configured_name}"
[[ "$("$configured_launcher" --version)" == "${configured_name} version dev" ]] || {
  printf 'error: configured launcher was not restored after rename proof\n' >&2
  exit 1
}

printf 'OK distribution rename contract\n'
