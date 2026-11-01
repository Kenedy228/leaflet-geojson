const assert = require("node:assert/strict");

const baseURL = process.env.POPUP_TEST_BASE_URL;
if (
  process.env.POPUP_TEST_ISOLATED !== "1" ||
  !baseURL ||
  new URL(baseURL).hostname !== "127.0.0.1"
) {
  throw new Error(
    "Run make test-browser: tests require the disposable local application",
  );
}

async function editor(browser, t) {
  const context = await browser.newContext();
  t.after(() => context.close());
  const token = await (
    await context.request.get(baseURL + "/api/v1/session")
  ).json();
  await context.request.post(baseURL + "/login", {
    form: {
      username: process.env.POPUP_TEST_EDITOR_USERNAME,
      password: process.env.POPUP_TEST_EDITOR_PASSWORD,
      _csrf: token.csrf_token,
    },
  });
  assert.equal(
    (await (await context.request.get(baseURL + "/api/v1/session")).json())
      .can_edit,
    true,
  );
  const page = await context.newPage();
  // Use the exact production distribution files; HTML still enforces its SRI hashes.
  for (const [url, file, contentType] of [
    [
      "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js",
      "leaflet/dist/leaflet.js",
      "application/javascript",
    ],
    [
      "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css",
      "leaflet/dist/leaflet.css",
      "text/css",
    ],
    [
      "https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/js/bootstrap.bundle.min.js",
      "bootstrap/dist/js/bootstrap.bundle.min.js",
      "application/javascript",
    ],
    [
      "https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css",
      "bootstrap/dist/css/bootstrap.min.css",
      "text/css",
    ],
  ]) {
    await page.route(url, (route) =>
      route.fulfill({
        path: require.resolve(file),
        contentType,
        headers: { "access-control-allow-origin": "*" },
      }),
    );
  }
  // Basemap imagery is outside these scenarios; keep test runs off the public tile service.
  await page.route(/https:\/\/([abc]\.)?tile\.openstreetmap\.org\//, (route) =>
    route.fulfill({ status: 204 }),
  );
  page.on("dialog", (dialog) => dialog.accept());
  page.on("pageerror", (error) =>
    console.error("Browser script error:", error.message),
  );
  page.on("requestfailed", (request) =>
    console.error(
      "Browser request failed:",
      request.url().split("?")[0],
      request.failure()?.errorText,
    ),
  );
  await page.addInitScript(() => {
    window.__popupXss = false;
  });
  await page.goto(baseURL, { waitUntil: "domcontentloaded" });
  await page.waitForFunction(
    () =>
      typeof accessState !== "undefined" &&
      accessState.can_edit &&
      typeof pointsMarkers !== "undefined" &&
      typeof roadsMarkers !== "undefined" &&
      typeof polygonsMarkers !== "undefined",
  );
  return { context, page };
}

module.exports = { baseURL, editor };
