#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

resolve_repo_root() {
  local script_dir
  script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  cd -- "${script_dir}/../.." && pwd
}

compute_sha256() {
  local input_path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$input_path" | awk '{ print $1 }'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$input_path" | awk '{ print $1 }'
    return
  fi
  die "Neither sha256sum nor shasum is available"
}

write_asset() {
  local release_dir="$1"
  local tag="$2"
  local asset_path="${release_dir}/${distribution_name}-${tag}.tar.gz"
  local bundle_dir="${scratch_dir}/bundle"

  rm -rf "$bundle_dir"
  mkdir -p "${bundle_dir}/${distribution_name}/bin" "${bundle_dir}/${distribution_name}/lib" "${bundle_dir}/jar-content"
  printf '#!/usr/bin/env sh\nprintf "projector JVM\\n"\n' > "${bundle_dir}/${distribution_name}/bin/${distribution_name}"
  printf '@echo off\r\necho projector JVM\r\n' > "${bundle_dir}/${distribution_name}/bin/${distribution_name}.bat"
  printf 'fixture\n' > "${bundle_dir}/jar-content/fixture.txt"
  jar --create --file "${bundle_dir}/${distribution_name}/lib/${distribution_name}.jar" -C "${bundle_dir}/jar-content" fixture.txt
  chmod 0755 "${bundle_dir}/${distribution_name}/bin/${distribution_name}"
  COPYFILE_DISABLE=1 tar -C "$bundle_dir" -czf "$asset_path" "$distribution_name"
}

write_sha256sums() {
  local release_dir="$1"
  : > "${release_dir}/SHA256SUMS"
  local asset_path
  for asset_path in "${release_dir}"/"${distribution_name}"-*.tar.gz; do
    printf '%s  %s\n' "$(compute_sha256 "$asset_path")" "$(basename -- "$asset_path")" >> "${release_dir}/SHA256SUMS"
  done
}

repo_root="$(resolve_repo_root)"
verifier="${repo_root}/.github/scripts/verify-release-assets.sh"
distribution_name="$(sed -n 's/^distributionName=//p' "${repo_root}/gradle.properties")"
[[ -n "$distribution_name" && "$distribution_name" != *$'\n'* ]] || \
  die "gradle.properties must define distributionName exactly once"
[[ -x "$verifier" ]] || die "release asset verifier is missing or not executable: $verifier"

scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/projector-release-assets.XXXXXX")"
cleanup() {
  rm -rf "$scratch_dir"
}
trap cleanup EXIT

tag="v9.8.7"
release_dir="${scratch_dir}/release"
mkdir -p "$release_dir"

write_asset "$release_dir" "$tag"
write_sha256sums "$release_dir"
"$verifier" --release-dir "$release_dir" --tag "$tag"

rm -rf "$release_dir"
mkdir -p "$release_dir"
: > "${release_dir}/SHA256SUMS"
if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/missing.err"; then
  die "release without JVM archive unexpectedly verified"
fi
grep -Fq "missing release asset" "${scratch_dir}/missing.err" \
  || die "missing asset failure did not mention missing release asset"

rm -rf "$release_dir"
mkdir -p "$release_dir"
write_asset "$release_dir" "$tag"
write_sha256sums "$release_dir"
printf 'tampered\n' >> "${release_dir}/${distribution_name}-${tag}.tar.gz"
if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/checksum.err"; then
  die "tampered asset unexpectedly verified"
fi
grep -Fq "checksum mismatch" "${scratch_dir}/checksum.err" \
  || die "tampered asset failure did not mention checksum mismatch"

rm -rf "$release_dir"
mkdir -p "$release_dir"
write_asset "$release_dir" "$tag"
write_sha256sums "$release_dir"
cp "${release_dir}/${distribution_name}-${tag}.tar.gz" "${release_dir}/${distribution_name}-${tag}-debug.tar.gz"
printf '%s  %s\n' \
  "$(compute_sha256 "${release_dir}/${distribution_name}-${tag}-debug.tar.gz")" \
  "${distribution_name}-${tag}-debug.tar.gz" \
  >> "${release_dir}/SHA256SUMS"
if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/extra.err"; then
  die "extra asset unexpectedly verified"
fi
grep -Fq "unexpected release asset" "${scratch_dir}/extra.err" \
  || die "extra asset failure did not mention unexpected release asset"

rm -rf "$release_dir"
mkdir -p "$release_dir"
write_asset "$release_dir" "$tag"
rm -rf "${scratch_dir}/forbidden"
mkdir -p "${scratch_dir}/forbidden"
COPYFILE_DISABLE=1 tar -xzf "${release_dir}/${distribution_name}-${tag}.tar.gz" -C "${scratch_dir}/forbidden"
printf 'native fixture\n' > "${scratch_dir}/forbidden/native.so"
jar --update \
  --file "${scratch_dir}/forbidden/${distribution_name}/lib/${distribution_name}.jar" \
  -C "${scratch_dir}/forbidden" native.so
COPYFILE_DISABLE=1 tar \
  -C "${scratch_dir}/forbidden" \
  -czf "${release_dir}/${distribution_name}-${tag}.tar.gz" \
  "$distribution_name"
write_sha256sums "$release_dir"
if "$verifier" --release-dir "$release_dir" --tag "$tag" >/dev/null 2>"${scratch_dir}/native.err"; then
  die "archive with native JAR entry unexpectedly verified"
fi
grep -Fq "contains forbidden entries" "${scratch_dir}/native.err" \
  || die "native JAR failure did not mention forbidden entries"

printf 'OK release asset verifier\n'
