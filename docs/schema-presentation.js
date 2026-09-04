(function exposeSchemaPresentation(root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  root.ProjektorSchemaPresentation = api;
}(typeof globalThis === "object" ? globalThis : this, () => {
  "use strict";

  const TYPE_FAMILIES = Object.freeze([
    "string",
    "object",
    "array",
    "number",
    "boolean",
    "null",
    "union",
    "enum",
    "reference",
    "any",
    "never",
    "schema",
  ]);

  function schemaType(schema) {
    if (typeof schema === "boolean") return schema ? "any" : "never";
    if (Array.isArray(schema.type)) return schema.type.join(" | ");
    if (schema.type) return schema.type;
    if (schema.$ref) return "reference";
    if (schema.oneOf) return "oneOf";
    if (schema.anyOf) return "anyOf";
    if (schema.allOf) return "allOf";
    if (schema.enum) return "enum";
    return "schema";
  }

  function typeFamily(schema) {
    if (typeof schema === "boolean") return schema ? "any" : "never";
    if (Array.isArray(schema.type)) return "union";
    if (["string", "object", "array", "boolean", "null"].includes(schema.type)) return schema.type;
    if (["number", "integer"].includes(schema.type)) return "number";
    if (schema.oneOf || schema.anyOf || schema.allOf) return "union";
    if (schema.enum || Object.hasOwn(schema, "const")) return "enum";
    if (schema.$ref) return "reference";
    return "schema";
  }

  function describeExample(schema) {
    const examples = schema.examples || (Object.hasOwn(schema, "example") ? [schema.example] : []);
    if (!examples.length) return null;
    const value = examples[0];
    const kind = value === null || ["string", "number", "boolean"].includes(typeof value)
      ? "inline"
      : "block";
    return Object.freeze({
      kind,
      label: kind === "inline" ? "Example" : "Example payload",
      value,
    });
  }

  function describeContractField(schema, options = {}) {
    const normalized = typeof schema === "object" && schema !== null ? schema : schema === true ? {} : { not: {} };
    const statuses = [];
    if (options.required === true) statuses.push("required");
    if (normalized.deprecated === true || options.deprecated === true) statuses.push("deprecated");
    const family = typeFamily(schema);
    if (!TYPE_FAMILIES.includes(family)) throw new Error(`Unsupported contract field family: ${family}`);
    return Object.freeze({
      typeLabel: schemaType(schema),
      typeFamily: family,
      statuses: Object.freeze(statuses),
      example: describeExample(normalized),
    });
  }

  return Object.freeze({ describeContractField, schemaType });
}));
