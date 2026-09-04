((root, factory) => {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  root.SchemaReference = api;
})(typeof globalThis === "object" ? globalThis : this, () => {
  "use strict";

  function isRecord(value) {
    return typeof value === "object" && value !== null && !Array.isArray(value);
  }

  function normalizePath(ownerPath, referencePath) {
    if (!referencePath) return { kind: "local", documentPath: ownerPath };

    const segments = ownerPath.split("/");
    segments.pop();
    for (const segment of referencePath.split("/")) {
      if (!segment || segment === ".") continue;
      if (segment === "..") {
        if (!segments.length) {
          return {
            kind: "outside-suite",
            reason: `Reference escapes the published schema suite: ${referencePath}`,
          };
        }
        segments.pop();
        continue;
      }
      segments.push(segment);
    }
    return { kind: "local", documentPath: segments.join("/") };
  }

  function referenceLocation(ownerPath, reference) {
    const separator = reference.indexOf("#");
    const referencePath = separator === -1 ? reference : reference.slice(0, separator);
    const fragment = separator === -1 ? "" : reference.slice(separator + 1);
    if (/^[a-z][a-z0-9+.-]*:/i.test(referencePath) || referencePath.startsWith("//")) {
      return { kind: "external", reference };
    }

    const normalized = normalizePath(ownerPath, referencePath);
    if (normalized.kind !== "local") return normalized;
    return {
      kind: "local",
      documentPath: normalized.documentPath,
      pointer: fragment ? `#${fragment}` : "#",
    };
  }

  function pointerValue(document, pointer) {
    if (pointer === "#") return { kind: "found", value: document };

    let fragment;
    try {
      fragment = decodeURIComponent(pointer.slice(1));
    } catch {
      return { kind: "invalid-pointer", reason: `Invalid encoded JSON Pointer: ${pointer}` };
    }
    if (!fragment.startsWith("/")) {
      return { kind: "invalid-pointer", reason: `Unsupported schema anchor: ${pointer}` };
    }

    let value = document;
    for (const token of fragment.slice(1).split("/")) {
      const key = token.replaceAll("~1", "/").replaceAll("~0", "~");
      if (Array.isArray(value) && /^(0|[1-9][0-9]*)$/.test(key)) {
        const index = Number(key);
        if (index >= value.length) {
          return { kind: "invalid-pointer", reason: `JSON Pointer does not exist: ${pointer}` };
        }
        value = value[index];
        continue;
      }
      if (!isRecord(value) || !Object.hasOwn(value, key)) {
        return { kind: "invalid-pointer", reason: `JSON Pointer does not exist: ${pointer}` };
      }
      value = value[key];
    }
    return { kind: "found", value };
  }

  function createResolver(documents) {
    if (!(documents instanceof Map)) {
      throw new TypeError("Schema documents must be provided as a Map.");
    }

    function inline(schema, ownerPath, trail = []) {
      if (!isRecord(schema) || typeof schema.$ref !== "string") {
        return {
          kind: "inline",
          schema,
          documentPath: ownerPath,
          pointer: null,
          trail: [...trail],
        };
      }

      const location = referenceLocation(ownerPath, schema.$ref);
      if (location.kind === "external") {
        return {
          kind: "external",
          reference: schema.$ref,
          reason: "External schema references cannot be inlined.",
          trail: [...trail],
        };
      }
      if (location.kind === "outside-suite") {
        return { ...location, reference: schema.$ref, trail: [...trail] };
      }

      const document = documents.get(location.documentPath);
      if (document === undefined) {
        return {
          kind: "missing-document",
          reference: schema.$ref,
          documentPath: location.documentPath,
          reason: `Referenced schema is not published: ${location.documentPath}`,
          trail: [...trail],
        };
      }

      const targetKey = `${location.documentPath}${location.pointer}`;
      if (trail.includes(targetKey)) {
        return {
          kind: "cycle",
          reference: schema.$ref,
          documentPath: location.documentPath,
          pointer: location.pointer,
          reason: `Recursive schema reference: ${targetKey}`,
          trail: [...trail],
        };
      }

      const pointed = pointerValue(document, location.pointer);
      if (pointed.kind !== "found") {
        return {
          ...pointed,
          reference: schema.$ref,
          documentPath: location.documentPath,
          pointer: location.pointer,
          trail: [...trail],
        };
      }

      const nextTrail = [...trail, targetKey];
      if (isRecord(pointed.value) && typeof pointed.value.$ref === "string") {
        return inline(pointed.value, location.documentPath, nextTrail);
      }
      return {
        kind: "resolved",
        schema: pointed.value,
        documentPath: location.documentPath,
        pointer: location.pointer,
        trail: nextTrail,
      };
    }

    return {
      inline,
      location: referenceLocation,
    };
  }

  return Object.freeze({ createResolver });
});
