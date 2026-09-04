(() => {
  "use strict";

  const presentation = globalThis.ProjektorSchemaPresentation;

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
    outline: document.querySelector("#schema-outline"),
    raw: document.querySelector("#raw-schema-link"),
    title: document.querySelector("#schema-title"),
  };

  const state = {
    documents: new Map(),
    manifest: null,
    pointerIds: new Map(),
    resolver: null,
    schemaPath: "",
    schema: null,
  };
  const nodeHydrators = new WeakMap();

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

  function appendBadge(parent, text, className = "type-badge") {
    parent.append(element("span", className, text));
  }

  function appendTypeBadge(parent, descriptor) {
    appendBadge(parent, descriptor.typeLabel, `type-badge type-${descriptor.typeFamily}`);
  }

  function appendStatusBadges(parent, descriptor) {
    for (const status of descriptor.statuses) {
      appendBadge(parent, status, `status-badge status-${status}`);
    }
  }

  function contractConstraints(schema) {
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

    return constraints;
  }

  function appendConstraints(parent, schema) {
    const constraints = contractConstraints(schema);
    if (!constraints.length) return;
    const list = element("div", "constraint-list");
    for (const constraint of constraints) appendBadge(list, constraint, "");
    parent.append(list);
  }

  function appendExample(parent, example) {
    if (!example) return;
    if (example.kind === "inline") {
      const row = element("p", "node-example-inline");
      row.append(element("span", "", example.label));
      row.append(element("code", "", JSON.stringify(example.value)));
      parent.append(row);
      return;
    }
    const disclosure = element("details", "node-example node-example-block");
    disclosure.append(element("summary", "", example.label));
    const pre = element("pre");
    const code = element("code", "json-example");
    appendHighlightedJson(code, example.value);
    pre.append(code);
    disclosure.append(pre);
    parent.append(disclosure);
  }

  function appendHighlightedJson(parent, value) {
    const source = JSON.stringify(value, null, 2);
    const tokens = /("(?:\\.|[^"\\])*")(?=\s*:)|("(?:\\.|[^"\\])*")|-?\d+(?:\.\d+)?(?:e[+-]?\d+)?|\b(?:true|false)\b|\bnull\b/gi;
    let cursor = 0;
    for (const match of source.matchAll(tokens)) {
      parent.append(document.createTextNode(source.slice(cursor, match.index)));
      const token = match[0];
      const tokenClass = match[1]
        ? "json-key"
        : match[2]
          ? "json-string"
          : token === "null"
            ? "json-null"
            : token === "true" || token === "false"
              ? "json-boolean"
              : "json-number";
      parent.append(element("span", tokenClass, token));
      cursor = match.index + token.length;
    }
    parent.append(document.createTextNode(source.slice(cursor)));
  }

  function hasNestedShapes(schema) {
    return Boolean(
      Object.keys(schema.properties || {}).length
      || Object.keys(schema.$defs || {}).length
      || Object.keys(schema.definitions || {}).length
      || Object.keys(schema.patternProperties || {}).length
      || (schema.oneOf || []).length
      || (schema.anyOf || []).length
      || (schema.allOf || []).length
      || (schema.items && !Array.isArray(schema.items))
      || (typeof schema.additionalProperties === "object" && schema.additionalProperties !== null)
    );
  }

  function pointerId(pointer) {
    if (!state.pointerIds.has(pointer)) {
      state.pointerIds.set(pointer, `schema-field-${state.pointerIds.size + 1}`);
    }
    return state.pointerIds.get(pointer);
  }

  function appendReferenceFailure(parent, outcome) {
    const failure = element("p", "reference-failure", outcome.reason || "The referenced shape is unavailable.");
    failure.setAttribute("role", "status");
    parent.append(failure);
  }

  function appendChildGroup(parent, title, entries, pointer, depth, ownerPath, trail) {
    if (!entries.length) return;
    parent.append(element("h3", "child-heading", title));
    const children = element("div", "child-nodes");
    entries.forEach(([name, schema, childPointer, required]) => {
      children.append(renderSchemaNode(
        name,
        schema,
        childPointer || pointer,
        required,
        depth + 1,
        ownerPath,
        trail,
      ));
    });
    parent.append(children);
  }

  function renderSchemaNode(
    name,
    schema,
    pointer,
    required = false,
    depth = 0,
    ownerPath = state.schemaPath,
    trail = [],
  ) {
    const outcome = state.resolver.inline(schema, ownerPath, trail);
    const inlined = outcome.kind === "resolved" || outcome.kind === "inline";
    const visibleSchema = inlined ? outcome.schema : schema;
    const visiblePath = inlined ? outcome.documentPath : ownerPath;
    const visibleTrail = inlined ? outcome.trail : trail;
    const descriptor = presentation.describeContractField(visibleSchema, {
      required,
      deprecated: typeof schema === "object" && schema !== null && schema.deprecated === true,
    });
    const localExamples = typeof schema === "object" && schema !== null
      ? schema.examples || (Object.hasOwn(schema, "example") ? [schema.example] : [])
      : [];
    const exampleDescriptor = localExamples.length
      ? presentation.describeContractField(schema).example
      : descriptor.example;
    const expandable = !inlined && typeof schema === "object" && schema !== null && schema.$ref
      ? true
      : typeof visibleSchema === "object" && visibleSchema !== null
        && (
          contractConstraints(visibleSchema).length > 0
          || exampleDescriptor?.kind === "block"
          || hasNestedShapes(visibleSchema)
        );
    const node = element(expandable ? "details" : "section", `schema-node${expandable ? "" : " schema-node-static"}`);
    node.dataset.pointer = pointer;
    node.id = pointerId(pointer);
    node.dataset.referenceState = outcome.kind;
    if (expandable && depth < 1) node.open = true;

    const summary = element(expandable ? "summary" : "div", expandable ? "" : "node-summary");
    if (!expandable) summary.tabIndex = -1;
    const localDescription = typeof schema === "object" && schema !== null ? schema.description : "";
    const description = typeof visibleSchema === "object" && visibleSchema !== null
      ? localDescription || visibleSchema.description || (inlined ? "No description provided." : outcome.reason)
      : visibleSchema ? "Any JSON value is accepted." : "No JSON value is accepted.";
    const heading = element("span", "node-heading");
    heading.append(element("span", "node-name", name));
    appendTypeBadge(heading, descriptor);
    appendStatusBadges(heading, descriptor);
    summary.append(heading);
    summary.append(element("span", "node-description", description));
    if (exampleDescriptor?.kind === "inline") appendExample(summary, exampleDescriptor);
    if (expandable) {
      const toggle = element("span", "node-toggle");
      toggle.setAttribute("aria-hidden", "true");
      summary.append(toggle);
    }
    node.append(summary);

    if (!expandable) return node;

    let hydrated = false;
    const hydrate = () => {
      if (hydrated) return;
      hydrated = true;
      const body = element("div", "node-body");
      node.append(body);
      if (!inlined && typeof schema === "object" && schema !== null && schema.$ref) {
        appendReferenceFailure(body, outcome);
        return;
      }
      if (typeof visibleSchema !== "object" || visibleSchema === null) return;

      appendConstraints(body, visibleSchema);
      if (exampleDescriptor?.kind === "block") appendExample(body, exampleDescriptor);

      const requiredProperties = new Set(visibleSchema.required || []);
      const properties = Object.entries(visibleSchema.properties || {}).map(([propertyName, child]) => [
        propertyName,
        child,
        `${pointer}/properties/${escapePointer(propertyName)}`,
        requiredProperties.has(propertyName),
      ]);
      appendChildGroup(body, "Properties", properties, pointer, depth, visiblePath, visibleTrail);

      const definitions = [
        ...Object.entries(visibleSchema.$defs || {}).map(([definitionName, child]) => [
          definitionName,
          child,
          `${pointer}/$defs/${escapePointer(definitionName)}`,
          false,
        ]),
        ...Object.entries(visibleSchema.definitions || {}).map(([definitionName, child]) => [
          definitionName,
          child,
          `${pointer}/definitions/${escapePointer(definitionName)}`,
          false,
        ]),
      ];
      appendChildGroup(body, "Definitions", definitions, pointer, depth, visiblePath, visibleTrail);

      const patterns = Object.entries(visibleSchema.patternProperties || {}).map(([pattern, child]) => [
        pattern,
        child,
        `${pointer}/patternProperties/${escapePointer(pattern)}`,
        false,
      ]);
      appendChildGroup(body, "Pattern properties", patterns, pointer, depth, visiblePath, visibleTrail);

      for (const keyword of ["oneOf", "anyOf", "allOf"]) {
        const variants = (visibleSchema[keyword] || []).map((child, index) => [
          `${keyword} option ${index + 1}`,
          child,
          `${pointer}/${keyword}/${index}`,
          false,
        ]);
        appendChildGroup(body, keyword, variants, pointer, depth, visiblePath, visibleTrail);
      }

      if (visibleSchema.items && !Array.isArray(visibleSchema.items)) {
        appendChildGroup(
          body,
          "Array items",
          [["item", visibleSchema.items, `${pointer}/items`, false]],
          pointer,
          depth,
          visiblePath,
          visibleTrail,
        );
      }
      if (typeof visibleSchema.additionalProperties === "object" && visibleSchema.additionalProperties !== null) {
        appendChildGroup(
          body,
          "Additional property values",
          [["additional property", visibleSchema.additionalProperties, `${pointer}/additionalProperties`, false]],
          pointer,
          depth,
          visiblePath,
          visibleTrail,
        );
      }
    };
    nodeHydrators.set(node, hydrate);
    if (outcome.kind === "resolved" && !node.open) {
      node.addEventListener("toggle", () => {
        if (node.open) hydrate();
      }, { once: true });
    } else {
      hydrate();
    }
    return node;
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
    const values = [draft, schema.$id || "id unspecified", presentation.schemaType(schema)];
    if (schema.additionalProperties === false) values.push("closed root");
    for (const value of values) appendBadge(elements.meta, value, "");
  }

  function renderOutline(schema) {
    elements.outline.replaceChildren();
    const outcome = state.resolver.inline(schema, state.schemaPath);
    if (outcome.kind !== "inline" && outcome.kind !== "resolved") {
      elements.outline.append(element("span", "outline-empty", "Outline unavailable"));
      return;
    }

    const visibleSchema = outcome.schema;
    if (typeof visibleSchema !== "object" || visibleSchema === null) {
      elements.outline.append(element("span", "outline-empty", "No named fields"));
      return;
    }
    const entries = Object.keys(visibleSchema.properties || {});
    const source = entries.length ? "properties" : (visibleSchema.$defs ? "$defs" : "definitions");
    const names = entries.length ? entries : Object.keys(visibleSchema[source] || {});
    if (!names.length) {
      elements.outline.append(element("span", "outline-empty", "No named fields"));
      return;
    }

    for (const name of names) {
      const pointer = `#/${source}/${escapePointer(name)}`;
      const link = element("a", "", displayTitle(name));
      link.href = `#${pointerId(pointer)}`;
      elements.outline.append(link);
    }
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
    target.querySelector("summary, .node-summary")?.focus({ preventScroll: true });
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
      if (!window.SchemaReference || typeof window.SchemaReference.createResolver !== "function") {
        throw new Error("Schema reference resolver is unavailable");
      }
      if (!presentation || typeof presentation.describeContractField !== "function") {
        throw new Error("Schema presentation model is unavailable");
      }

      const parameters = new URLSearchParams(window.location.search);
      const requestedPath = parameters.get("schema");
      const selectedPath = requestedPath || manifest.default;
      const entry = manifest.schemas.find((candidate) => candidate.path === selectedPath);
      if (!entry) {
        throw new Error(`Unknown schema path: ${selectedPath}`);
      }

      state.schemaPath = entry.path;
      buildCatalog(manifest.schemas, entry.path);
      const documents = await Promise.all(manifest.schemas.map(async (schemaEntry) => [
        schemaEntry.path,
        await fetchJson(schemaEntry.path, schemaEntry.title),
      ]));
      state.documents = new Map(documents);
      state.resolver = window.SchemaReference.createResolver(state.documents);
      const schema = state.documents.get(entry.path);
      state.schema = schema;
      renderSummary(entry, schema);
      elements.content.replaceChildren(renderSchemaNode("Root contract", schema, "#", true));
      renderOutline(schema);
      elements.content.setAttribute("aria-busy", "false");
      applyFilter();
      revealPointer(parameters.get("pointer"));
    } catch (error) {
      showError(error instanceof Error ? error.message : "Unknown rendering failure");
    }
  }

  elements.filter.addEventListener("input", applyFilter);
  elements.expand.addEventListener("click", () => {
    let previousCount = -1;
    for (let pass = 0; pass < 32; pass += 1) {
      const nodes = [...elements.content.querySelectorAll(".schema-node:not([hidden])")];
      if (nodes.length === previousCount) break;
      previousCount = nodes.length;
      for (const node of nodes) {
        node.open = true;
        nodeHydrators.get(node)?.();
      }
    }
    applyFilter();
  });
  elements.collapse.addEventListener("click", () => {
    for (const node of elements.content.querySelectorAll(".schema-node")) node.open = false;
  });
  document.addEventListener("keydown", (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLocaleLowerCase() === "k") {
      event.preventDefault();
      elements.filter.focus();
      elements.filter.select();
    }
  });

  start();
})();
