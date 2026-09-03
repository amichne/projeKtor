# projeKtor

This repository publishes the `projeKtor` composite GitHub Action. It validates a
provider-neutral agent-tooling source tree and projects it into the native
layout for either Codex or GitHub Copilot.

```text
source tree -> validate -> project -> validate -> harness payload
harness JSON -> validate -> decode -> adaptable document -> validate -> project
```

The action does not install, register, commit, or publish the generated
payload.

## Usage

Pin the action itself to a full commit SHA and select an exact projeKtor
release in production workflows.

```yaml
- name: Check out source
  uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1

- name: Project source
  id: projection
  uses: OWNER/REPOSITORY@FULL_COMMIT_SHA
  with:
    source: .
    harness: codex
    version: v1.0.0

- name: Consume projection
  run: find "${{ steps.projection.outputs.projection-path }}" -type f
```

`harness` accepts `codex` or `github-copilot`. The outputs are
`projection-path`, `files`, and `version`. For release assets in a private
action repository, pass a `token` with read access to that repository.

The action discovers its release repository and API endpoint from the GitHub
runtime. No repository URL is compiled into the action, so the same commit can
run from a renamed repository or a GitHub Enterprise Server mirror.

On standard GitHub-hosted Linux x64 and Apple Silicon macOS runners, the
action prefers the release's GraalVM native executable. It falls back to the
portable JVM archive for Windows, unsupported architectures, and older
releases that predate native assets.

## JSON Schema and Pages

`schemas/` contains the detailed provider-neutral source contracts and the
supported Codex and GitHub Copilot output contracts. The Pages workflow
publishes that schema tree byte-for-byte alongside a small usage guide.

The CLI packages the full schema tree and can print it as one self-contained
Draft 2020-12 document:

```sh
projeKtor --schema
```

It can validate any supported document shape directly:

```sh
projeKtor validate --shape codex-plugin --input plugin.json
projeKtor validate --shape adaptable-marketplace --input adaptable.marketplace.json
```

It can also refine a Codex or GitHub Copilot marketplace/plugin into the
adaptable form while retaining the validated native document as its harness
adapter. A Codex marketplace needs an owner because that native shape does not
carry one:

```sh
projeKtor ingest --shape codex-marketplace --input marketplace.json \
  --owner-name "Platform Team" > adaptable.marketplace.json
projeKtor ingest --shape github-copilot-plugin --input plugin.json \
  > adaptable.plugin.json
projeKtor ingest --shape codex-plugin --input plugin.json \
  --into adaptable.plugin.json > adaptable.plugin.with-codex.json
```

Supported `--shape` values are `adaptable-marketplace`, `adaptable-plugin`,
`codex-marketplace`, `codex-plugin`, `github-copilot-marketplace`, and
`github-copilot-plugin`. Ingestion accepts the four harness-native values;
validation accepts all six.

The schema construction, all local references, and the packaged validator are
checked with:

```sh
.github/scripts/test-schema-contracts.sh
.github/scripts/test-pages-site.sh
```

## Identity and development

`distributionName` in `gradle.properties` is the single source of truth for
the CLI launcher, archive, development install, temporary paths, and release
asset name. The Kotlin package is an implementation detail and does not define
the published identity.

Run the full local gate with Java 21 or newer:

```sh
./gradlew check installDevelopmentCli --no-daemon
.github/scripts/test-distribution-identity.sh
.github/scripts/test-schema-contracts.sh
.github/scripts/test-native-delivery.sh
.github/scripts/test-source-projection-action.sh
.github/scripts/test-release-asset-verifier.sh
.github/scripts/test-release-workflow-contract.sh
.github/scripts/test-pages-site.sh
.github/scripts/test-repository-boundary.sh
```

Release tags use `vX.Y.Z`. Native-enabled releases contain exactly these
archives plus `SHA256SUMS`:

- `projeKtor-vX.Y.Z.tar.gz` — portable JVM fallback
- `projeKtor-vX.Y.Z-linux-x64.tar.gz` — GitHub-hosted Linux
- `projeKtor-vX.Y.Z-macos-arm64.tar.gz` — Apple Silicon macOS
