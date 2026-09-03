#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd -- "$repo_root"

require_literal() {
  local path="$1"
  local literal="$2"
  grep -Fq -- "$literal" "$path" || {
    printf 'error: %s must contain %s\n' "$path" "$literal" >&2
    exit 1
  }
}

require_literal cli/build.gradle.kts 'kotlin("jvm") version "2.4.10"'
require_literal cli/build.gradle.kts 'kotlin("plugin.serialization") version "2.4.10"'
require_literal cli/build.gradle.kts 'id("org.graalvm.buildtools.native") version "1.1.9"'
require_literal cli/build.gradle.kts 'com.github.ajalt.clikt:clikt-core:5.1.0'
require_literal cli/build.gradle.kts 'io.github.optimumcode:json-schema-validator:0.5.5'
require_literal cli/build.gradle.kts 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0'
if grep -Fq -- 'mordant' cli/build.gradle.kts; then
  printf 'error: unused Mordant dependency must remain removed\n' >&2
  exit 1
fi
require_literal gradle/wrapper/gradle-wrapper.properties 'gradle-9.7.1-bin.zip'
require_literal gradle/wrapper/gradle-wrapper.properties 'distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a'

if command -v sha256sum >/dev/null 2>&1; then
  wrapper_jar_sha="$(sha256sum gradle/wrapper/gradle-wrapper.jar | awk '{ print $1 }')"
else
  wrapper_jar_sha="$(shasum -a 256 gradle/wrapper/gradle-wrapper.jar | awk '{ print $1 }')"
fi
[[ "$wrapper_jar_sha" == '7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d' ]] || {
  printf 'error: unexpected Gradle wrapper JAR checksum: %s\n' "$wrapper_jar_sha" >&2
  exit 1
}

require_literal action.yml 'actions/setup-java@dd06d9cba3e5552c54d9f8ea23572deb30010f7c # v6.0.0'
require_literal action.yml 'actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0'

for workflow in .github/workflows/ci.yml .github/workflows/release.yml; do
  require_literal "$workflow" 'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1'
  require_literal "$workflow" 'actions/setup-java@dd06d9cba3e5552c54d9f8ea23572deb30010f7c # v6.0.0'
  require_literal "$workflow" 'actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0'
  require_literal "$workflow" 'graalvm/setup-graalvm@bef4b0e916c7dd079bf60fb95d49139f67e32c5f # v1.5.3'
  require_literal "$workflow" 'gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb # v6.3.0'
done

require_literal .github/workflows/release.yml 'actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1'
require_literal .github/workflows/release.yml 'actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8.0.1'

while IFS= read -r use_line; do
  coordinate="${use_line#*uses: }"
  coordinate="${coordinate%%[[:space:]]*}"
  revision="${coordinate##*@}"
  [[ "$revision" =~ ^[0-9a-f]{40}$ ]] || {
    printf 'error: external action is not pinned to a full commit SHA: %s\n' "$coordinate" >&2
    exit 1
  }
done < <(grep -hE '^[[:space:]]*uses: [^[:space:]]+@' action.yml .github/workflows/*.yml)

printf 'OK dependency versions\n'
