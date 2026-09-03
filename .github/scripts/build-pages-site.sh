#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
requested_output="${1:-${repo_root}/site}"
output_dir="$(python3 - "$repo_root" "$requested_output" <<'PY'
import sys
from pathlib import Path

repo_root = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2])
if not output.is_absolute():
    output = repo_root / output
output = output.resolve()
if output == Path(output.anchor) or output == repo_root:
    raise SystemExit("refusing unsafe Pages output directory")
print(output)
PY
)"

distribution_name="$(sed -n 's/^distributionName=//p' "${repo_root}/gradle.properties")"
[[ -n "$distribution_name" && "$distribution_name" != *$'\n'* ]] || {
  printf 'error: gradle.properties must define distributionName exactly once\n' >&2
  exit 1
}
[[ "$distribution_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || {
  printf 'error: distributionName contains unsupported characters: %s\n' "$distribution_name" >&2
  exit 1
}

repository_identity="$(python3 - "$repo_root" "${GITHUB_SERVER_URL:-}" "${GITHUB_REPOSITORY:-}" <<'PY'
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit

repo_root = Path(sys.argv[1])
server_url = sys.argv[2].rstrip("/")
repository = sys.argv[3].strip("/")

if bool(server_url) != bool(repository):
    raise SystemExit("GITHUB_SERVER_URL and GITHUB_REPOSITORY must be provided together")

if not repository:
    try:
        origin = subprocess.run(
            ["git", "-C", str(repo_root), "config", "--get", "remote.origin.url"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except subprocess.CalledProcessError as error:
        raise SystemExit(
            "cannot derive Pages repository identity; set GITHUB_SERVER_URL and GITHUB_REPOSITORY"
        ) from error

    scp_match = re.fullmatch(r"git@([^:]+):(.+)", origin)
    if scp_match:
        host, repository = scp_match.groups()
        server_url = f"https://{host}"
    else:
        parsed = urlsplit(origin)
        if parsed.scheme not in {"http", "https", "ssh"} or not parsed.hostname:
            raise SystemExit(
                "cannot derive an HTTP repository link from origin; set GitHub runtime identity"
            )
        scheme = parsed.scheme if parsed.scheme in {"http", "https"} else "https"
        port = f":{parsed.port}" if parsed.port else ""
        server_url = f"{scheme}://{parsed.hostname}{port}"
        repository = parsed.path.strip("/")

    if repository.endswith(".git"):
        repository = repository[:-4]

if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
    raise SystemExit(f"invalid GitHub repository identity: {repository!r}")

parsed_server = urlsplit(server_url)
if (
    parsed_server.scheme not in {"http", "https"}
    or not parsed_server.hostname
    or parsed_server.username
    or parsed_server.password
    or parsed_server.query
    or parsed_server.fragment
):
    raise SystemExit(f"invalid GitHub server URL: {server_url!r}")

print(f"{server_url}/{repository}")
print(repository)
PY
)"
repository_url="${repository_identity%%$'\n'*}"
action_repository="${repository_identity#*$'\n'}"
[[ -n "$repository_url" && -n "$action_repository" && "$repository_url" != "$action_repository" ]] || {
  printf 'error: failed to resolve Pages repository identity\n' >&2
  exit 1
}

rm -rf -- "$output_dir"
mkdir -p -- "$output_dir/schemas"
cp -R "${repo_root}/docs/." "$output_dir/"
cp -R "${repo_root}/schemas/." "$output_dir/schemas/"

python3 - "$output_dir" "$distribution_name" "$repository_url" "$action_repository" <<'PY'
import json
import re
import sys
from pathlib import Path

output_dir = Path(sys.argv[1])
project_name = sys.argv[2]
repository_url = sys.argv[3]
action_repository = sys.argv[4]

tokens = {
    "{{PROJECT_NAME}}": project_name,
    "{{REPOSITORY_URL}}": repository_url,
    "{{ACTION_REPOSITORY}}": action_repository,
}
for template_path in sorted(output_dir.rglob("*.html")):
    rendered = template_path.read_text(encoding="utf-8")
    for token, value in tokens.items():
        rendered = rendered.replace(token, value)
    unresolved = sorted(set(re.findall(r"\{\{[A-Z_]+\}\}", rendered)))
    if unresolved:
        raise SystemExit(
            f"unresolved template tokens in {template_path}: {', '.join(unresolved)}"
        )
    template_path.write_text(rendered, encoding="utf-8")

schemas_root = output_dir / "schemas"
group_order = {
    "Before projection": 0,
    "Source building blocks": 1,
    "After projection · Codex": 2,
    "After projection · GitHub Copilot": 3,
}

def schema_group(relative_path: str) -> str:
    if relative_path == "marketplace/adaptable.schema.json":
        return "Before projection"
    if relative_path.startswith("core/"):
        return "Source building blocks"
    if "/codex/" in f"/{relative_path}" or relative_path == "marketplace/codex.schema.json":
        return "After projection · Codex"
    if "/github/" in f"/{relative_path}" or relative_path == "marketplace/github.schema.json":
        return "After projection · GitHub Copilot"
    raise SystemExit(f"schema is outside the supported explorer groups: {relative_path}")

entries = []
for schema_path in schemas_root.rglob("*.schema.json"):
    relative_path = schema_path.relative_to(schemas_root).as_posix()
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    entries.append(
        {
            "path": f"schemas/{relative_path}",
            "title": schema.get("title", schema_path.stem),
            "description": schema.get("description", ""),
            "draft": schema.get("$schema", ""),
            "id": schema.get("$id", ""),
            "group": schema_group(relative_path),
        }
    )

entries.sort(key=lambda entry: (group_order[entry["group"]], entry["title"].casefold()))
manifest = {
    "default": "schemas/marketplace/adaptable.schema.json",
    "schemas": entries,
}
(output_dir / "schema-index.json").write_text(
    json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
    encoding="utf-8",
)
PY

printf 'OK Pages site %s\n' "$output_dir"
