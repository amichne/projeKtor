#!/usr/bin/env bash
set -euo pipefail

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: .github/scripts/verify-release-assets.sh --release-dir <dir> --tag <vX.Y.Z>

Verify a downloaded projector release directory. The directory must contain
one platform-neutral Kotlin/JVM CLI archive and SHA256SUMS.
USAGE
}

release_dir=""
tag=""

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

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
distribution_name="$(sed -n 's/^distributionName=//p' "${repo_root}/gradle.properties")"
[[ -n "$distribution_name" && "$distribution_name" != *$'\n'* ]] || \
  die "gradle.properties must define distributionName exactly once"
[[ "$distribution_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || \
  die "distributionName contains unsupported characters: ${distribution_name}"

python3 - "$release_dir" "$tag" "$distribution_name" <<'PY'
import hashlib
import tarfile
import sys
import zipfile
from pathlib import Path

release_dir = Path(sys.argv[1])
tag = sys.argv[2]
distribution_name = sys.argv[3]

expected_assets = {f"{distribution_name}-{tag}.tar.gz"}
required_members = {
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

unexpected_assets = sorted(actual_assets - expected_assets)
if unexpected_assets:
    fail(f"unexpected release asset: {unexpected_assets}")

missing_assets = sorted(expected_assets - actual_assets)
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
    if asset_name in sha_entries:
        fail(f"duplicate checksum entry for {asset_name}")
    sha_entries[asset_name] = digest

unexpected_checksums = sorted(set(sha_entries) - expected_assets)
if unexpected_checksums:
    fail(f"unexpected checksum entry: {unexpected_checksums}")

for asset_name in sorted(expected_assets):
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

        regular_files = {member.name for member in members if member.isfile()}
        unexpected_members = sorted(
            name
            for name in regular_files
            if name not in required_members and not (name.startswith(f"{distribution_name}/lib/") and name.endswith(".jar"))
        )
        if unexpected_members:
            fail(f"{asset_name} contains unexpected runtime member: {unexpected_members}")

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

    missing_members = sorted(required_members - names)
    if missing_members:
        fail(f"{asset_name} is missing archive member: {missing_members}")

print(f"Verified projector release assets for {tag} in {release_dir}")
PY
