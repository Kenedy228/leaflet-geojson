const test = require("node:test");
const assert = require("node:assert/strict");
const { chromium } = require("playwright");
const AxeBuilder = require("@axe-core/playwright").default;
const {
  baseURL,
  editor,
  drafts,
  points,
  activate,
  until,
} = require("./support.cjs");
let browser;
test.before(async () => {
  browser = await chromium.launch({ headless: true });
});
test.after(async () => {
  await browser?.close();
});
async function noEditor(page) {
  assert.equal(
    await page
      .getByRole("heading", { name: "Новые точки", exact: true })
      .count(),
    0,
  );
  assert.equal(
    await page
      .getByRole("heading", { name: "Выбранная точка", exact: true })
      .count(),
    0,
  );
  assert.equal(
    await page.locator(".leaflet-interactive[data-draft-id]").count(),
    0,
  );
}
test("guest hides editor tools but retains public layers and distance", async (t) => {
  const context = await browser.newContext();
  t.after(() => context.close());
  const page = await context.newPage();
  await page.route("**/*tile.openstreetmap.org/**", (r) => r.abort());
  await page.goto(baseURL, { waitUntil: "networkidle" });
  await noEditor(page);
  await points(page);
  assert.ok((await page.locator("[data-point-id]").count()) >= 3);
  assert.equal(
    await page.locator(".points-distance-control").isEnabled(),
    true,
  );
  await page.getByRole("link", { name: "Войти как редактор" }).focus();
  assert.equal(
    await page
      .locator(".access-login")
      .evaluate((x) => x === document.activeElement),
    true,
  );
  const axe = await new AxeBuilder({ page }).exclude("#map").analyze();
  assert.deepEqual(
    axe.violations.map((v) => v.id),
    [],
  );
});
test("logout removes draft popup and tools while keeping public layers and distance", async (t) => {
  const { page } = await editor(browser, t);
  await points(page);
  await drafts(page, ["Убрать после выхода"]);
  await page.locator(".points-distance-control").check();
  const markers = page.locator('[data-point-id^="osm:"]');
  await activate(markers.nth(0));
  await activate(markers.nth(1));
  await page.locator(".points-distance-calculate-control").click();
  await until(() => page.locator("[data-distance-line]").count(), 1);
  const distance = await page.locator(".distance-status").textContent();
  await activate(page.locator(".leaflet-interactive[data-draft-id]").first());
  await page
    .locator(".leaflet-popup")
    .filter({ hasText: "Убрать после выхода" })
    .waitFor();
  await page.locator(".access-logout").focus();
  await page.keyboard.press("Enter");
  await page.locator(".access-login").waitFor();
  await noEditor(page);
  await until(() => page.locator(".leaflet-popup").count(), 0);
  await until(
    () =>
      page
        .locator(".access-login")
        .evaluate((node) => node === document.activeElement),
    true,
  );
  assert.equal(await page.locator(".distance-status").textContent(), distance);
  assert.equal(await page.locator("[data-distance-line]").count(), 1);
  assert.ok((await page.locator("[data-point-id]").count()) >= 3);
});
test("logout locks editor tools throughout a delayed server response", async (t) => {
  const { page } = await editor(browser, t);
  await drafts(page, ["Черновик"]);
  let release;
  const gate = new Promise((r) => (release = r));
  t.after(() => release());
  let entered;
  const intercepted = new Promise((r) => (entered = r));
  await page.route("**/logout", async (r) => {
    entered();
    await gate;
    await r.continue();
  });
  await page.locator(".access-logout").click();
  await intercepted;
  await noEditor(page);
  release();
  await page.locator(".access-login").waitFor();
  await noEditor(page);
});
test("returning to a tab clears editor state after the session ends elsewhere", async (t) => {
  const { page, context } = await editor(browser, t);
  await drafts(page, ["Истёкший сеанс"]);
  const session = await (
    await context.request.get(baseURL + "/api/v1/session")
  ).json();
  await context.request.post(baseURL + "/logout", {
    headers: { [session.csrf_header]: session.csrf_token },
  });
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await page.locator(".access-login").waitFor();
  await noEditor(page);
});
test("failed logout keeps editor tools hidden and exposes an explicit retry", async (t) => {
  const { page } = await editor(browser, t);
  await drafts(page, ["Не возвращать"]);
  await page.route("**/logout", (r) => r.abort());
  await page.locator(".access-logout").click();
  await page
    .getByRole("button", { name: "Повторить выход", exact: true })
    .waitFor();
  await noEditor(page);
  await page.unroute("**/logout");
  await page
    .getByRole("button", { name: "Повторить выход", exact: true })
    .click();
  await page.locator(".access-login").waitFor();
  await noEditor(page);
});

test("an unavailable access check hides drafts and restores them only to the confirmed editor", async (t) => {
  const { page } = await editor(browser, t);
  await drafts(page, ["Пережить сбой соединения"]);
  await page.route("**/api/v1/session", (r) => r.abort());
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await page
    .getByRole("button", { name: "Проверить доступ", exact: true })
    .waitFor();
  await noEditor(page);
  await page.unroute("**/api/v1/session");
  await page
    .getByRole("button", { name: "Проверить доступ", exact: true })
    .click();
  await page.locator(".change-add-point-name input").waitFor();
  assert.equal(
    await page.locator(".change-add-point-name input").inputValue(),
    "Пережить сбой соединения",
  );
  assert.equal(
    await page.locator(".leaflet-interactive[data-draft-id]").count(),
    1,
  );
});
