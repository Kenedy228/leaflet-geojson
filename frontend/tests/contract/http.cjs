const assert = require("node:assert/strict");
const {
  loadContract,
  operation,
  response,
  examples,
} = require("./openapi.cjs");

async function main() {
  assert.equal(
    process.env.API_CONTRACT_ISOLATED,
    "1",
    "Use the owned PostGIS runner infra/api_contract_test.py",
  );
  const base = process.env.API_CONTRACT_BASE_URL;
  assert.match(base || "", /^http:\/\/127\.0\.0\.1:\d+$/);
  const contract = await loadContract();
  function client() {
    let cookie = "";
    return async (method, path, body, headers = {}) => {
      const result = await fetch(base + path, {
        method,
        body,
        headers: { Cookie: cookie, ...headers },
        redirect: "manual",
        signal: AbortSignal.timeout(10000),
      });
      for (const value of result.headers.getSetCookie()) {
        if (value.startsWith("JSESSIONID=")) cookie = value.split(";")[0];
      }
      const actual = {
        status: result.status,
        headers: result.headers,
        text: await result.text(),
      };
      const parsed = response(
        contract,
        operation(contract.api, method, path),
        actual,
        method,
      );
      return { ...actual, body: parsed };
    };
  }
  const anonymous = client(),
    editor = client();
  const session = async (call) => {
    const result = await call("GET", "/api/v1/session");
    assert.equal(result.status, 200);
    return result.body;
  };
  const anonymousSession = await session(anonymous);
  assert.equal(anonymousSession.can_edit, false);
  const initial = await session(editor);
  assert.equal(initial.editing_enabled, true);
  const loginPage = await editor("GET", "/login");
  assert.match(loginPage.body, /name="username"/);
  const failedLogin = await editor(
    "POST",
    "/login",
    new URLSearchParams({ username: "invalid", password: "invalid" }),
    { [initial.csrf_header]: initial.csrf_token },
  );
  assert.equal(failedLogin.status, 302);
  assert.equal(
    new URL(failedLogin.headers.get("location"), base).pathname,
    "/login",
  );
  assert.equal(
    new URL(failedLogin.headers.get("location"), base).search,
    "?error",
  );
  const login = await editor(
    "POST",
    "/login",
    new URLSearchParams({
      username: process.env.API_CONTRACT_EDITOR_USERNAME,
      password: process.env.API_CONTRACT_EDITOR_PASSWORD,
    }),
    { [initial.csrf_header]: initial.csrf_token },
  );
  assert.equal(login.status, 302);
  assert.equal(new URL(login.headers.get("location"), base).pathname, "/");
  const editorSession = await session(editor);
  assert.equal(editorSession.can_edit, true);
  const stale = await editor("POST", "/api/v1/osm/points/add", "[]", {
    "Content-Type": "application/json",
    [initial.csrf_header]: initial.csrf_token,
  });
  assert.equal(stale.status, 403);
  assert.equal(stale.body.code, "invalid_csrf");
  let id;
  for (const example of examples.cases) {
    const call = example.editor ? editor : anonymous;
    const auth = example.editor ? editorSession : anonymousSession;
    const headers = example.csrf ? { [auth.csrf_header]: auth.csrf_token } : {};
    if (example.body !== undefined)
      headers["Content-Type"] = "application/json";
    const target = example.path.replace("${id}", id);
    const actual = await call(
      example.method,
      target,
      example.body === undefined ? undefined : JSON.stringify(example.body),
      headers,
    );
    assert.equal(actual.status, example.status, example.name);
    if (example.code)
      assert.equal(actual.body.code, example.code, example.name);
    if (example.nonempty) {
      assert.ok(actual.body.length > 0, example.name);
      const layer = target.split("?")[0].split("/").pop();
      for (const feature of actual.body)
        contract.validate(
          contract.api.components.schemas[`${layer}Geometry`],
          JSON.parse(feature.geojson),
        );
    }
    if (example.distance)
      assert.ok(Math.abs(actual.body.totalDistance - example.distance) < 0.001);
    if (example.captureId) {
      assert.equal(actual.body.inserted, example.body.length);
      id = actual.body.points[0].id;
      assert.match(id, /^user:[0-9a-f-]{36}$/);
      assert.deepEqual(JSON.parse(actual.body.points[0].geojson).coordinates, [
        example.body[0].lng,
        example.body[0].lat,
      ]);
    }
    // Use actual HTTP bytes to prove both schema and status drift are detected.
    if (example.name === "read-points") {
      const op = structuredClone(operation(contract.api, "GET", target));
      delete op.responses["200"];
      assert.throws(
        () => response(contract, op, actual, "GET"),
        /Undocumented response status/,
      );
      op.responses["200"] = {
        description: "mutation",
        content: { "application/json": { schema: { type: "object" } } },
      };
      assert.throws(
        () => response(contract, op, actual, "GET"),
        /must be object/,
      );
    }
    console.log(`Contract HTTP: ${example.name} ${actual.status}`);
  }
  for (const layer of ["points", "roads", "polygons"]) {
    assert.equal((await anonymous("HEAD", `/api/v1/osm/${layer}`)).status, 200);
  }
  for (const [method, target] of [
    ["OPTIONS", "/api/v1/osm/points"],
    ["HEAD", "/api/v1/session"],
  ]) {
    assert.equal((await anonymous(method, target)).status, 401);
    assert.equal((await editor(method, target)).status, 403);
  }
  const preflight = await anonymous(
    "OPTIONS",
    "/api/v1/osm/points",
    undefined,
    {
      Origin: "https://untrusted.invalid",
      "Access-Control-Request-Method": "GET",
    },
  );
  assert.equal(preflight.status, 403);
  assert.equal(preflight.headers.get("access-control-allow-origin"), null);
  for (const suffix of ["", "/liveness", "/readiness"]) {
    const health = await anonymous(
      "GET",
      `/actuator/health${suffix}`,
      undefined,
      { Accept: "application/vnd.spring-boot.actuator.v3+json" },
    );
    assert.equal(health.status, 200);
    assert.deepEqual(health.body, { status: "UP" });
  }
  assert.equal((await editor("GET", "/logout")).status, 200);
  const logout = await editor("POST", "/logout", undefined, {
    [editorSession.csrf_header]: editorSession.csrf_token,
  });
  assert.equal(logout.status, 302);
  assert.equal(new URL(logout.headers.get("location"), base).pathname, "/");
  assert.ok(
    logout.headers
      .getSetCookie()
      .some(
        (value) =>
          value.startsWith("JSESSIONID=") && value.includes("Max-Age=0"),
      ),
  );
  assert.equal((await session(editor)).can_edit, false);
  console.log(
    "Contract HTTP: session, login/logout, fresh CSRF, HEAD/OPTIONS, CORS, health and drift mutations passed",
  );
}
main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
