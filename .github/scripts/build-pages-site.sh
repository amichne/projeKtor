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

rm -rf -- "$output_dir"
mkdir -p -- "$output_dir/schemas"
cp -R "${repo_root}/schemas/." "$output_dir/schemas/"

python3 - "${repo_root}/docs/index.html" "$output_dir/index.html" "$distribution_name" <<'PY'
import sys
from pathlib import Path

template_path = Path(sys.argv[1])
output_path = Path(sys.argv[2])
project_name = sys.argv[3]
template = template_path.read_text(encoding="utf-8")
token = "{{PROJECT_NAME}}"
if token not in template:
    raise SystemExit(f"missing project-name token in {template_path}")
rendered = template.replace(token, project_name)
if token in rendered:
    raise SystemExit(f"unresolved project-name token in {template_path}")
output_path.write_text(rendered, encoding="utf-8")
PY

printf 'OK Pages site %s\n' "$output_dir"
