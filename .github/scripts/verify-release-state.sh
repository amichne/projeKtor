#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: .github/scripts/verify-release-state.sh --tag <vX.Y.Z> [options]

Verify that a projector release is fully published:
  - GitHub release exists, is not draft, and is not a prerelease.
  - Release archives and SHA256SUMS pass local asset verification.
  - The release is the repository's latest release.

Options:
  --repository <owner/repo>  GitHub repository. Defaults to GITHUB_REPOSITORY.
  --work-dir <dir>           Directory for downloaded assets. Defaults to a temp dir.
USAGE
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
tag=""
repository="${GITHUB_REPOSITORY:-}"
work_dir=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      [[ $# -ge 2 ]] || die "Missing value for --tag"
      tag="$2"; shift 2 ;;
    --tag=*)
      tag="${1#--tag=}"; shift ;;
    --repository)
      [[ $# -ge 2 ]] || die "Missing value for --repository"
      repository="$2"; shift 2 ;;
    --repository=*)
      repository="${1#--repository=}"; shift ;;
    --work-dir)
      [[ $# -ge 2 ]] || die "Missing value for --work-dir"
      work_dir="$2"; shift 2 ;;
    --work-dir=*)
      work_dir="${1#--work-dir=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$tag" ]] || { usage; die "--tag is required"; }
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "--tag must match vX.Y.Z: $tag"
[[ "$repository" == */* ]] || die "--repository must look like owner/repo"
command -v gh >/dev/null 2>&1 || die "gh is required"

distribution_name="$(sed -n 's/^distributionName=//p' "${repo_root}/gradle.properties")"
[[ -n "$distribution_name" && "$distribution_name" != *$'\n'* ]] || \
  die "gradle.properties must define distributionName exactly once"

cleanup_dir=""
if [[ -z "$work_dir" ]]; then
  cleanup_dir="$(mktemp -d "${TMPDIR:-/tmp}/projector-release-state.XXXXXX")"
  work_dir="$cleanup_dir"
else
  mkdir -p "$work_dir"
fi

cleanup() {
  if [[ -n "$cleanup_dir" ]]; then
    rm -rf "$cleanup_dir"
  fi
}
trap cleanup EXIT

require_false() {
  local value="$1"
  local message="$2"
  [[ "$value" == "false" ]] || die "$message"
}

is_draft="$(gh release view "$tag" --repo "$repository" --json isDraft --jq .isDraft)"
is_prerelease="$(gh release view "$tag" --repo "$repository" --json isPrerelease --jq .isPrerelease)"
require_false "$is_draft" "GitHub release ${tag} is still a draft"
require_false "$is_prerelease" "Release ${tag} is marked prerelease"
latest_tag="$(gh api "repos/${repository}/releases/latest" --jq .tag_name)"
[[ "$latest_tag" == "$tag" ]] || die "Release ${tag} is not latest; latest is ${latest_tag}"

release_dir="${work_dir}/release-assets"
rm -rf "$release_dir"
mkdir -p "$release_dir"
gh release download "$tag" --repo "$repository" --dir "$release_dir" --pattern "${distribution_name}-*.tar.gz"
gh release download "$tag" --repo "$repository" --dir "$release_dir" --pattern 'SHA256SUMS'
"${repo_root}/.github/scripts/verify-release-assets.sh" --release-dir "$release_dir" --tag "$tag"

printf 'Verified published release state for %s\n' "$tag"
