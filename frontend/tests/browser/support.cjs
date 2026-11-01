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

async function until(read, expected, message = "UI did not settle") {
  await assertEventually(
    async () => assert.deepEqual(await read(), expected),
    message,
  );
}
async function assertEventually(check, message) {
  const deadline = Date.now() + 10000;
  for (;;) {
    try {
      await check();
      return;
    } catch (error) {
      if (Date.now() >= deadline) {
        error.message = `${message}: ${error.message}`;
        throw error;
      }
      await new Promise((resolve) => setTimeout(resolve, 30));
    }
  }
}
async function editor(browser, t, options = {}) {
  const context = await browser.newContext(options);
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
  const scriptErrors = [];
  page.on("pageerror", (error) => scriptErrors.push(error.message));
  t.after(() =>
    assert.deepEqual(scriptErrors, [], "No uncaught browser errors"),
  );
  await page.route(/https:\/\/([abc]\.)?tile\.openstreetmap\.org\//, (route) =>
    route.fulfill({ status: 204 }),
  );
  await page.goto(baseURL, { waitUntil: "domcontentloaded" });
  await page.locator("#map.leaflet-container").waitFor();
  await page.locator(".access-logout").waitFor();
  await until(() => page.locator(".add-point-control input").isEnabled(), true);
  return { context, page };
}
async function drafts(page, names) {
  await page.locator(".add-point-control input").check();
  for (const name of names) {
    const count = await page.locator(".change-add-point-name").count();
    // Use the public keyboard equivalent; stored points may cover any map pixel.
    await page
      .getByLabel("Широта", { exact: true })
      .fill(String(52.333 + count * 0.0005));
    await page
      .getByLabel("Долгота", { exact: true })
      .fill(String(35.342 + count * 0.0007));
    await page.getByRole("button", { name: "Добавить по координатам" }).focus();
    await page.keyboard.press("Enter");
    await until(
      () => page.locator(".change-add-point-name").count(),
      count + 1,
    );
    await page.locator(".change-add-point-name input").last().fill(name);
  }
}
async function save(page) {
  const response = page.waitForResponse(
    (r) => r.request().method() === "POST" && r.url().endsWith("/points/add"),
  );
  await page.locator(".add-point-save").click();
  const result = await response;
  await assertEventually(async () => {
    const rows = await page.locator(".change-add-point-name").count();
    assert.ok(
      rows === 0 || !(await page.locator(".add-point-save").isDisabled()),
    );
  });
  return result;
}
async function points(page) {
  await page.locator(".points-control").check();
  await page.locator('[data-point-id^="osm:"]').first().waitFor();
}
async function activate(locator) {
  await locator.focus();
  await locator.press("Enter");
}
async function settlePopups(page) {
  // Axe must inspect the final colours, not Leaflet's intermediate opacity during fade-in.
  // Checking opacity also covers the frame before the CSS transition has started.
  await page.waitForFunction(() =>
    [...document.querySelectorAll(".leaflet-popup")].every(
      (popup) =>
        getComputedStyle(popup).opacity === "1" &&
        popup
          .getAnimations()
          .every(
            (animation) =>
              animation.playState !== "running" && !animation.pending,
          ),
    ),
  );
}
function gate() {
  let release, arrived;
  const wait = new Promise((resolve) => (release = resolve));
  const arrival = new Promise((resolve) => (arrived = resolve));
  return { wait, release, arrived, arrival };
}
module.exports = {
  baseURL,
  editor,
  until,
  assertEventually,
  drafts,
  save,
  points,
  activate,
  settlePopups,
  gate,
};
