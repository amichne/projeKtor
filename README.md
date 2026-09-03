# Source Projection Action

This repository publishes one composite GitHub Action. It validates a
provider-neutral agent-tooling source tree and projects it into the native
layout for either Codex or GitHub Copilot.

```text
source tree -> validate -> project -> validate -> harness payload
```

The action does not install, register, commit, or publish the generated
payload.

## Usage

Pin the action itself to a full commit SHA and select an exact projector
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
    version: v1.2.3

- name: Consume projection
  run: find "${{ steps.projection.outputs.projection-path }}" -type f
```

`harness` accepts `codex` or `github-copilot`. The outputs are
`projection-path`, `files`, and `version`. For release assets in a private
action repository, pass a `token` with read access to that repository.

The action discovers its release repository and API endpoint from the GitHub
runtime. No repository URL is compiled into the action, so the same commit can
run from a renamed repository or a GitHub Enterprise Server mirror.

## Rename and development

`distributionName` in `gradle.properties` is the single source of truth for
the CLI launcher, archive, development install, temporary paths, and release
asset name. The Kotlin package is an implementation detail and does not define
the published identity.

Run the full local gate with Java 21 or newer:

```sh
./gradlew check installDevelopmentCli --no-daemon
.github/scripts/test-distribution-identity.sh
.github/scripts/test-source-projection-action.sh
.github/scripts/test-release-workflow-contract.sh
.github/scripts/test-repository-boundary.sh
```

Release tags use `vX.Y.Z`. Each release contains exactly one platform-neutral
JVM archive plus `SHA256SUMS`.
