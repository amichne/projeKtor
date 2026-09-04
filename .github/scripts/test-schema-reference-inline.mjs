#!/usr/bin/env node

import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const require = createRequire(import.meta.url);
const { createResolver } = require("../../docs/schema-reference.js");
const repositoryRoot = resolve(import.meta.dirname, "../..");
const schemaPaths = [
  "schemas/adapters/codex/hooks.schema.json",
  "schemas/adapters/codex/marketplace-lock.schema.json",
  "schemas/adapters/codex/plugin.schema.json",
  "schemas/adapters/github/hooks.schema.json",
  "schemas/adapters/github/plugin.schema.json",
  "schemas/core/agent.schema.json",
  "schemas/core/hook.schema.json",
  "schemas/core/instruction.schema.json",
  "schemas/core/lock.schema.json",
  "schemas/core/marketplace.schema.json",
  "schemas/core/plugin.schema.json",
  "schemas/core/reference-definitions.schema.json",
  "schemas/core/skill.schema.json",
  "schemas/marketplace/adaptable.schema.json",
  "schemas/marketplace/codex.schema.json",
  "schemas/marketplace/github.schema.json",
];
const documents = new Map(
  schemaPaths.map((path) => [
    path,
    JSON.parse(readFileSync(resolve(repositoryRoot, path), "utf8")),
  ]),
);
const resolver = createResolver(documents);

const pluginPath = "schemas/core/plugin.schema.json";
const plugin = documents.get(pluginPath);
const item = plugin.properties.extends.items;
const pluginReference = resolver.inline(item, pluginPath);

assert.equal(pluginReference.kind, "resolved");
assert.equal(pluginReference.documentPath, "schemas/core/reference-definitions.schema.json");
assert.equal(pluginReference.pointer, "#/$defs/PluginReference");
assert.equal(pluginReference.schema.type, "object");
assert.equal(pluginReference.schema.description, "Reference to a plugin without embedding its primitives.");
assert.deepEqual(Object.keys(pluginReference.schema.properties), [
  "type",
  "name",
  "source",
  "version",
  "integrity",
]);

const sourceReference = resolver.inline(
  pluginReference.schema.properties.source,
  pluginReference.documentPath,
  pluginReference.trail,
);
assert.equal(sourceReference.kind, "resolved");
assert.equal(sourceReference.documentPath, "schemas/core/reference-definitions.schema.json");
assert.equal(sourceReference.pointer, "#/$defs/SourceReference");
assert.equal(sourceReference.schema.description, "Where a plugin or primitive should be resolved from before projection into a provider-specific harness.");
assert.equal(sourceReference.schema.oneOf.length, 5);

const cycle = resolver.inline(
  pluginReference.schema,
  pluginReference.documentPath,
  pluginReference.trail,
);
assert.equal(cycle.kind, "inline");

const agentPath = "schemas/core/agent.schema.json";
const agent = documents.get(agentPath);
const primitiveDependency = resolver.inline(agent.properties.dependsOn.items, agentPath);
assert.equal(primitiveDependency.kind, "resolved");
const skillDependency = resolver.inline(
  primitiveDependency.schema.oneOf[0],
  primitiveDependency.documentPath,
  primitiveDependency.trail,
);
assert.equal(skillDependency.kind, "resolved");
const recursiveDependency = resolver.inline(
  skillDependency.schema.properties.dependsOn.items,
  skillDependency.documentPath,
  skillDependency.trail,
);
assert.equal(recursiveDependency.kind, "cycle");
assert.equal(recursiveDependency.documentPath, "schemas/core/reference-definitions.schema.json");
assert.equal(recursiveDependency.pointer, "#/$defs/PrimitiveDependency");

console.log("OK nested schema references resolve to inline shapes");
