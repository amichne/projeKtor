#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd -- "$repo_root"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

is_required_path() {
  case "$1" in
    .github/actions/project/run.sh | \
      .github/fixtures/source-projection/source/* | \
      .github/scripts/test-dependency-versions.sh | \
      .github/scripts/test-distribution-identity.sh | \
      .github/scripts/test-release-asset-verifier.sh | \
      .github/scripts/test-release-workflow-contract.sh | \
      .github/scripts/test-repository-boundary.sh | \
      .github/scripts/test-source-projection-action.sh | \
      .github/scripts/verify-release-assets.sh | \
      .github/scripts/verify-release-state.sh | \
      .github/workflows/ci.yml | \
      .github/workflows/release.yml | \
      .gitignore | \
      action.yml | \
      build.gradle.kts | \
      cli/build.gradle.kts | \
      cli/src/main/kotlin/* | \
      cli/src/test/kotlin/* | \
      gradle.properties | \
      gradle/wrapper/gradle-wrapper.jar | \
      gradle/wrapper/gradle-wrapper.properties | \
      gradlew | \
      LICENSE | \
      README.md | \
      settings.gradle.kts)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

unexpected=()
while IFS= read -r path; do
  [[ -e "$path" || -L "$path" ]] || continue
  if ! is_required_path "$path"; then
    unexpected+=("$path")
  fi
done < <(git ls-files --cached --others --exclude-standard)

if (( ${#unexpected[@]} > 0 )); then
  printf 'Unexpected paths outside the published-action boundary:\n' >&2
  printf '  %s\n' "${unexpected[@]}" >&2
  exit 1
fi

action_manifests=()
while IFS= read -r manifest; do
  [[ -e "$manifest" || -L "$manifest" ]] || continue
  case "$manifest" in
    action.yml | action.yaml | */action.yml | */action.yaml)
      action_manifests+=("$manifest")
      ;;
  esac
done < <(git ls-files --cached --others --exclude-standard)
[[ "${#action_manifests[@]}" -eq 1 && "${action_manifests[0]}" == "action.yml" ]] || \
  die "expected exactly one action metadata file at action.yml; found: ${action_manifests[*]:-none}"

publication_file_contains() {
  local needle="$1"
  local path
  while IFS= read -r -d '' path; do
    [[ -f "$path" ]] || continue
    if grep -IFqn -- "$needle" "$path"; then
      grep -IFn -- "$needle" "$path" >&2
      printf 'in %s\n' "$path" >&2
      return 0
    fi
  done < <(git ls-files --cached --others --exclude-standard -z)
  return 1
}

current_repository='amichne'
current_repository+='/intelligence'
if publication_file_contains "$current_repository"; then
  die "publication files must derive the action repository instead of naming ${current_repository}"
fi

workstation_root='/Users'
workstation_root+='/amichne/'
if publication_file_contains "$workstation_root"; then
  die 'publication files must not contain workstation-specific paths'
fi

for path in action.yml .github/actions/project/run.sh .github/workflows/ci.yml .github/workflows/release.yml; do
  [[ -f "$path" ]] || die "required publication path is missing: $path"
done

printf 'OK published-action repository boundary\n'
