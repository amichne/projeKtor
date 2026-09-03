#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: .github/scripts/verify-release-assets.sh \
  --release-dir <dir> --tag <vX.Y.Z> [--asset <archive>]

Without --asset, verify the complete release: one platform-neutral Kotlin/JVM
archive, Linux x64 and macOS arm64 native archives, and SHA256SUMS. With
--asset, verify exactly one downloaded archive against a closed checksum
manifest; this mode lets the Action use a native asset or an older JVM-only
release without weakening complete-release publication checks.
USAGE
}

release_dir=""
tag=""
selected_asset=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release-dir)
      [[ $# -ge 2 ]] || die "Missing value for --release-dir"
      release_dir="$2"; shift 2 ;;
    --release-dir=*)
      release_dir="${1#--release-dir=}"; shift ;;
    --tag)
      [[ $# -ge 2 ]] || die "Missing value for --tag"
      tag="$2"; shift 2 ;;
    --tag=*)
      tag="${1#--tag=}"; shift ;;
    --asset)
      [[ $# -ge 2 ]] || die "Missing value for --asset"
      selected_asset="$2"; shift 2 ;;
    --asset=*)
      selected_asset="${1#--asset=}"; shift ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      usage; die "Unknown argument: $1" ;;
  esac
done

[[ -n "$release_dir" ]] || { usage; die "--release-dir is required"; }
[[ -n "$tag" ]] || { usage; die "--tag is required"; }
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "--tag must match vX.Y.Z: $tag"
[[ -d "$release_dir" ]] || die "Release directory not found: $release_dir"
[[ -f "${release_dir}/SHA256SUMS" ]] || die "SHA256SUMS not found in $release_dir"
[[ "$selected_asset" != */* && "$selected_asset" != *\\* ]] || \
  die "--asset must be an archive name, not a path: $selected_asset"

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
distribution_name="$(sed -n 's/^distributionName=//p' "${repo_root}/gradle.properties")"
[[ -n "$distribution_name" && "$distribution_name" != *$'\n'* ]] || \
  die "gradle.properties must define distributionName exactly once"
[[ "$distribution_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || \
  die "distributionName contains unsupported characters: ${distribution_name}"

python3 - "$release_dir" "$tag" "$distribution_name" "$selected_asset" <<'PY'
import hashlib
import re
import tarfile
import sys
import zipfile
from pathlib import Path

release_dir = Path(sys.argv[1])
tag = sys.argv[2]
distribution_name = sys.argv[3]
selected_asset = sys.argv[4]

native_targets = ("linux-x64", "macos-arm64")
portable_asset = f"{distribution_name}-{tag}.tar.gz"
native_assets = {
    f"{distribution_name}-{tag}-{target}.tar.gz"
    for target in native_targets
}
expected_assets = {portable_asset, *native_assets}
portable_required_members = {
    f"{distribution_name}/bin/{distribution_name}",
    f"{distribution_name}/bin/{distribution_name}.bat",
}
forbidden_runtime_suffixes = (".py", ".pyc", ".pyo", ".rs", ".so", ".dylib", ".jnilib", ".dll", ".exe")


def fail(message: str) -> None:
    raise SystemExit(message)


actual_assets = {
    path.name
    for path in release_dir.iterdir()
    if path.is_file() and path.name.startswith(f"{distribution_name}-") and path.name.endswith(".tar.gz")
}

if selected_asset:
    if selected_asset not in expected_assets:
        fail(f"unsupported selected release asset: {selected_asset}")
    required_assets = {selected_asset}
else:
    required_assets = expected_assets

unexpected_assets = sorted(actual_assets - required_assets)
if unexpected_assets:
    fail(f"unexpected release asset: {unexpected_assets}")

missing_assets = sorted(required_assets - actual_assets)
if missing_assets:
    fail(f"missing release asset: {missing_assets}")

sha_entries: dict[str, str] = {}
for raw_line in (release_dir / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
    line = raw_line.strip()
    if not line:
        continue
    parts = line.split()
    if len(parts) != 2:
        fail(f"invalid SHA256SUMS line: {raw_line}")
    digest, asset_name = parts
    if not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
        fail(f"invalid SHA-256 digest for {asset_name}: {digest}")
    if asset_name in sha_entries:
        fail(f"duplicate checksum entry for {asset_name}")
    sha_entries[asset_name] = digest.lower()

unexpected_checksums = sorted(set(sha_entries) - expected_assets)
if unexpected_checksums:
    fail(f"unexpected checksum entry: {unexpected_checksums}")

if not selected_asset:
    missing_checksums = sorted(expected_assets - set(sha_entries))
    if missing_checksums:
        fail(f"missing checksum entry: {missing_checksums}")

for asset_name in sorted(required_assets):
    asset_path = release_dir / asset_name
    expected_digest = sha_entries.get(asset_name)
    if expected_digest is None:
        fail(f"missing checksum entry for {asset_name}")
    actual_digest = hashlib.sha256(asset_path.read_bytes()).hexdigest()
    if actual_digest != expected_digest:
        fail(f"checksum mismatch for {asset_name}: expected {expected_digest}, got {actual_digest}")

    with tarfile.open(asset_path, "r:gz") as archive:
        members = archive.getmembers()
        names = {member.name.removesuffix("/") for member in members}
        unsafe_members = sorted(
            member.name
            for member in members
            if member.name.startswith("/") or ".." in Path(member.name).parts or not (member.isfile() or member.isdir())
        )
        if unsafe_members:
            fail(f"{asset_name} contains unsafe archive member: {unsafe_members}")

        regular_files = {member.name.removesuffix("/") for member in members if member.isfile()}
        if asset_name == portable_asset:
            unexpected_members = sorted(
                name
                for name in regular_files
                if name not in portable_required_members
                and not (name.startswith(f"{distribution_name}/lib/") and name.endswith(".jar"))
            )
            if unexpected_members:
                fail(f"{asset_name} contains unexpected runtime member: {unexpected_members}")

            missing_members = sorted(portable_required_members - names)
            if missing_members:
                fail(f"{asset_name} is missing archive member: {missing_members}")

            launcher = next(
                member
                for member in members
                if member.name.removesuffix("/") == f"{distribution_name}/bin/{distribution_name}"
            )
            if launcher.mode & 0o111 == 0:
                fail(f"{asset_name} JVM launcher is not executable")

            jar_members = sorted(
                name
                for name in regular_files
                if name.startswith(f"{distribution_name}/lib/") and name.endswith(".jar")
            )
            if not jar_members:
                fail(f"{asset_name} is missing runtime JARs under {distribution_name}/lib/")

            for jar_member in jar_members:
                extracted = archive.extractfile(jar_member)
                if extracted is None:
                    fail(f"{asset_name} could not read runtime JAR {jar_member}")
                with zipfile.ZipFile(extracted) as jar:
                    forbidden_entries = sorted(
                        entry for entry in jar.namelist() if entry.lower().endswith(forbidden_runtime_suffixes)
                    )
                if forbidden_entries:
                    fail(f"{asset_name} runtime JAR {jar_member} contains forbidden entries: {forbidden_entries}")
        else:
            if regular_files != {distribution_name}:
                fail(
                    f"{asset_name} must contain exactly one root executable named "
                    f"{distribution_name}; got {sorted(regular_files)}"
                )
            native_executable = next(
                member
                for member in members
                if member.isfile() and member.name.removesuffix("/") == distribution_name
            )
            if native_executable.mode & 0o111 == 0:
                fail(f"{asset_name} native executable is not executable")

mode = f"selected asset {selected_asset}" if selected_asset else "complete release"
print(f"Verified {distribution_name} {mode} for {tag} in {release_dir}")
PY
