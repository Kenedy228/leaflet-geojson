const test = require("node:test");
const assert = require("node:assert/strict");
const { chromium } = require("playwright");
const AxeBuilder = require("@axe-core/playwright").default;
const { baseURL } = require("./support.cjs");
let browser;
test.before(async () => {
  browser = await chromium.launch({ headless: true });
});
test.after(async () => {
  await browser?.close();
});

test("login matches the app and supports error, keyboard entry and logout", async (t) => {
  const context = await browser.newContext();
  t.after(() => context.close());
  const page = await context.newPage();
  await page.route("**/*tile.openstreetmap.org/**", (r) => r.abort());
  for (const width of [1440, 390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(baseURL + "/login", { waitUntil: "networkidle" });
    assert.equal(
      await page
        .getByRole("heading", { name: "Вход редактора", exact: true })
        .count(),
      1,
    );
    await page.getByRole("button", { name: "Войти", exact: true }).waitFor();
    assert.ok(
      await page
        .getByRole("button", { name: "Войти", exact: true })
        .isEnabled(),
    );
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    const axe = await new AxeBuilder({ page }).analyze();
    assert.deepEqual(
      axe.violations.map((v) => v.id),
      [],
    );
  }
  await page
    .getByLabel("Логин", { exact: true })
    .fill(process.env.POPUP_TEST_EDITOR_USERNAME);
  await page.getByLabel("Пароль", { exact: true }).fill("wrong-password");
  await page.getByRole("button", { name: "Войти", exact: true }).click();
  await page
    .getByRole("alert")
    .filter({ hasText: "Неверный логин или пароль" })
    .waitFor();
  await page
    .getByLabel("Логин", { exact: true })
    .fill(process.env.POPUP_TEST_EDITOR_USERNAME);
  await page
    .getByLabel("Пароль", { exact: true })
    .fill(process.env.POPUP_TEST_EDITOR_PASSWORD);
  await page.keyboard.press("Enter");
  await page.waitForURL(baseURL + "/");
  await page.getByRole("button", { name: "Выйти", exact: true }).click();
  await page.getByRole("link", { name: "Войти как редактор" }).waitFor();
  assert.equal(
    (await (await context.request.get(baseURL + "/api/v1/session")).json())
      .can_edit,
    false,
  );
});

test("login retries failed session bootstrap and respects readonly", async (t) => {
  const context = await browser.newContext();
  t.after(() => context.close());
  const page = await context.newPage();
  let fail = true;
  await page.route("**/api/v1/session", async (route) => {
    if (fail) return route.abort();
    return route.continue();
  });
  await page.goto(baseURL + "/login", { waitUntil: "networkidle" });
  const submit = page.getByRole("button", { name: "Войти", exact: true });
  assert.equal(await submit.isDisabled(), true);
  fail = false;
  await page.getByRole("button", { name: "Повторить", exact: true }).click();
  await page.waitForFunction(
    () => !document.querySelector("#login-submit").disabled,
  );
  await page.unroute("**/api/v1/session");
  await page.route("**/api/v1/session", async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    await route.fulfill({
      response,
      json: { ...body, editing_enabled: false, can_edit: false },
    });
  });
  await page.reload({ waitUntil: "networkidle" });
  assert.equal(await submit.isDisabled(), true);
  assert.match(
    await page.getByRole("status").textContent(),
    /Редактирование отключено/,
  );
  await page.getByRole("link", { name: "Продолжить без входа" }).focus();
  await page.keyboard.press("Enter");
  await page.waitForURL(baseURL + "/");
});
