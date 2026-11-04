const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const SwaggerParser = require("@apidevtools/swagger-parser");
const Ajv2020 = require("ajv/dist/2020");
const addFormats = require("ajv-formats");

const root = path.resolve(__dirname, "../../..");
const examples = JSON.parse(
  fs.readFileSync(path.join(root, "docs/api-examples.json")),
);

async function loadContract(file = path.join(root, "docs/openapi.json")) {
  // Only local references are allowed; validation never fetches remote schemas.
  const api = await SwaggerParser.validate(file, {
    resolve: { external: false },
  });
  assert.equal(api.openapi, "3.1.0");
  const ajv = new Ajv2020({ allErrors: true, strict: false });
  addFormats(ajv);
  const validators = new WeakMap();
  function validate(schema, value) {
    if (!validators.has(schema)) validators.set(schema, ajv.compile(schema));
    const check = validators.get(schema);
    assert.ok(check(value), ajv.errorsText(check.errors));
  }
  // Compile every schema, including request/header schemas without fixture coverage.
  function compile(node) {
    if (!node || typeof node !== "object") return;
    for (const [key, value] of Object.entries(node)) {
      if (key === "schema") ajv.compile(value);
      else if (key === "schemas")
        Object.values(value).forEach((schema) => ajv.compile(schema));
      else compile(value);
    }
  }
  compile(api);
  // Parser schema validation does not verify references to security scheme names.
  for (const item of Object.values(api.paths)) {
    for (const op of Object.values(item)) {
      for (const requirement of op.security || []) {
        for (const name of Object.keys(requirement))
          assert.ok(
            api.components.securitySchemes[name],
            `Unknown security scheme ${name}`,
          );
      }
    }
  }
  return { api, validate };
}

function operation(api, method, target) {
  const pathname = target
    .split("?")[0]
    .replace(/\/points\/(?:user:|osm:).+$/, "/points/{id}");
  const op = api.paths[pathname]?.[method.toLowerCase()];
  assert.ok(op, `Undocumented ${method} ${pathname}`);
  return op;
}

function response(contract, op, result, method) {
  const spec = op.responses[String(result.status)];
  assert.ok(spec, `Undocumented response status ${result.status}`);
  for (const [name, header] of Object.entries(spec.headers || {})) {
    const value = result.headers.get(name);
    if (name.toLowerCase() === "x-request-id" || header.required)
      assert.ok(value, `Missing ${name}`);
    if (value !== null && header.schema)
      contract.validate(header.schema, value);
  }
  if (method === "HEAD" || !spec.content) {
    assert.equal(result.text, "", "Response must have no body");
    return;
  }
  const media = result.headers.get("content-type")?.split(";")[0];
  const content = spec.content[media];
  assert.ok(content, `Undocumented content-type ${media}`);
  const body =
    media === "application/json" || media?.endsWith("+json")
      ? JSON.parse(result.text)
      : result.text;
  contract.validate(content.schema, body);
  if (result.status >= 400 && typeof body?.code === "string")
    assert.equal(result.headers.get("cache-control"), "no-store");
  return body;
}

async function check() {
  const contract = await loadContract();
  // Match advertised security to the HTTP boundary exercised below, including
  // the public SELECT-only distance exception and CSRF-protected login/logout.
  for (const example of examples.cases) {
    const op = operation(
      contract.api,
      example.method,
      example.path.replace(
        "${id}",
        "user:00000000-0000-0000-0000-000000000000",
      ),
    );
    const write =
      example.method === "DELETE" || example.path === "/api/v1/osm/points/add";
    assert.deepEqual(
      op.security,
      write ? [{ sessionCookie: [], csrfToken: [] }] : [],
    );
  }
  for (const target of ["/login", "/logout"]) {
    assert.deepEqual(operation(contract.api, "POST", target).security, [
      { csrfToken: [] },
    ]);
  }
  for (const example of examples.cases) {
    const op = operation(
      contract.api,
      example.method,
      example.path.replace(
        "${id}",
        "user:00000000-0000-0000-0000-000000000000",
      ),
    );
    assert.ok(
      op.responses[String(example.status)],
      `Example ${example.name}: missing response`,
    );
    if (example.body !== undefined) {
      const schema = op.requestBody.content["application/json"].schema;
      if (example.invalidRequest)
        assert.throws(() => contract.validate(schema, example.body));
      else contract.validate(schema, example.body);
    }
  }
  // Prove structural validation rejects a real OpenAPI violation.
  const broken = JSON.parse(
    fs.readFileSync(path.join(root, "docs/openapi.json")),
  );
  broken.paths["/api/v1/osm/points"].get.responses["200"].description = 42;
  await assert.rejects(() =>
    SwaggerParser.validate(broken, { resolve: { external: false } }),
  );
  console.log(
    `OpenAPI 3.1 structure, schemas and ${examples.cases.length} executable requests validated`,
  );
}

module.exports = { loadContract, operation, response, examples, check };
if (require.main === module)
  check().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
