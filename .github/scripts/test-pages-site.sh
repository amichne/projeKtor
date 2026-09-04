#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd -- "$repo_root"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_file() {
  [[ -f "$1" ]] || die "$2 is missing: $1"
}

require_file .github/scripts/build-pages-site.sh 'Pages build script'
require_file .github/workflows/pages.yml 'Pages workflow'
require_file docs/index.html 'Pages guide'
require_file docs/schema.html 'schema explorer page'
require_file docs/schema-explorer.js 'schema explorer behavior'
require_file docs/schema-reference.js 'schema reference resolver'
require_file docs/schema-presentation.js 'schema presentation model'
require_file docs/site.css 'Pages stylesheet'
require_file .github/scripts/test-schema-reference-inline.mjs 'schema reference behavior check'
require_file .github/scripts/test-schema-contract-presentation.mjs 'schema presentation behavior check'
require_file schemas/marketplace/adaptable.schema.json 'published source schema'

node .github/scripts/test-schema-reference-inline.mjs
node .github/scripts/test-schema-contract-presentation.mjs

proof_root="$(mktemp -d "${TMPDIR:-/tmp}/projektor-pages-proof.XXXXXX")"
trap 'rm -rf "$proof_root"' EXIT
GITHUB_SERVER_URL='https://github.example.test' \
GITHUB_REPOSITORY='acme/projeKtor' \
  .github/scripts/build-pages-site.sh "$proof_root/site"

require_file "$proof_root/site/index.html" 'rendered Pages guide'
require_file "$proof_root/site/schema.html" 'rendered schema explorer'
require_file "$proof_root/site/schema-explorer.js" 'rendered schema explorer behavior'
require_file "$proof_root/site/schema-reference.js" 'rendered schema reference resolver'
require_file "$proof_root/site/schema-presentation.js" 'rendered schema presentation model'
require_file "$proof_root/site/site.css" 'rendered Pages stylesheet'
require_file "$proof_root/site/schema-index.json" 'rendered schema index'
require_file "$proof_root/site/schemas/marketplace/adaptable.schema.json" 'rendered Pages schema'
diff -qr schemas "$proof_root/site/schemas" >/dev/null || \
  die 'Pages must publish the authoritative schema tree byte-for-byte'

grep -Fq '<title>projeKtor' "$proof_root/site/index.html" || die 'rendered page title must use projeKtor'
grep -Fq 'id="use"' "$proof_root/site/index.html" || die 'guide must explain how to use the Action'
grep -Fq 'id="check"' "$proof_root/site/index.html" || die 'guide must explain how to check a source tree'
grep -Fq 'id="commit"' "$proof_root/site/index.html" || die 'guide must show how to commit projected contents'
grep -Fq 'href="https://github.example.test/acme/projeKtor"' "$proof_root/site/index.html" || \
  die 'guide must link the repository resolved by the Pages build'
grep -Fq 'uses: acme/projeKtor@FULL_COMMIT_SHA' "$proof_root/site/index.html" || \
  die 'guide must render the current repository as the Action coordinate'
grep -Fq 'schema.html?schema=schemas/marketplace/adaptable.schema.json' "$proof_root/site/index.html" || \
  die 'guide must link the provider-neutral input schema explorer'
grep -Fq 'schema.html?schema=schemas/marketplace/codex.schema.json' "$proof_root/site/index.html" || \
  die 'guide must link the Codex output schema explorer'
grep -Fq 'schema.html?schema=schemas/marketplace/github.schema.json' "$proof_root/site/index.html" || \
  die 'guide must link the GitHub Copilot output schema explorer'
grep -Fq 'git diff --cached --quiet' "$proof_root/site/index.html" || \
  die 'commit example must avoid empty commits'
if grep -Eq '\./gradlew|installDevelopmentCli|build/install|bin/projeKtor' "$proof_root/site/index.html"; then
  die 'published use and check flows must not instruct readers to install or invoke the built CLI'
fi
if grep -ERq '\{\{[A-Z_]+\}\}' "$proof_root/site"; then
  die 'rendered site contains an unresolved template token'
fi

python3 - "$proof_root/site/schema-index.json" <<'PY'
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
expected = sorted(
    f"schemas/{path.relative_to('schemas').as_posix()}"
    for path in Path("schemas").rglob("*.schema.json")
)
actual = [entry["path"] for entry in manifest["schemas"]]
if sorted(actual) != expected or len(actual) != len(set(actual)):
    raise SystemExit("schema index must contain every authoritative schema exactly once")
if manifest.get("default") != "schemas/marketplace/adaptable.schema.json":
    raise SystemExit("schema explorer must open on the provider-neutral contract")
required_groups = {
    "Before projection",
    "Source building blocks",
    "After projection · Codex",
    "After projection · GitHub Copilot",
}
if {entry["group"] for entry in manifest["schemas"]} != required_groups:
    raise SystemExit("schema index must distinguish before, building-block, and after contracts")
PY

grep -Fq 'schema-index.json' "$proof_root/site/schema-explorer.js" || \
  die 'schema explorer must load the generated authoritative schema index'
grep -Fq "\$ref" "$proof_root/site/schema-explorer.js" || \
  die 'schema explorer must recognize schema references for inline rendering'
grep -Fq 'schema-reference.js' "$proof_root/site/schema.html" || \
  die 'schema explorer must load the inline reference resolver'
grep -Fq 'schema-presentation.js' "$proof_root/site/schema.html" || \
  die 'schema explorer must load the typed presentation model'
if grep -Fq 'Follow $ref' "$proof_root/site/schema-explorer.js"; then
  die 'schema explorer must inline local references instead of exposing navigation mechanics'
fi
grep -Fq 'raw-schema-link' "$proof_root/site/schema.html" || \
  die 'schema explorer must retain a raw JSON escape hatch'
if grep -Eiq '(^|[^[:alnum:]])ajv([^[:alnum:]]|$)' "$proof_root/site/schema-explorer.js"; then
  die 'schema explorer must not depend on Ajv'
fi

ruby -e 'require "yaml"; YAML.safe_load(File.read(ARGV.fetch(0)), aliases: true)' .github/workflows/pages.yml
grep -Fq 'actions/upload-pages-artifact@' .github/workflows/pages.yml || die 'Pages workflow must upload a Pages artifact'
grep -Fq 'actions/deploy-pages@' .github/workflows/pages.yml || die 'Pages workflow must deploy through GitHub Pages'
grep -Fq '.github/scripts/test-schema-contracts.sh' .github/workflows/pages.yml || die 'Pages workflow must validate the schema before upload'
grep -Fq '.github/scripts/build-pages-site.sh' .github/workflows/pages.yml || die 'Pages workflow must use the local site builder'

printf 'OK projeKtor Pages contract\n'
