#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: .github/scripts/build-native-release-asset.sh \
  --tag <vX.Y.Z> --target <linux-x64|macos-arm64> --output-dir <dir>

Package the GraalVM nativeCompile output for the current target. The target
must match the operating system and architecture that execute this script.
USAGE
}

host_target() {
  case "$(uname -s):$(uname -m)" in
    Linux:x86_64) printf 'linux-x64\n' ;;
    Darwin:arm64) printf 'macos-arm64\n' ;;
    *) die "unsupported native build host: $(uname -s) $(uname -m)" ;;
  esac
}

tag=""
target=""
output_dir=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      [[ $# -ge 2 ]] || die "Missing value for --tag"
      tag="$2"; shift 2 ;;
    --tag=*)
      tag="${1#--tag=}"; shift ;;
    --target)
      [[ $# -ge 2 ]] || die "Missing value for --target"
      target="$2"; shift 2 ;;
    --target=*)
      target="${1#--target=}"; shift ;;
    --output-dir)
      [[ $# -ge 2 ]] || die "Missing value for --output-dir"
      output_dir="$2"; shift 2 ;;
    --output-dir=*)
      output_dir="${1#--output-dir=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "--tag must match vX.Y.Z: ${tag:-<empty>}"
case "$target" in
  linux-x64|macos-arm64) ;;
  *) die "--target must be linux-x64 or macos-arm64: ${target:-<empty>}" ;;
esac
[[ -n "$output_dir" ]] || { usage; die "--output-dir is required"; }

actual_target="$(host_target)"
[[ "$target" == "$actual_target" ]] || \
  die "target ${target} does not match native build host ${actual_target}"

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
distribution_name="$(sed -n 's/^distributionName=//p' "${repo_root}/gradle.properties")"
[[ -n "$distribution_name" && "$distribution_name" != *$'\n'* ]] || \
  die "gradle.properties must define distributionName exactly once"
[[ "$distribution_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || \
  die "distributionName contains unsupported characters: ${distribution_name}"

executable="${repo_root}/cli/build/native/nativeCompile/${distribution_name}"
[[ -f "$executable" ]] || die "nativeCompile output is missing: ${executable}"
[[ -x "$executable" ]] || die "nativeCompile output is not executable: ${executable}"

mkdir -p -- "$output_dir"
output_dir="$(cd -- "$output_dir" && pwd -P)"
staging_dir="$(mktemp -d "${TMPDIR:-/tmp}/${distribution_name}-native-package.XXXXXX")"
cleanup() {
  rm -rf -- "$staging_dir"
}
trap cleanup EXIT

cp -- "$executable" "${staging_dir}/${distribution_name}"
chmod 0755 "${staging_dir}/${distribution_name}"
asset="${distribution_name}-${tag}-${target}.tar.gz"
COPYFILE_DISABLE=1 tar -C "$staging_dir" -czf "${output_dir}/${asset}" "$distribution_name"
[[ -s "${output_dir}/${asset}" ]] || die "native archive was not created: ${output_dir}/${asset}"

printf '%s\n' "${output_dir}/${asset}"
