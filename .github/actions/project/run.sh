#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

download_root=""
runtime_root=""
downloaded_cli=""

cleanup() {
  local exit_code="$?"
  if [[ -n "$download_root" ]]; then
    rm -rf -- "$download_root"
  fi
  if [[ -n "$runtime_root" ]]; then
    rm -rf -- "$runtime_root"
  fi
  exit "$exit_code"
}

trap cleanup EXIT

require_value() {
  local name="$1"
  local value="$2"
  [[ -n "$value" ]] || die "${name} is required"
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || die "${name} must not contain line breaks"
}

property_value() {
  local name="$1"
  local file_path="$2"
  local value
  value="$(sed -n "s/^${name}=//p" "$file_path")"
  [[ -n "$value" && "$value" != *$'\n'* ]] || die "${file_path} must define ${name} exactly once"
  printf '%s\n' "$value"
}

runner_path() {
  local path="$1"
  if [[ "${RUNNER_OS:-}" == "Windows" ]] && command -v cygpath >/dev/null 2>&1; then
    cygpath --unix "$path"
  else
    printf '%s\n' "$path"
  fi
}

external_path() {
  local path="$1"
  if [[ "${RUNNER_OS:-}" == "Windows" ]] && command -v cygpath >/dev/null 2>&1; then
    cygpath --mixed "$path"
  else
    printf '%s\n' "$path"
  fi
}

absolute_source_path() {
  local requested="$1"
  local candidate
  if [[ "$requested" == /* || "$requested" =~ ^[A-Za-z]:[\\/].* ]]; then
    candidate="$(runner_path "$requested")"
  else
    candidate="${workspace_root}/${requested}"
  fi
  [[ -d "$candidate" ]] || die "source directory does not exist: ${candidate}"
  cd -- "$candidate" && pwd -P
}

absolute_output_path() {
  local requested="$1"
  local candidate
  local parent
  local name

  if [[ -z "$requested" ]]; then
    mkdir -p -- "$runner_temp_root"
    candidate="$(mktemp -d "${runner_temp_root}/${distribution_name}-projection.XXXXXX")/payload"
  elif [[ "$requested" == /* || "$requested" =~ ^[A-Za-z]:[\\/].* ]]; then
    candidate="$(runner_path "$requested")"
  else
    candidate="${workspace_root}/${requested}"
  fi

  parent="$(dirname -- "$candidate")"
  name="$(basename -- "$candidate")"
  mkdir -p -- "$parent"
  parent="$(cd -- "$parent" && pwd -P)"
  printf '%s/%s\n' "$parent" "$name"
}

github_api_download() {
  local url="$1"
  local destination="$2"
  local accept="$3"
  local args=(
    --fail
    --location
    --retry 3
    --silent
    --show-error
    --header "Accept: ${accept}"
    --output "$destination"
  )
  if [[ -n "$token_input" ]]; then
    args+=(--header "Authorization: Bearer ${token_input}")
  fi
  curl "${args[@]}" "$url"
}

release_metadata() {
  local selector="$1"
  local destination="$2"
  github_api_download \
    "${action_api_url%/}/repos/${action_repository}/releases/${selector}" \
    "$destination" \
    "application/vnd.github+json"
}

resolve_latest_version() {
  local metadata
  local resolved
  metadata="$(mktemp "${runner_temp_root}/${distribution_name}-latest.XXXXXX")"
  release_metadata "latest" "$metadata"
  resolved="$(python3 - "$metadata" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    value = json.load(source).get("tag_name", "")
print(value)
PY
)"
  rm -f -- "$metadata"
  [[ "$resolved" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
    die "latest release for ${action_server_url%/}/${action_repository} did not resolve to vX.Y.Z"
  printf '%s\n' "$resolved"
}

native_target_for_runner() {
  case "${RUNNER_OS:-}:${RUNNER_ARCH:-}" in
    Linux:X64) printf 'linux-x64\n' ;;
    macOS:ARM64) printf 'macos-arm64\n' ;;
    *) return 1 ;;
  esac
}

download_cli() {
  local version="$1"
  local destination="$2"
  local portable_asset="${distribution_name}-${version}.tar.gz"
  local native_target=""
  local preferred_asset=""
  local asset
  local asset_url
  local metadata
  local asset_urls
  local checksum_url

  if native_target="$(native_target_for_runner)"; then
    preferred_asset="${distribution_name}-${version}-${native_target}.tar.gz"
  fi

  download_root="$(mktemp -d "${runner_temp_root}/${distribution_name}-download.XXXXXX")"
  metadata="${download_root}/release.json"
  release_metadata "tags/${version}" "$metadata"

  asset_urls="$(python3 - "$metadata" "$preferred_asset" "$portable_asset" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    assets = json.load(source).get("assets", [])
if not isinstance(assets, list):
    raise SystemExit("release assets must be a list")

by_name = {}
for asset in assets:
    if not isinstance(asset, dict):
        raise SystemExit("release contains malformed asset metadata")
    name = asset.get("name")
    url = asset.get("url")
    if not isinstance(name, str) or not isinstance(url, str) or not name or not url:
        raise SystemExit("release contains incomplete asset metadata")
    if name in by_name:
        raise SystemExit(f"release contains duplicate asset metadata: {name}")
    by_name[name] = url

preferred = sys.argv[2]
portable = sys.argv[3]
selected = preferred if preferred and preferred in by_name else portable
for name in (selected, "SHA256SUMS"):
    if name not in by_name:
        raise SystemExit(f"release is missing asset: {name}")

print(selected)
print(by_name[selected])
print(by_name["SHA256SUMS"])
PY
)"
  asset="$(printf '%s\n' "$asset_urls" | sed -n '1p')"
  asset_url="$(printf '%s\n' "$asset_urls" | sed -n '2p')"
  checksum_url="$(printf '%s\n' "$asset_urls" | sed -n '3p')"
  [[ -n "$asset" && -n "$asset_url" && -n "$checksum_url" ]] || \
    die "release asset metadata is incomplete for ${version}"
  [[ "$(printf '%s\n' "$asset_urls" | wc -l | tr -d '[:space:]')" -eq 3 ]] || \
    die "release asset metadata is ambiguous for ${version}"

  github_api_download "$asset_url" "${download_root}/${asset}" "application/octet-stream"
  github_api_download "$checksum_url" "${download_root}/SHA256SUMS" "application/octet-stream"
  rm -f -- "$metadata"

  "${action_root}/.github/scripts/verify-release-assets.sh" \
    --release-dir "$download_root" \
    --tag "$version" \
    --asset "$asset"

  mkdir -p -- "$destination"
  tar -xzf "${download_root}/${asset}" -C "$destination"
  if [[ "$asset" == "$portable_asset" ]]; then
    downloaded_cli="${destination}/${distribution_name}/bin/${distribution_name}"
  else
    downloaded_cli="${destination}/${distribution_name}"
  fi
  rm -rf -- "$download_root"
  download_root=""
}

source_input="${INPUT_SOURCE:-}"
harness="${INPUT_HARNESS:-}"
output_input="${INPUT_OUTPUT:-}"
token_input="${INPUT_TOKEN:-}"
version_input="${INPUT_VERSION:-}"

require_value "source" "$source_input"
require_value "harness" "$harness"
require_value "version" "$version_input"
[[ "$output_input" != *$'\n'* && "$output_input" != *$'\r'* ]] || die "output must not contain line breaks"
[[ "$token_input" != *$'\n'* && "$token_input" != *$'\r'* ]] || die "token must not contain line breaks"

case "$harness" in
  codex|github-copilot) ;;
  *) die "harness must be exactly codex or github-copilot: ${harness}" ;;
esac

require_value "GITHUB_ACTION_PATH" "${GITHUB_ACTION_PATH:-}"
require_value "GITHUB_WORKSPACE" "${GITHUB_WORKSPACE:-}"
require_value "GITHUB_OUTPUT" "${GITHUB_OUTPUT:-}"
require_value "RUNNER_TEMP" "${RUNNER_TEMP:-}"

if [[ "$version_input" != "latest" && ! "$version_input" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  die "version must be latest or an exact vX.Y.Z tag: ${version_input}"
fi

action_root="$(runner_path "$GITHUB_ACTION_PATH")"
workspace_root="$(runner_path "$GITHUB_WORKSPACE")"
runner_temp_root="$(runner_path "$RUNNER_TEMP")"
github_output_path="$(runner_path "$GITHUB_OUTPUT")"
identity_file="${action_root}/gradle.properties"
[[ -f "$identity_file" ]] || die "distribution identity file is missing: ${identity_file}"
distribution_name="$(property_value distributionName "$identity_file")"
[[ "$distribution_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die "distributionName contains unsupported characters: ${distribution_name}"

source_path="$(absolute_source_path "$source_input")"
output_path="$(absolute_output_path "$output_input")"

if [[ -n "${SOURCE_PROJECTION_CLI:-}" ]]; then
  cli="${SOURCE_PROJECTION_CLI}"
  resolved_version="development"
  [[ -x "$cli" ]] || die "SOURCE_PROJECTION_CLI is not executable: ${cli}"
else
  action_api_url="${ACTION_API_URL:-}"
  action_repository="${ACTION_REPOSITORY:-}"
  action_server_url="${ACTION_SERVER_URL:-}"
  require_value "ACTION_API_URL" "$action_api_url"
  require_value "ACTION_REPOSITORY" "$action_repository"
  require_value "ACTION_SERVER_URL" "$action_server_url"
  [[ "$action_api_url" =~ ^https?://[^[:space:]]+$ ]] || die "ACTION_API_URL must be an HTTP(S) URL"
  [[ "$action_server_url" =~ ^https?://[^[:space:]]+$ ]] || die "ACTION_SERVER_URL must be an HTTP(S) URL"
  [[ "$action_repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "ACTION_REPOSITORY must be owner/repository"
  if [[ "$version_input" == "latest" ]]; then
    resolved_version="$(resolve_latest_version)"
  else
    resolved_version="$version_input"
  fi
  [[ "$resolved_version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "version must be latest or an exact vX.Y.Z tag: ${resolved_version}"

  runtime_root="$(mktemp -d "${runner_temp_root}/${distribution_name}-runtime.XXXXXX")"
  download_cli "$resolved_version" "$runtime_root"
  cli="$downloaded_cli"
  [[ -f "$cli" ]] || die "release launcher is missing: ${cli}"
  chmod +x "$cli"
  [[ -x "$cli" ]] || die "release launcher is not executable: ${cli}"
fi

"$cli" project \
  --source "$source_path" \
  --harness "$harness" \
  --out "$output_path"

[[ -d "$output_path" ]] || die "projeKtor did not create the output directory: ${output_path}"
file_count="$(find "$output_path" -type f | wc -l | tr -d '[:space:]')"
[[ "$file_count" =~ ^[0-9]+$ ]] || die "projected file count is not numeric: ${file_count}"
reported_output_path="$(external_path "$output_path")"

{
  printf 'projection-path=%s\n' "$reported_output_path"
  printf 'files=%s\n' "$file_count"
  printf 'version=%s\n' "$resolved_version"
} >> "$github_output_path"
