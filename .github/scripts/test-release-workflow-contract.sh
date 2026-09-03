#!/usr/bin/env bash
# shellcheck disable=SC2016 # Workflow expressions and shell variables are intentional literals.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd -- "$repo_root"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_literal() {
  local path="$1"
  local literal="$2"
  grep -Fq -- "$literal" "$path" || die "$path must contain: $literal"
}

reject_literal() {
  local path="$1"
  local literal="$2"
  ! grep -Fq -- "$literal" "$path" || die "$path must not contain: $literal"
}

require_order() {
  local path="$1"
  local earlier="$2"
  local later="$3"
  local earlier_line
  local later_line
  earlier_line="$(grep -nF -- "$earlier" "$path" | head -1 | cut -d: -f1)"
  later_line="$(grep -nF -- "$later" "$path" | head -1 | cut -d: -f1)"
  [[ -n "$earlier_line" && -n "$later_line" && "$earlier_line" -lt "$later_line" ]] || \
    die "$path must place '$earlier' before '$later'"
}

ci=.github/workflows/ci.yml
release=.github/workflows/release.yml
pages=.github/workflows/pages.yml

for path in "$ci" "$release" "$pages" action.yml .github/scripts/verify-release-assets.sh .github/scripts/verify-release-state.sh; do
  [[ -f "$path" ]] || die "required release path is missing: $path"
done

[[ ! -e .github/workflows/distribute-intelligence.yml ]] || die 'the superseded distribution workflow must be removed'
[[ ! -e .github/workflows/docs.yml ]] || die 'the superseded documentation workflow must remain removed'

for workflow in "$ci" "$release"; do
  ruby -e 'require "yaml"; YAML.safe_load(File.read(ARGV.fetch(0)), aliases: true)' "$workflow" || \
    die "$workflow is not valid YAML"
  require_literal "$workflow" 'actions/checkout@'
  require_literal "$workflow" 'actions/setup-java@'
  require_literal "$workflow" 'gradle/actions/setup-gradle@'
  require_literal "$workflow" './gradlew check --no-daemon'
  require_literal "$workflow" '.github/scripts/test-distribution-identity.sh'
  require_literal "$workflow" '.github/scripts/test-schema-contracts.sh'
  require_literal "$workflow" '.github/scripts/test-native-delivery.sh'
  require_literal "$workflow" '.github/scripts/test-pages-site.sh'
  require_literal "$workflow" '.github/scripts/test-repository-boundary.sh'
  reject_literal "$workflow" 'amichne/'
  reject_literal "$workflow" 'github.com/'
  reject_literal "$workflow" 'Homebrew'
  reject_literal "$workflow" 'homebrew'
done

ruby -e 'require "yaml"; YAML.safe_load(File.read(ARGV.fetch(0)), aliases: true)' "$pages" || \
  die "$pages is not valid YAML"
require_literal "$pages" '.github/scripts/test-schema-contracts.sh'
require_literal "$pages" '.github/scripts/test-pages-site.sh'
require_literal "$pages" 'actions/configure-pages@'
require_literal "$pages" 'actions/upload-pages-artifact@'
require_literal "$pages" 'actions/deploy-pages@'

require_literal "$ci" 'uses: ./'
require_literal "$ci" 'ubuntu-latest'
require_literal "$ci" 'macos-latest'
require_literal "$ci" 'windows-latest'
require_literal "$ci" 'SOURCE_PROJECTION_CLI:'
require_literal "$ci" 'harness: codex'
require_literal "$ci" 'harness: github-copilot'
require_literal "$ci" 'name: Verify native executable (${{ matrix.target }})'
require_literal "$ci" 'target: linux-x64'
require_literal "$ci" 'runner: ubuntu-24.04'
require_literal "$ci" 'target: macos-arm64'
require_literal "$ci" 'runner: macos-15'
require_literal "$ci" ':cli:nativeCompile'
require_literal "$ci" '.github/scripts/test-native-executable.sh'

require_literal "$release" 'release_type:'
require_literal "$release" 'major)'
require_literal "$release" 'minor)'
require_literal "$release" 'patch)'
require_literal "$release" 'Release workflow_dispatch must run from main'
require_literal "$release" 'gh release create "$tag" --draft --verify-tag'
require_literal "$release" ':cli:distTar'
require_literal "$release" 'name: Build native CLI (${{ matrix.target }})'
require_literal "$release" 'target: linux-x64'
require_literal "$release" 'runner: ubuntu-24.04'
require_literal "$release" 'target: macos-arm64'
require_literal "$release" 'runner: macos-15'
reject_literal "$release" 'target: linux-arm64'
reject_literal "$release" 'target: macos-x64'
reject_literal "$release" 'target: windows-x64'
require_literal "$release" ':cli:nativeCompile'
require_literal "$release" '.github/scripts/build-native-release-asset.sh'
require_literal "$release" '.github/scripts/test-native-executable.sh'
require_literal "$release" "needs.native.result == 'success'"
require_literal "$release" '.github/scripts/verify-release-assets.sh'
require_literal "$release" 'actions/upload-artifact@'
require_literal "$release" 'actions/download-artifact@'
require_literal "$release" 'pattern: release-assets-*'
require_literal "$release" 'merge-multiple: true'
require_literal "$release" 'sha256sum ./*.tar.gz > SHA256SUMS'
require_literal "$release" 'gh release upload "$RELEASE_TAG" dist/* --clobber'
require_literal "$release" 'gh release edit "$RELEASE_TAG"'
require_literal "$release" '${{ github.server_url }}/${{ github.repository }}/actions/runs/'
require_literal "$release" '.github/scripts/verify-release-state.sh'
require_order "$release" 'Build and verify distribution' 'Upload release assets'
require_order "$release" 'Compile, package, and exercise native CLI' 'Upload native release asset'
require_order "$release" 'sha256sum ./*.tar.gz > SHA256SUMS' '.github/scripts/verify-release-assets.sh --release-dir dist --tag "$RELEASE_TAG"'
require_order "$release" 'Verify and publish release' 'Verify published state'

for path in .github/scripts/verify-release-assets.sh .github/scripts/verify-release-state.sh; do
  require_literal "$path" 'distributionName='
  reject_literal "$path" 'intelligence-'
  reject_literal "$path" 'amichne/'
  reject_literal "$path" 'Homebrew'
  reject_literal "$path" 'homebrew'
done

printf 'OK release workflow contract\n'
