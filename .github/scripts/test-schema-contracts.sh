#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd -- "$repo_root"

python3 - <<'PY'
import json
import re
from pathlib import Path
from urllib.parse import unquote

repo_root = Path.cwd().resolve()
schema_root = repo_root / "schemas"
fixture_root = repo_root / ".github/fixtures/source-projection/source"
draft_2020_12 = "https://json-schema.org/draft/2020-12/schema"
draft_07 = "http://json-schema.org/draft-07/schema#"

expected_schema_paths = {
    "adapters/codex/hooks.schema.json",
    "adapters/codex/marketplace-lock.schema.json",
    "adapters/codex/plugin.schema.json",
    "adapters/github/hooks.schema.json",
    "adapters/github/plugin.schema.json",
    "core/agent.schema.json",
    "core/hook.schema.json",
    "core/instruction.schema.json",
    "core/lock.schema.json",
    "core/marketplace.schema.json",
    "core/plugin.schema.json",
    "core/reference-definitions.schema.json",
    "core/skill.schema.json",
    "marketplace/adaptable.schema.json",
    "marketplace/codex.schema.json",
    "marketplace/github.schema.json",
}


def fail(message: str) -> None:
    raise SystemExit(f"error: {message}")


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"{path.relative_to(repo_root)} is not valid JSON: {error}")


actual_schema_paths = {
    str(path.relative_to(schema_root))
    for path in schema_root.rglob("*.schema.json")
}
if actual_schema_paths != expected_schema_paths:
    missing = sorted(expected_schema_paths - actual_schema_paths)
    unexpected = sorted(actual_schema_paths - expected_schema_paths)
    fail(f"schema suite mismatch; missing={missing}, unexpected={unexpected}")

documents = {}
for relative in sorted(expected_schema_paths):
    path = (schema_root / relative).resolve()
    document = read_json(path)
    if not isinstance(document, dict):
        fail(f"schemas/{relative} must contain a JSON object")
    expected_draft = draft_07 if relative == "adapters/codex/hooks.schema.json" else draft_2020_12
    if document.get("$schema") != expected_draft:
        fail(f"schemas/{relative} must use its authoritative JSON Schema draft")
    if not isinstance(document.get("$id"), str) or not document["$id"]:
        fail(f"schemas/{relative} must define $id")
    if not isinstance(document.get("title"), str) or not document["title"]:
        fail(f"schemas/{relative} must define title")
    documents[path] = document

fixture_path = schema_root / "marketplace/fixtures/codex.marketplace.json"
read_json(fixture_path)


def pointer_value(document, fragment: str, source: str):
    if not fragment:
        return document
    if not fragment.startswith("/"):
        fail(f"unsupported JSON Pointer in {source}: #{fragment}")
    value = document
    for encoded in fragment[1:].split("/"):
        key = unquote(encoded).replace("~1", "/").replace("~0", "~")
        if not isinstance(value, dict) or key not in value:
            fail(f"unresolved JSON Pointer in {source}: #{fragment}")
        value = value[key]
    return value


def resolve_reference(base_path: Path, reference: str):
    if re.match(r"^[a-z][a-z0-9+.-]*://", reference, re.IGNORECASE):
        return None
    relative, separator, fragment = reference.partition("#")
    target_path = (base_path.parent / relative).resolve() if relative else base_path
    if target_path not in documents:
        fail(f"unresolved schema file reference from {base_path.relative_to(repo_root)}: {reference}")
    return documents[target_path], target_path, pointer_value(
        documents[target_path],
        fragment if separator else "",
        str(base_path.relative_to(repo_root)),
    )


def visit_references(value, base_path: Path):
    if isinstance(value, list):
        for item in value:
            visit_references(item, base_path)
        return
    if not isinstance(value, dict):
        return
    reference = value.get("$ref")
    if isinstance(reference, str):
        resolve_reference(base_path, reference)
    for item in value.values():
        visit_references(item, base_path)


for schema_path, schema in documents.items():
    visit_references(schema, schema_path)

adaptable_contract = documents[(schema_root / "marketplace/adaptable.schema.json").resolve()]
reference_contract = documents[(schema_root / "core/reference-definitions.schema.json").resolve()]


def adapter_shape_signature(contract):
    return (
        contract.get("discriminator"),
        [
            (
                tuple(branch.get("properties", {}).get("type", {}).get("enum", [])),
                tuple(branch.get("required", [])),
                tuple(branch.get("properties", {}).keys()),
                branch.get("additionalProperties"),
            )
            for branch in contract.get("oneOf", [])
        ],
    )


if adapter_shape_signature(
    adaptable_contract["properties"].get("adapters", {})
) != adapter_shape_signature(
    reference_contract["$defs"]["Marketplace"]["properties"].get("adapters", {})
):
    fail("adaptable marketplace adapter contract must match reference-definitions Marketplace")


def dereference(node, base_path: Path):
    while isinstance(node, dict) and isinstance(node.get("$ref"), str):
        resolved = resolve_reference(base_path, node["$ref"])
        if resolved is None:
            return node, base_path
        _, base_path, node = resolved
    return node, base_path


def branch_discriminator(node, base_path: Path):
    node, base_path = dereference(node, base_path)
    if not isinstance(node, dict):
        return set()
    type_schema = node.get("properties", {}).get("type")
    type_schema, _ = dereference(type_schema, base_path)
    if not isinstance(type_schema, dict):
        return set()
    values = type_schema.get("enum")
    return set(values) if isinstance(values, list) else set()


def check_constructed(instance, node, base_path: Path, location: str):
    node, base_path = dereference(node, base_path)
    if not isinstance(node, dict):
        return

    branches = node.get("oneOf")
    if isinstance(branches, list):
        discriminator = instance.get("type") if isinstance(instance, dict) else None
        candidates = [branch for branch in branches if discriminator in branch_discriminator(branch, base_path)]
        if len(candidates) != 1:
            fail(f"{location} does not select exactly one typed schema branch for {discriminator!r}")
        check_constructed(instance, candidates[0], base_path, location)
        return

    expected_type = node.get("type")
    if expected_type == "object":
        if not isinstance(instance, dict):
            fail(f"{location} must be an object")
        properties = node.get("properties", {})
        required = node.get("required", [])
        missing = sorted(key for key in required if key not in instance)
        if missing:
            fail(f"{location} is missing required properties: {', '.join(missing)}")
        if node.get("additionalProperties") is False:
            unknown = sorted(set(instance) - set(properties))
            if unknown:
                fail(f"{location} contains unknown properties: {', '.join(unknown)}")
        for key, value in instance.items():
            if key in properties:
                check_constructed(value, properties[key], base_path, f"{location}.{key}")
        return

    if expected_type == "array":
        if not isinstance(instance, list):
            fail(f"{location} must be an array")
        minimum = node.get("minItems")
        if isinstance(minimum, int) and len(instance) < minimum:
            fail(f"{location} must contain at least {minimum} items")
        item_schema = node.get("items")
        if item_schema is not None:
            for index, item in enumerate(instance):
                check_constructed(item, item_schema, base_path, f"{location}[{index}]")
        return

    if expected_type == "string":
        if not isinstance(instance, str):
            fail(f"{location} must be a string")
        if "enum" in node and instance not in node["enum"]:
            fail(f"{location} must be one of {node['enum']}")
        if isinstance(node.get("minLength"), int) and len(instance) < node["minLength"]:
            fail(f"{location} is shorter than minLength")
        if isinstance(node.get("pattern"), str) and re.search(node["pattern"], instance) is None:
            fail(f"{location} does not match its schema pattern")
        return

    if expected_type == "integer":
        if not isinstance(instance, int) or isinstance(instance, bool):
            fail(f"{location} must be an integer")
        if "enum" in node and instance not in node["enum"]:
            fail(f"{location} must be one of {node['enum']}")


adaptable_path = (schema_root / "marketplace/adaptable.schema.json").resolve()
plugin_path = (schema_root / "core/plugin.schema.json").resolve()
hook_path = (schema_root / "core/hook.schema.json").resolve()
marketplace_fixture_path = fixture_root / "adaptable.marketplace.json"
marketplace = read_json(marketplace_fixture_path)
check_constructed(marketplace, documents[adaptable_path], adaptable_path, "adaptable.marketplace.json")

validated_documents = [marketplace_fixture_path]
for entry in marketplace["plugins"]:
    source = entry["plugin"]["source"]
    if source["type"] != "LOCAL_SOURCE":
        continue
    manifest_path = (fixture_root / source["path"] / "plugin.json").resolve()
    if fixture_root.resolve() not in (manifest_path, *manifest_path.parents):
        fail(f"plugin source escapes fixture root: {source['path']}")
    manifest = read_json(manifest_path)
    check_constructed(manifest, documents[plugin_path], plugin_path, "plugin.json")
    if manifest.get("name") != entry.get("name") or entry["plugin"].get("name") != entry.get("name"):
        fail(f"plugin identity disagrees for marketplace entry {entry.get('name')}")
    validated_documents.append(manifest_path)
    for hook in manifest.get("hooks", []):
        metadata_path = (fixture_root / hook["path"]).resolve()
        if fixture_root.resolve() not in (metadata_path, *metadata_path.parents):
            fail(f"hook source escapes fixture root: {hook['path']}")
        metadata = read_json(metadata_path)
        check_constructed(metadata, documents[hook_path], hook_path, metadata_path.name)
        if metadata.get("name") != hook.get("name"):
            fail(f"hook identity disagrees for {hook.get('name')}")
        validated_documents.append(metadata_path)

print(
    f"OK detailed JSON Schema suite ({len(expected_schema_paths)} schemas, "
    f"{len(validated_documents)} source documents)"
)
PY
