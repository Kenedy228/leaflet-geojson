const test = require("node:test");
const assert = require("node:assert/strict");
const { chromium } = require("playwright");
const AxeBuilder = require("@axe-core/playwright").default;
const {
  editor,
  drafts,
  save,
  points,
  activate,
  until,
} = require("./support.cjs");

const payload = `<b>Кавычки " ' &</b><img src="/__popup_test_missing" onerror="document.title='XSS EXECUTED'"><svg onload="document.title='XSS EXECUTED'"></svg>`;
let browser;
test.before(async () => {
  browser = await chromium.launch({ headless: true });
});
test.after(async () => {
  await browser?.close();
});

async function literalPopup(page, expected) {
  const popup = page.locator(".leaflet-popup-content").last();
  await popup.waitFor({ state: "visible" });
  assert.ok((await popup.textContent()).includes(expected));
  assert.equal(
    await popup
      .locator("img, svg, script, iframe, [onerror], [onload]")
      .count(),
    0,
  );
  assert.notEqual(await page.title(), "XSS EXECUTED");
}

test("draft, persisted point, selection and distance names remain literal after save/reload", async (t) => {
  const { page } = await editor(browser, t);
  await points(page);
  await drafts(page, [payload]);
  await activate(page.locator(".leaflet-interactive[data-draft-id]").first());
  await literalPopup(page, payload);
  const data = await (await save(page)).json();
  assert.equal(data.points[0].name, payload);
  await page.reload({ waitUntil: "domcontentloaded" });
  await page.locator(".access-logout").waitFor();
  await points(page);
  const marker = page.locator(`[data-point-id="${data.points[0].id}"]`);
  await activate(marker);
  await literalPopup(page, payload);
  await page.locator(".points-distance-control").check();
  await activate(marker);
  await activate(page.locator('[data-point-id^="osm:"]').first());
  await until(
    () => page.locator(".points-distance-selected-list li").count(),
    2,
  );
  assert.equal(
    await page
      .locator(".points-distance-selected-list li")
      .first()
      .textContent(),
    payload,
  );
  const calculated = page.waitForResponse(
    (r) => r.url().endsWith("/distances") && r.status() === 200,
  );
  await page.locator(".points-distance-calculate-control").click();
  const distance = await (await calculated).json();
  const line = page.locator("[data-distance-line]").first();
  await line.waitFor({ state: "attached" });
  await activate(line);
  await literalPopup(page, payload);
  assert.ok(
    (
      await page.locator(".leaflet-popup-content").last().textContent()
    ).includes(distance.lines[0].distance.toFixed(2)),
  );
  const axe = await new AxeBuilder({ page })
    .include(".leaflet-popup")
    .analyze();
  assert.deepEqual(
    axe.violations.map((v) => v.id),
    [],
  );
  await page.locator(".leaflet-popup-close-button").last().focus();
  await page.keyboard.press("Enter");
  await until(() => page.locator(".leaflet-popup").count(), 0);
});

for (const layer of ["roads", "polygons"]) {
  test(`${layer} imported names and empty-name fallback are literal in real Leaflet popups`, async (t) => {
    const { page } = await editor(browser, t);
    await page.route(`**/api/v1/osm/${layer}`, async (route) => {
      const response = await route.fetch();
      const data = await response.json();
      assert.ok(data.length >= 2);
      data[0].name = payload;
      data[1].name = null;
      await route.fulfill({ response, json: data });
    });
    await page.locator(`.${layer}-control`).check();
    const paths = page.locator(`.leaflet-interactive[data-layer="${layer}"]`);
    await until(() => paths.count(), 2);
    for (const [index, expected] of [
      [0, payload],
      [1, "Без названия"],
    ]) {
      await activate(paths.nth(index));
      await literalPopup(page, expected);
    }
  });
}
