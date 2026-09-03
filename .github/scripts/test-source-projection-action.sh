#!/usr/bin/env bash
# shellcheck disable=SC2016 # Workflow expressions are intentional contract literals.
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_contains() {
  local file_path="$1"
  local expected="$2"
  local description="$3"
  grep -Fq -- "$expected" "$file_path" || die "${description}: missing '${expected}' in ${file_path}"
}

output_value() {
  local file_path="$1"
  local name="$2"
  sed -n "s/^${name}=//p" "$file_path" | tail -1
}

property_value() {
  local name="$1"
  local file_path="$2"
  local value
  value="$(sed -n "s/^${name}=//p" "$file_path")"
  [[ -n "$value" && "$value" != *$'\n'* ]] || die "${file_path} must define ${name} exactly once"
  printf '%s\n' "$value"
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
action_manifest="${repo_root}/action.yml"
action_runner="${repo_root}/.github/actions/project/run.sh"
identity_file="${repo_root}/gradle.properties"
fixture_root="${repo_root}/.github/fixtures/source-projection"

[[ -f "$identity_file" ]] || die "distribution identity file is missing: ${identity_file}"
distribution_name="$(property_value distributionName "$identity_file")"
development_cli="${repo_root}/.local/${distribution_name}/bin/${distribution_name}"

[[ -f "$action_manifest" ]] || die "action manifest is missing: ${action_manifest}"
[[ -f "$action_runner" ]] || die "action runner is missing: ${action_runner}"
[[ -x "$action_runner" ]] || die "action runner must be executable: ${action_runner}"
[[ -x "$development_cli" ]] || die "development CLI is missing; run ./gradlew installDevelopmentCli"
[[ -f "${fixture_root}/source/adaptable.marketplace.json" ]] || die "action fixture is missing"

ruby -e 'require "yaml"; YAML.safe_load(File.read(ARGV.fetch(0)), aliases: true)' "$action_manifest"
require_contains "$action_manifest" "name: ${distribution_name}" "Action metadata must match the centralized distribution identity"
require_contains "$action_manifest" "using: composite" "Action must use the auditable composite runtime"
require_contains "$action_manifest" "uses: actions/setup-java@" "Action must provision the released JVM runtime"
require_contains "$action_manifest" "uses: actions/setup-python@" "Action must provision the release verifier runtime"
require_contains "$action_manifest" 'ACTION_REPOSITORY: ${{ github.action_repository }}' "Action must derive its release repository"
require_contains "$action_manifest" 'ACTION_API_URL: ${{ github.api_url }}' "Action must derive its GitHub API"
require_contains "$action_manifest" 'ACTION_SERVER_URL: ${{ github.server_url }}' "Action must derive its GitHub server"
require_contains "$action_manifest" "token:" "Action must expose authentication for private release assets"
require_contains "$action_manifest" "harness:" "Action must expose the target harness"
require_contains "$action_manifest" "source:" "Action must expose the provider-neutral source"
require_contains "$action_manifest" "output:" "Action must expose the generated output path"
require_contains "$action_manifest" "version:" "Action must expose release selection"
require_contains "$action_manifest" "projection-path:" "Action must return the normalized projection path"
require_contains "$action_manifest" "files:" "Action must return the generated file count"
require_contains "$action_runner" 'distributionName' "Action runner must read the centralized distribution identity"
require_contains "$action_runner" 'ACTION_REPOSITORY' "Action runner must read the repository context"
require_contains "$action_runner" 'ACTION_API_URL' "Action runner must read the API context"
require_contains "$action_runner" 'ACTION_SERVER_URL' "Action runner must read the server context"

proof_root="$(mktemp -d "${TMPDIR:-/tmp}/source-projection-action-contract.XXXXXX")"
server_pid=""
cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" >/dev/null 2>&1 || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf "$proof_root"
}
trap cleanup EXIT
mkdir -p "$proof_root/runner-temp"

run_projection() {
  local harness="$1"
  local output="$2"
  local github_output="$3"

  GITHUB_ACTION_PATH="$repo_root" \
  GITHUB_WORKSPACE="$repo_root" \
  GITHUB_OUTPUT="$github_output" \
  RUNNER_TEMP="$proof_root/runner-temp" \
  ACTION_API_URL="https://github.enterprise.example/api/v3" \
  ACTION_REPOSITORY="enterprise/source-projection" \
  ACTION_SERVER_URL="https://github.enterprise.example" \
  INPUT_SOURCE="$fixture_root" \
  INPUT_HARNESS="$harness" \
  INPUT_OUTPUT="$output" \
  INPUT_TOKEN="" \
  INPUT_VERSION="latest" \
  SOURCE_PROJECTION_CLI="$development_cli" \
    "$action_runner"
}

codex_output="$proof_root/codex"
codex_github_output="$proof_root/codex.outputs"
run_projection "codex" "$codex_output" "$codex_github_output"
[[ -f "$codex_output/.agents/plugins/marketplace.json" ]] || die "Codex marketplace projection is missing"
codex_output="$(cd -- "$codex_output" && pwd -P)"
require_contains "$codex_github_output" "projection-path=${codex_output}" "Action must report the Codex output path"
require_contains "$codex_github_output" "version=development" "Development seam must report its version authority"
codex_files="$(output_value "$codex_github_output" files)"
[[ "$codex_files" =~ ^[0-9]+$ ]] || die "Action file count must be numeric: ${codex_files}"
[[ "$codex_files" -eq "$(find "$codex_output" -type f | wc -l | tr -d '[:space:]')" ]] || die "Action file count must match the Codex projection"

copilot_output="$proof_root/github-copilot"
copilot_github_output="$proof_root/github-copilot.outputs"
run_projection "github-copilot" "$copilot_output" "$copilot_github_output"
[[ -f "$copilot_output/.github/plugin/marketplace.json" ]] || die "GitHub Copilot marketplace projection is missing"
copilot_output="$(cd -- "$copilot_output" && pwd -P)"
require_contains "$copilot_github_output" "projection-path=${copilot_output}" "Action must report the Copilot output path"

default_github_output="$proof_root/default.outputs"
run_projection "codex" "" "$default_github_output"
default_output="$(output_value "$default_github_output" projection-path)"
runner_temp="$(cd -- "$proof_root/runner-temp" && pwd -P)"
[[ "$default_output" == "${runner_temp}"/"${distribution_name}"-projection.*/payload ]] || die "Default output must be a fresh directory under RUNNER_TEMP: ${default_output}"
[[ -f "$default_output/.agents/plugins/marketplace.json" ]] || die "Default projection output is missing"

release_dir="$proof_root/release"
mkdir -p "$release_dir"
release_asset="${distribution_name}-v0.0.0.tar.gz"
native_asset="${distribution_name}-v0.0.0-linux-x64.tar.gz"
COPYFILE_DISABLE=1 tar -C "${repo_root}/.local" -czf "${release_dir}/${release_asset}" "$distribution_name"
native_bundle="${proof_root}/native-bundle"
mkdir -p "$native_bundle"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'printf "native\n" > "$NATIVE_EXECUTION_MARKER"' \
  'exec "$NATIVE_FIXTURE_CLI" "$@"' \
  > "${native_bundle}/${distribution_name}"
chmod 0755 "${native_bundle}/${distribution_name}"
COPYFILE_DISABLE=1 tar -C "$native_bundle" -czf "${release_dir}/${native_asset}" "$distribution_name"
if command -v sha256sum >/dev/null 2>&1; then
  release_sha="$(sha256sum "${release_dir}/${release_asset}" | awk '{ print $1 }')"
  native_sha="$(sha256sum "${release_dir}/${native_asset}" | awk '{ print $1 }')"
else
  release_sha="$(shasum -a 256 "${release_dir}/${release_asset}" | awk '{ print $1 }')"
  native_sha="$(shasum -a 256 "${release_dir}/${native_asset}" | awk '{ print $1 }')"
fi
printf '%s  %s\n%s  %s\n' \
  "$release_sha" "$release_asset" \
  "$native_sha" "$native_asset" \
  >"${release_dir}/SHA256SUMS"

port_file="$proof_root/mock-api.port"
python3 - "$release_dir" "$release_asset" "$native_asset" "$port_file" <<'PY' &
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

release_dir = Path(sys.argv[1])
release_asset = sys.argv[2]
native_asset = sys.argv[3]
port_file = Path(sys.argv[4])
repository_path = "/api/v3/repos/enterprise/source-projection/releases/"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer fixture-token":
            self.send_error(401)
            return
        if self.path == repository_path + "latest":
            self.send_json({"tag_name": "v0.0.0"})
            return
        if self.path == repository_path + "tags/v0.0.0":
            base = f"http://127.0.0.1:{self.server.server_port}"
            self.send_json({
                "assets": [
                    {"name": release_asset, "url": f"{base}/assets/{release_asset}"},
                    {"name": native_asset, "url": f"{base}/assets/{native_asset}"},
                    {"name": "SHA256SUMS", "url": f"{base}/assets/SHA256SUMS"},
                ]
            })
            return
        if self.path.startswith("/assets/"):
            asset = release_dir / self.path.removeprefix("/assets/")
            if not asset.is_file() or asset.parent != release_dir:
                self.send_error(404)
                return
            payload = asset.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        self.send_error(404)

    def send_json(self, value):
        payload = json.dumps(value).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_):
        pass


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
port_file.write_text(str(server.server_port), encoding="utf-8")
server.serve_forever()
PY
server_pid="$!"
for _ in {1..50}; do
  [[ -s "$port_file" ]] && break
  sleep 0.1
done
[[ -s "$port_file" ]] || die "mock release API did not start"
mock_port="$(cat "$port_file")"

release_output="$proof_root/release-download"
release_github_output="$proof_root/release-download.outputs"
native_marker="$proof_root/native-executed"
GITHUB_ACTION_PATH="$repo_root" \
GITHUB_WORKSPACE="$repo_root" \
GITHUB_OUTPUT="$release_github_output" \
RUNNER_TEMP="$proof_root/runner-temp" \
RUNNER_OS="Linux" \
RUNNER_ARCH="X64" \
ACTION_API_URL="http://127.0.0.1:${mock_port}/api/v3" \
ACTION_REPOSITORY="enterprise/source-projection" \
ACTION_SERVER_URL="http://127.0.0.1:${mock_port}" \
INPUT_SOURCE="$fixture_root" \
INPUT_HARNESS="codex" \
INPUT_OUTPUT="$release_output" \
INPUT_TOKEN="fixture-token" \
INPUT_VERSION="latest" \
NATIVE_EXECUTION_MARKER="$native_marker" \
NATIVE_FIXTURE_CLI="$development_cli" \
SOURCE_PROJECTION_CLI="" \
  "$action_runner"
[[ -f "$release_output/.agents/plugins/marketplace.json" ]] || die "Downloaded release did not project source"
[[ -f "$native_marker" ]] || die "Linux x64 runner did not prefer the native release asset"
require_contains "$release_github_output" "version=v0.0.0" "Latest release must resolve to an exact tag"

rm -f "$native_marker"
fallback_output="$proof_root/release-fallback"
fallback_github_output="$proof_root/release-fallback.outputs"
GITHUB_ACTION_PATH="$repo_root" \
GITHUB_WORKSPACE="$repo_root" \
GITHUB_OUTPUT="$fallback_github_output" \
RUNNER_TEMP="$proof_root/runner-temp" \
RUNNER_OS="Windows" \
RUNNER_ARCH="X64" \
ACTION_API_URL="http://127.0.0.1:${mock_port}/api/v3" \
ACTION_REPOSITORY="enterprise/source-projection" \
ACTION_SERVER_URL="http://127.0.0.1:${mock_port}" \
INPUT_SOURCE="$fixture_root" \
INPUT_HARNESS="github-copilot" \
INPUT_OUTPUT="$fallback_output" \
INPUT_TOKEN="fixture-token" \
INPUT_VERSION="v0.0.0" \
NATIVE_EXECUTION_MARKER="$native_marker" \
NATIVE_FIXTURE_CLI="$development_cli" \
SOURCE_PROJECTION_CLI="" \
  "$action_runner"
[[ -f "$fallback_output/.github/plugin/marketplace.json" ]] || die "JVM fallback did not project source"
[[ ! -e "$native_marker" ]] || die "unsupported runner unexpectedly executed a native release asset"
require_contains "$fallback_github_output" "version=v0.0.0" "JVM fallback must retain the selected tag"
kill "$server_pid" >/dev/null 2>&1 || true
wait "$server_pid" 2>/dev/null || true
server_pid=""

invalid_log="$proof_root/invalid-harness.log"
if run_projection "cursor" "$proof_root/cursor" "$proof_root/cursor.outputs" >"$invalid_log" 2>&1; then
  die "Action accepted an unsupported harness"
fi
require_contains "$invalid_log" "harness must be exactly codex or github-copilot" "Action must explain the harness contract"

invalid_version_log="$proof_root/invalid-version.log"
if GITHUB_ACTION_PATH="$repo_root" \
  GITHUB_WORKSPACE="$repo_root" \
  GITHUB_OUTPUT="$proof_root/invalid-version.outputs" \
  RUNNER_TEMP="$proof_root/runner-temp" \
  ACTION_API_URL="https://github.enterprise.example/api/v3" \
  ACTION_REPOSITORY="enterprise/source-projection" \
  ACTION_SERVER_URL="https://github.enterprise.example" \
  INPUT_SOURCE="$fixture_root" \
  INPUT_HARNESS="codex" \
  INPUT_OUTPUT="$proof_root/invalid-version" \
  INPUT_TOKEN="" \
  INPUT_VERSION="main" \
  SOURCE_PROJECTION_CLI="" \
    "$action_runner" >"$invalid_version_log" 2>&1
then
  die "Action accepted a non-release version"
fi
require_contains "$invalid_version_log" "version must be latest or an exact vX.Y.Z tag" "Action must explain the version contract"

printf 'OK source projection action contract\n'
