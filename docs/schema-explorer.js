(() => {
  "use strict";

  const elements = {
    catalog: document.querySelector("#schema-catalog"),
    content: document.querySelector("#schema-content"),
    count: document.querySelector("#schema-count"),
    description: document.querySelector("#schema-description"),
    empty: document.querySelector("#schema-empty"),
    expand: document.querySelector("#expand-schema"),
    collapse: document.querySelector("#collapse-schema"),
    filter: document.querySelector("#schema-filter"),
    group: document.querySelector("#schema-group"),
    meta: document.querySelector("#schema-meta"),
    raw: document.querySelector("#raw-schema-link"),
    title: document.querySelector("#schema-title"),
  };

  const state = {
    manifest: null,
    schemaPath: "",
    schema: null,
  };

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  async function fetchJson(path, label) {
    const response = await fetch(path, { headers: { Accept: "application/json" } });
    if (!response.ok) {
      throw new Error(`${label} returned HTTP ${response.status}`);
    }
    try {
      return await response.json();
    } catch (error) {
      throw new Error(`${label} is not valid JSON`, { cause: error });
    }
  }

  function explorerHref(schemaPath, pointer = "") {
    const url = new URL(window.location.href);
    url.search = "";
    url.hash = "";
    url.searchParams.set("schema", schemaPath);
    if (pointer) url.searchParams.set("pointer", pointer);
    return `${url.pathname}${url.search}`;
  }

  function displayTitle(title) {
    return /^[A-Z0-9_]+$/.test(title) ? title.replaceAll("_", " ") : title;
  }

  function buildCatalog(entries, selectedPath) {
    elements.catalog.replaceChildren();
    const grouped = new Map();
    for (const entry of entries) {
      if (!grouped.has(entry.group)) grouped.set(entry.group, []);
      grouped.get(entry.group).push(entry);
    }

    for (const [groupName, schemas] of grouped) {
      const section = element("section", "catalog-group");
      section.append(element("h2", "", groupName));
      for (const schema of schemas) {
        const link = element("a", "", displayTitle(schema.title));
        link.href = explorerHref(schema.path);
        link.title = schema.description;
        if (schema.path === selectedPath) link.setAttribute("aria-current", "page");
        section.append(link);
      }
      elements.catalog.append(section);
    }
  }

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

  function appendBadge(parent, text, className = "type-badge") {
    parent.append(element("span", className, text));
  }

  function appendConstraints(parent, schema) {
    const constraints = [];
    const scalarKeys = [
      ["format", "format"],
      ["pattern", "pattern"],
      ["minLength", "min length"],
      ["maxLength", "max length"],
      ["minimum", "minimum"],
      ["maximum", "maximum"],
      ["exclusiveMinimum", "exclusive min"],
      ["exclusiveMaximum", "exclusive max"],
      ["multipleOf", "multiple of"],
      ["minItems", "min items"],
      ["maxItems", "max items"],
      ["minProperties", "min properties"],
      ["maxProperties", "max properties"],
    ];

    for (const [key, label] of scalarKeys) {
      if (Object.hasOwn(schema, key)) constraints.push(`${label} · ${schema[key]}`);
    }
    if (schema.uniqueItems === true) constraints.push("unique items");
    if (schema.additionalProperties === false) constraints.push("closed object");
    if (schema.enum) constraints.push(`enum · ${schema.enum.map(String).join(" | ")}`);
    if (Object.hasOwn(schema, "const")) constraints.push(`constant · ${String(schema.const)}`);

    if (!constraints.length) return;
    const list = element("div", "constraint-list");
    for (const constraint of constraints) appendBadge(list, constraint, "");
    parent.append(list);
  }

  function appendExample(parent, schema) {
    const examples = schema.examples || (Object.hasOwn(schema, "example") ? [schema.example] : []);
    if (!examples.length) return;
    const figure = element("figure", "node-example");
    figure.append(element("figcaption", "", examples.length > 1 ? `Example 1 of ${examples.length}` : "Example"));
    const pre = element("pre");
    pre.append(element("code", "", JSON.stringify(examples[0], null, 2)));
    figure.append(pre);
    parent.append(figure);
  }

  function normalizeReferencePath(reference) {
    const [filePart, fragment = ""] = reference.split("#", 2);
    if (/^[a-z][a-z0-9+.-]*:/i.test(filePart)) {
      return { external: true, href: reference, label: reference };
    }

    const stack = state.schemaPath.split("/");
    stack.pop();
    if (filePart) {
      for (const part of filePart.split("/")) {
        if (!part || part === ".") continue;
        if (part === "..") {
          if (!stack.length) return null;
          stack.pop();
        } else {
          stack.push(part);
        }
      }
    } else {
      stack.splice(0, stack.length, ...state.schemaPath.split("/"));
    }

    const targetPath = stack.join("/");
    const targetExists = state.manifest.schemas.some((entry) => entry.path === targetPath);
    if (!targetExists) return null;
    const pointer = fragment ? `#${fragment}` : "";
    return {
      external: false,
      href: explorerHref(targetPath, pointer),
      label: `${targetPath}${pointer}`,
    };
  }

  function appendReference(parent, reference) {
    const resolved = normalizeReferencePath(reference);
    if (!resolved) {
      const unresolved = element("span", "ref-link", `Unresolved reference · ${reference}`);
      unresolved.setAttribute("role", "status");
      parent.append(unresolved);
      return;
    }
    const link = element("a", "ref-link", `Follow $ref · ${resolved.label}`);
    link.href = resolved.href;
    if (resolved.external) {
      link.target = "_blank";
      link.rel = "noreferrer";
    }
    parent.append(link);
  }

  function appendChildGroup(parent, title, entries, pointer, depth) {
    if (!entries.length) return;
    parent.append(element("h3", "child-heading", title));
    const children = element("div", "child-nodes");
    entries.forEach(([name, schema, childPointer, required]) => {
      children.append(renderSchemaNode(name, schema, childPointer || pointer, required, depth + 1));
    });
    parent.append(children);
  }

  function renderSchemaNode(name, schema, pointer, required = false, depth = 0) {
    const details = element("details", "schema-node");
    details.dataset.pointer = pointer;
    if (depth < 1) details.open = true;

    const summary = element("summary");
    summary.append(element("span", "node-name", name));
    const description = typeof schema === "object" && schema !== null
      ? schema.description || "No description provided."
      : schema ? "Any JSON value is accepted." : "No JSON value is accepted.";
    summary.append(element("span", "node-description", description));
    summary.append(element("span", "type-badge", schemaType(schema)));
    details.append(summary);

    const body = element("div", "node-body");
    if (typeof schema !== "object" || schema === null) {
      details.append(body);
      return details;
    }

    const badges = element("div", "node-badges");
    appendBadge(badges, schemaType(schema));
    if (required) appendBadge(badges, "required", "required-badge");
    if (schema.deprecated) appendBadge(badges, "deprecated", "required-badge");
    body.append(badges);
    appendConstraints(body, schema);
    if (schema.$ref) appendReference(body, schema.$ref);
    appendExample(body, schema);

    const requiredProperties = new Set(schema.required || []);
    const properties = Object.entries(schema.properties || {}).map(([propertyName, child]) => [
      propertyName,
      child,
      `${pointer}/properties/${escapePointer(propertyName)}`,
      requiredProperties.has(propertyName),
    ]);
    appendChildGroup(body, "Properties", properties, pointer, depth);

    const definitions = Object.entries(schema.$defs || {}).map(([definitionName, child]) => [
      definitionName,
      child,
      `${pointer}/$defs/${escapePointer(definitionName)}`,
      false,
    ]);
    appendChildGroup(body, "Definitions", definitions, pointer, depth);

    const patterns = Object.entries(schema.patternProperties || {}).map(([pattern, child]) => [
      pattern,
      child,
      `${pointer}/patternProperties/${escapePointer(pattern)}`,
      false,
    ]);
    appendChildGroup(body, "Pattern properties", patterns, pointer, depth);

    for (const keyword of ["oneOf", "anyOf", "allOf"]) {
      const variants = (schema[keyword] || []).map((child, index) => [
        `${keyword} option ${index + 1}`,
        child,
        `${pointer}/${keyword}/${index}`,
        false,
      ]);
      appendChildGroup(body, keyword, variants, pointer, depth);
    }

    if (schema.items && !Array.isArray(schema.items)) {
      appendChildGroup(body, "Array items", [["item", schema.items, `${pointer}/items`, false]], pointer, depth);
    }
    if (typeof schema.additionalProperties === "object" && schema.additionalProperties !== null) {
      appendChildGroup(
        body,
        "Additional property values",
        [["additional property", schema.additionalProperties, `${pointer}/additionalProperties`, false]],
        pointer,
        depth,
      );
    }

    details.append(body);
    return details;
  }

  function escapePointer(value) {
    return value.replaceAll("~", "~0").replaceAll("/", "~1");
  }

  function renderSummary(entry, schema) {
    elements.group.textContent = entry.group;
    elements.title.textContent = displayTitle(schema.title || entry.title);
    elements.description.textContent = schema.description || "No schema description provided.";
    elements.raw.href = entry.path;
    elements.meta.replaceChildren();

    const draft = (schema.$schema || "draft unspecified").replace("https://json-schema.org/", "").replace("/schema", "");
    const values = [draft, schema.$id || "id unspecified", schemaType(schema)];
    if (schema.additionalProperties === false) values.push("closed root");
    for (const value of values) appendBadge(elements.meta, value, "");
  }

  function applyFilter() {
    const query = elements.filter.value.trim().toLocaleLowerCase();
    const nodes = [...elements.content.querySelectorAll(".schema-node")];
    let visible = 0;
    for (const node of nodes) {
      const matches = !query || node.textContent.toLocaleLowerCase().includes(query);
      node.hidden = !matches;
      if (matches) {
        visible += 1;
        if (query) node.open = true;
      }
    }
    elements.empty.hidden = visible !== 0;
    elements.count.textContent = `${visible} of ${nodes.length} fields visible`;
  }

  function revealPointer(pointer) {
    if (!pointer) return;
    const target = [...elements.content.querySelectorAll(".schema-node")]
      .find((node) => node.dataset.pointer === pointer);
    if (!target) return;
    let parent = target;
    while (parent) {
      if (parent instanceof HTMLDetailsElement) parent.open = true;
      parent = parent.parentElement;
    }
    target.scrollIntoView({ block: "center" });
    target.querySelector("summary")?.focus({ preventScroll: true });
  }

  function showError(message) {
    elements.content.setAttribute("aria-busy", "false");
    elements.content.replaceChildren();
    const error = element("div", "schema-error");
    error.setAttribute("role", "alert");
    error.append(element("strong", "", "The schema could not be rendered."));
    error.append(document.createTextNode(message));
    elements.content.append(error);
    elements.count.textContent = "Contract unavailable";
  }

  async function start() {
    try {
      const manifest = await fetchJson("schema-index.json", "Schema index");
      if (!Array.isArray(manifest.schemas) || !manifest.schemas.length) {
        throw new Error("Schema index contains no contracts");
      }
      state.manifest = manifest;

      const parameters = new URLSearchParams(window.location.search);
      const requestedPath = parameters.get("schema");
      const selectedPath = requestedPath || manifest.default;
      const entry = manifest.schemas.find((candidate) => candidate.path === selectedPath);
      if (!entry) {
        throw new Error(`Unknown schema path: ${selectedPath}`);
      }

      state.schemaPath = entry.path;
      buildCatalog(manifest.schemas, entry.path);
      const schema = await fetchJson(entry.path, entry.title);
      state.schema = schema;
      renderSummary(entry, schema);
      elements.content.replaceChildren(renderSchemaNode("Root contract", schema, "#", true));
      elements.content.setAttribute("aria-busy", "false");
      applyFilter();
      revealPointer(parameters.get("pointer"));
    } catch (error) {
      showError(error instanceof Error ? error.message : "Unknown rendering failure");
    }
  }

  elements.filter.addEventListener("input", applyFilter);
  elements.expand.addEventListener("click", () => {
    for (const node of elements.content.querySelectorAll(".schema-node:not([hidden])")) node.open = true;
  });
  elements.collapse.addEventListener("click", () => {
    for (const node of elements.content.querySelectorAll(".schema-node")) node.open = false;
  });

  start();
})();
