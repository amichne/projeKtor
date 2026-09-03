#!/usr/bin/env bash
# shellcheck disable=SC2016 # Workflow expressions and shell variables are intentional contract literals.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd -- "$repo_root"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_literal() {
  local file_path="$1"
  local literal="$2"
  local description="$3"
  grep -Fq -- "$literal" "$file_path" || die "$description: missing '$literal' in $file_path"
}

reject_literal() {
  local file_path="$1"
  local literal="$2"
  local description="$3"
  ! grep -Fq -- "$literal" "$file_path" || die "$description: found '$literal' in $file_path"
}

build_file=cli/build.gradle.kts
action_runner=.github/actions/project/run.sh
asset_verifier=.github/scripts/verify-release-assets.sh
ci=.github/workflows/ci.yml
release=.github/workflows/release.yml

for required_path in \
  "$build_file" \
  "$action_runner" \
  "$asset_verifier" \
  "$ci" \
  "$release" \
  .github/scripts/build-native-release-asset.sh \
  .github/scripts/test-native-executable.sh
do
  [[ -f "$required_path" ]] || die "native delivery file is missing: $required_path"
done

require_literal "$build_file" 'id("org.graalvm.buildtools.native") version "1.1.9"' 'latest stable GraalVM Native Build Tools plugin is required'
require_literal "$build_file" 'imageName.set(distributionName)' 'native image must derive its name from distributionName'
require_literal "$build_file" 'mainClass.set("intelligence.cli.MainKt")' 'native image must select the application entry point'
require_literal "$build_file" '"--no-fallback"' 'native image must not embed a JVM fallback'
require_literal "$build_file" '"-O2"' 'native image must use release optimization'
require_literal "$build_file" '"-march=compatibility"' 'native image must remain compatible within its target architecture'

for target in linux-x64 macos-arm64; do
  require_literal "$asset_verifier" "\"${target}\"" "release verifier must close the native target set"
  require_literal "$release" "target: ${target}" "release workflow must build every supported native target"
done

for unsupported_target in linux-arm64 macos-x64 windows-x64; do
  reject_literal "$asset_verifier" "\"${unsupported_target}\"" 'release verifier must reject unsupported native targets'
  reject_literal "$release" "target: ${unsupported_target}" 'release workflow must not build unsupported native targets'
done

require_literal "$release" 'runner: ubuntu-24.04' 'release must build Linux x64 on its target OS'
require_literal "$release" 'runner: macos-15' 'release must build macOS arm64 on Apple silicon'
reject_literal "$release" 'ubuntu-24.04-arm' 'release must not build Linux arm64'
reject_literal "$release" 'macos-15-intel' 'release must not build macOS x64'
reject_literal "$release" 'windows-2022' 'release must not build Windows native binaries'
require_literal "$release" 'graalvm/setup-graalvm@bef4b0e916c7dd079bf60fb95d49139f67e32c5f # v1.5.3' 'GraalVM setup action must be pinned'
require_literal "$release" './gradlew :cli:nativeCompile' 'release must compile a native image'
require_literal "$release" '.github/scripts/build-native-release-asset.sh' 'release must package through the checked-in boundary'
require_literal "$release" '.github/scripts/test-native-executable.sh' 'release must smoke-test the produced executable'
require_literal "$release" 'needs.native.result == '\''success'\''' 'release publication must wait for both native targets'

require_literal "$ci" 'name: Verify native executable' 'CI must exercise a real native executable'
require_literal "$ci" 'graalvm/setup-graalvm@bef4b0e916c7dd079bf60fb95d49139f67e32c5f # v1.5.3' 'CI GraalVM setup action must be pinned'
require_literal "$ci" './gradlew :cli:nativeCompile' 'CI must compile the native image'
require_literal "$ci" '.github/scripts/test-native-executable.sh' 'CI must run the native executable contract'

require_literal "$action_runner" 'native_target_for_runner' 'Action must refine runner identity to a closed native target'
require_literal "$action_runner" '${distribution_name}-${version}-${native_target}.tar.gz' 'Action must derive the native asset name centrally'
require_literal "$action_runner" '--asset "$asset"' 'Action must verify the selected release asset'
require_literal "$asset_verifier" '--asset <archive>' 'release verifier must support selected-asset verification'

printf 'OK GraalVM native delivery contract\n'
