#!/usr/bin/env node

import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const require = createRequire(import.meta.url);
const { describeContractField } = require("../../docs/schema-presentation.js");
const repositoryRoot = resolve(import.meta.dirname, "../..");
const plugin = JSON.parse(
  readFileSync(resolve(repositoryRoot, "schemas/core/plugin.schema.json"), "utf8"),
);
const references = JSON.parse(
  readFileSync(resolve(repositoryRoot, "schemas/core/reference-definitions.schema.json"), "utf8"),
);

const name = describeContractField(references.$defs.Name, { required: true });
assert.deepEqual(name, {
  typeLabel: "string",
  typeFamily: "string",
  statuses: ["required"],
  example: {
    kind: "inline",
    label: "Example",
    value: "code-review",
  },
});

const version = describeContractField({
  type: "string",
  description: "A semantic version selector.",
  examples: ["^1.0.0"],
});
assert.deepEqual(version, {
  typeLabel: "string",
  typeFamily: "string",
  statuses: [],
  example: {
    kind: "inline",
    label: "Example",
    value: "^1.0.0",
  },
});

const payload = describeContractField(plugin.properties.interface, { deprecated: true });
assert.deepEqual(payload, {
  typeLabel: "object",
  typeFamily: "object",
  statuses: ["deprecated"],
  example: {
    kind: "block",
    label: "Example payload",
    value: {
      websiteURL: "https://github.com/acme/tools",
      privacyPolicyURL: "https://github.com/acme/tools",
      termsOfServiceURL: "https://github.com/acme/tools",
    },
  },
});

const source = describeContractField({ oneOf: [{ type: "string" }, { type: "object" }] });
assert.equal(source.typeLabel, "oneOf");
assert.equal(source.typeFamily, "union");

const nullable = describeContractField({ type: ["string", "null"] });
assert.equal(nullable.typeLabel, "string | null");
assert.equal(nullable.typeFamily, "union");

console.log("OK contract fields have typed rendering metadata");
