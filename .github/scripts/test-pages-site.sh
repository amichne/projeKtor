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
require_file schemas/marketplace/adaptable.schema.json 'published source schema'

proof_root="$(mktemp -d "${TMPDIR:-/tmp}/projektor-pages-proof.XXXXXX")"
trap 'rm -rf "$proof_root"' EXIT
.github/scripts/build-pages-site.sh "$proof_root/site"

require_file "$proof_root/site/index.html" 'rendered Pages guide'
require_file "$proof_root/site/schemas/marketplace/adaptable.schema.json" 'rendered Pages schema'
diff -qr schemas "$proof_root/site/schemas" >/dev/null || \
  die 'Pages must publish the authoritative schema tree byte-for-byte'

grep -Fq '<title>projeKtor' "$proof_root/site/index.html" || die 'rendered page title must use projeKtor'
grep -Fq 'id="use"' "$proof_root/site/index.html" || die 'guide must explain how to use the Action'
grep -Fq 'id="check"' "$proof_root/site/index.html" || die 'guide must explain how to check a source tree'
grep -Fq 'id="commit"' "$proof_root/site/index.html" || die 'guide must show how to commit projected contents'
grep -Fq 'OWNER/REPOSITORY@FULL_COMMIT_SHA' "$proof_root/site/index.html" || \
  die 'guide must keep the repository coordinate portable'
grep -Fq 'git diff --cached --quiet' "$proof_root/site/index.html" || \
  die 'commit example must avoid empty commits'
if grep -Fq '{{PROJECT_NAME}}' "$proof_root/site/index.html"; then
  die 'rendered guide contains an unresolved project-name token'
fi

ruby -e 'require "yaml"; YAML.safe_load(File.read(ARGV.fetch(0)), aliases: true)' .github/workflows/pages.yml
grep -Fq 'actions/upload-pages-artifact@' .github/workflows/pages.yml || die 'Pages workflow must upload a Pages artifact'
grep -Fq 'actions/deploy-pages@' .github/workflows/pages.yml || die 'Pages workflow must deploy through GitHub Pages'
grep -Fq '.github/scripts/test-schema-contracts.sh' .github/workflows/pages.yml || die 'Pages workflow must validate the schema before upload'
grep -Fq '.github/scripts/build-pages-site.sh' .github/workflows/pages.yml || die 'Pages workflow must use the local site builder'

printf 'OK projeKtor Pages contract\n'
