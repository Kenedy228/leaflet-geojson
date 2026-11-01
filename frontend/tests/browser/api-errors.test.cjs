const test = require("node:test");
const assert = require("node:assert/strict");
const { randomUUID } = require("node:crypto");
const { chromium } = require("playwright");
const {
  baseURL,
  editor,
  points,
  activate,
  until,
  assertEventually,
  drafts,
} = require("./support.cjs");
let browser;
test.before(async () => {
  browser = await chromium.launch({ headless: true });
});
test.after(async () => {
  await browser?.close();
});

test("actual HTTP errors from MVC, CORS and firewall share the safe JSON DTO", async (t) => {
  const { context } = await editor(browser, t);
  for (const [path, options, status, code] of [
    [
      "/api/v1/osm/distances",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        data: "{",
      },
      400,
      "invalid_request",
    ],
    [
      "/api/v1/osm/distances",
      {
        method: "POST",
        data: {
          points: [
            [0, 91],
            [0, 0],
          ],
        },
      },
      400,
      "invalid_distance_request",
    ],
    [
      "/api/v1/osm/points/add",
      {
        method: "OPTIONS",
        headers: {
          Origin: "https://untrusted.example",
          "Access-Control-Request-Method": "POST",
        },
      },
      403,
      "forbidden",
    ],
    ["/api/v1/missing", { method: "GET" }, 404, "not_found"],
    ["/api/v1/osm/points;bad", { method: "GET" }, 400, "invalid_request"],
  ]) {
    const response = await context.request.fetch(baseURL + path, options);
    assert.equal(response.status(), status);
    const body = await response.json();
    assert.deepEqual(Object.keys(body).sort(), ["code", "message"]);
    assert.equal(body.code, code);
    assert.equal(typeof body.message, "string");
  }
});

test("distance and layer failures display API messages and can be retried", async (t) => {
  const { page } = await editor(browser, t);
  const scriptErrors = [];
  page.on("pageerror", (error) => scriptErrors.push(error.message));
  await page.route("**/api/v1/osm/roads", (route) =>
    route.fulfill({
      status: 500,
      json: { code: "internal_error", message: "Дороги временно недоступны" },
    }),
  );
  await page.locator(".roads-control").check();
  await assertEventually(async () =>
    assert.match(
      await page.locator('.layer-status[data-layer="roads"]').textContent(),
      /Дороги временно недоступны/,
    ),
  );
  await page.unroute("**/api/v1/osm/roads");
  await page
    .getByRole("button", { name: "Повторить загрузку: Дороги" })
    .click();
  await until(
    () => page.locator('[data-layer="roads"].leaflet-interactive').count(),
    2,
  );
  await points(page);
  await page.locator(".points-distance-control").check();
  for (let i = 0; i < 2; i++)
    await activate(page.locator('[data-point-id^="osm:"]').nth(i));
  await page.route("**/api/v1/osm/distances", (route) =>
    route.fulfill({
      status: 400,
      json: {
        code: "invalid_distance_request",
        message: "Проверьте координаты выбранных точек",
      },
    }),
  );
  await page.locator(".points-distance-calculate-control").click();
  await assertEventually(async () =>
    assert.match(
      await page.locator(".distance-error").textContent(),
      /Проверьте координаты выбранных точек/,
    ),
  );
  assert.equal(
    await page.locator(".points-distance-selected-list li").count(),
    2,
  );
  await page.unroute("**/api/v1/osm/distances");
  const calculated = page.waitForResponse(
    (r) => r.url().endsWith("/distances") && r.status() === 200,
  );
  await page.locator(".points-distance-calculate-control").click();
  await calculated;
  await until(() => page.locator("[data-distance-line]").count(), 1);
  assert.deepEqual(scriptErrors, []);
});

test("deleting a point removed by another client reports actual404", async (t) => {
  const { context, page } = await editor(browser, t);
  const csrf = await (
    await context.request.get(baseURL + "/api/v1/session")
  ).json();
  const headers = { [csrf.csrf_header]: csrf.csrf_token };
  const added = await context.request.post(baseURL + "/api/v1/osm/points/add", {
    headers,
    data: [{ name: "stale-point-" + randomUUID(), lat: 52.34, lng: 35.35 }],
  });
  assert.equal(added.status(), 201);
  const id = (await added.json()).points[0].id;
  await points(page);
  await activate(page.locator(`[data-point-id="${id}"]`));
  assert.equal(
    (
      await context.request.delete(baseURL + "/api/v1/osm/points/" + id, {
        headers,
      })
    ).status(),
    204,
  );
  const response = page.waitForResponse(
    (r) => r.request().method() === "DELETE" && r.status() === 404,
  );
  await page.locator(".delete-points-control").click();
  assert.equal((await response).status(), 404);
  await assertEventually(async () =>
    assert.match(
      await page.locator(".delete-status").textContent(),
      /не найдена/,
    ),
  );
  assert.equal(await page.locator(".delete-points-control").isEnabled(), false);
});

test("logout preserves drafts and forbids a later write with the expired session", async (t) => {
  const { context, page } = await editor(browser, t);
  await drafts(page, ["Черновик после выхода"]);
  const token = await (
    await context.request.get(baseURL + "/api/v1/session")
  ).json();
  await page.locator(".access-logout").click();
  await page.locator(".access-login").waitFor();
  assert.equal(
    await page.locator(".change-add-point-name input").inputValue(),
    "Черновик после выхода",
  );
  assert.equal(await page.locator(".add-point-save").isDisabled(), true);
  const denied = await context.request.post(
    baseURL + "/api/v1/osm/points/add",
    {
      headers: { [token.csrf_header]: token.csrf_token },
      data: [{ name: "Forbidden", lat: 52.34, lng: 35.35 }],
    },
  );
  assert.ok([401, 403].includes(denied.status()));
  assert.deepEqual(Object.keys(await denied.json()).sort(), [
    "code",
    "message",
  ]);
});
