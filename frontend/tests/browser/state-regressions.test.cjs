const test = require("node:test");
const assert = require("node:assert/strict");
const { chromium } = require("playwright");
const AxeBuilder = require("@axe-core/playwright").default;
const {
  editor,
  until,
  assertEventually,
  drafts,
  points,
  activate,
  settlePopups,
  gate,
} = require("./support.cjs");
let browser;
test.before(async () => {
  browser = await chromium.launch({ headless: true });
});
test.after(async () => {
  await browser?.close();
});

for (const layer of ["points", "roads", "polygons"]) {
  const label = { points: "Точки", roads: "Дороги", polygons: "Полигоны" }[
    layer
  ];
  const paths =
    layer === "points"
      ? "[data-point-id]"
      : `[data-layer="${layer}"].leaflet-interactive`;
  test(`${layer}: hidden pending layer stays hidden and reverse responses preserve newest request`, async (t) => {
    const { page } = await editor(browser, t);
    const first = gate();
    let calls = 0;
    await page.route(`**/api/v1/osm/${layer}`, async (route) => {
      const response = await route.fetch();
      const data = await response.json();
      const call = ++calls;
      data.forEach((item, i) => {
        item.name = `${call === 1 ? "Устаревший" : "Актуальный"} ${i}`;
      });
      if (call === 1) {
        first.arrived();
        await first.wait;
      }
      // A cancelled HTTP request is allowed to reject at the transport boundary.
      await route.fulfill({ response, json: data }).catch(() => {});
    });
    try {
      await page.locator(`.${layer}-control`).check();
      await first.arrival;
      assert.equal(await page.locator(`.${layer}-control`).isEnabled(), true);
      await page.locator(`.${layer}-control`).uncheck();
      assert.equal(await page.locator(paths).count(), 0);
      await page.locator(`.${layer}-control`).check();
      await page.locator(paths).first().waitFor();
      first.release();
      await page.waitForLoadState("networkidle");
      await activate(page.locator(paths).first());
      await assertEventually(async () =>
        assert.match(
          await page.locator(".leaflet-popup-content").last().textContent(),
          /Актуальный/,
        ),
      );
      await page.locator(`.${layer}-control`).uncheck();
      await until(() => page.locator(paths).count(), 0);
    } finally {
      first.release();
    }
  });
  test(`${layer}: a delayed response cannot make an unchecked layer visible`, async (t) => {
    const { page } = await editor(browser, t);
    const delayed = gate();
    await page.route(`**/api/v1/osm/${layer}`, async (route) => {
      const response = await route.fetch();
      delayed.arrived();
      await delayed.wait;
      await route.fulfill({ response }).catch(() => {});
    });
    try {
      await page.locator(`.${layer}-control`).check();
      await delayed.arrival;
      await page.locator(`.${layer}-control`).uncheck();
      delayed.release();
      await page.waitForLoadState("networkidle");
      assert.equal(await page.locator(paths).count(), 0);
      assert.equal(await page.locator(`.${layer}-control`).isChecked(), false);
    } finally {
      delayed.release();
    }
  });
  for (const failure of ["offline", "invalid JSON"]) {
    test(`${layer}: ${failure} is visible and retry reloads real geometry`, async (t) => {
      const { page } = await editor(browser, t);
      await page.route(`**/api/v1/osm/${layer}`, (route) =>
        failure === "offline"
          ? route.abort("internetdisconnected")
          : route.fulfill({ contentType: "application/json", body: "{" }),
      );
      await page.locator(`.${layer}-control`).check();
      const retry = page.getByRole("button", {
        name: `Повторить загрузку: ${label}`,
      });
      await retry.waitFor();
      assert.ok(
        (
          await page
            .locator(`.layer-status[data-layer="${layer}"]`)
            .textContent()
        ).trim().length > 0,
      );
      assert.equal(await page.locator(paths).count(), 0);
      await page.unroute(`**/api/v1/osm/${layer}`);
      await retry.click();
      await page.locator(paths).first().waitFor();
      assert.equal(await page.locator(`.${layer}-control`).isChecked(), true);
    });
  }
}

test("draft discard removes only its row; mode switches preserve later draft and do not create phantom points", async (t) => {
  const { page } = await editor(browser, t);
  await points(page);
  await drafts(page, ["Удалить", "Сохранить"]);
  await page.locator(".change-add-point-name .drop-new-point").first().click();
  await until(() => page.locator(".change-add-point-name").count(), 1);
  assert.equal(
    await page.locator(".change-add-point-name input").inputValue(),
    "Сохранить",
  );
  await activate(page.locator('[data-point-id^="osm:"]').first());
  assert.equal(await page.locator(".change-add-point-name").count(), 1);
  assert.equal(
    await page.locator(".points-distance-selected-list li").count(),
    0,
  );
  await page.locator(".points-distance-control").check();
  assert.equal(
    await page.locator(".add-point-control input").isChecked(),
    false,
  );
  await activate(page.locator('[data-point-id^="osm:"]').first());
  await until(
    () => page.locator(".points-distance-selected-list li").count(),
    1,
  );
  await page.locator(".add-point-control input").check();
  assert.equal(
    await page.locator(".points-distance-control").isChecked(),
    false,
  );
  await until(
    () => page.locator(".points-distance-selected-list li").count(),
    0,
  );
  await page.locator(".add-point-control input").uncheck();
  assert.equal(
    await page.locator(".change-add-point-name input").inputValue(),
    "Сохранить",
  );
  assert.equal(
    await page.locator("[data-draft-id].leaflet-interactive").count(),
    1,
  );
});

for (const reset of ["reset", "hide points", "leave distance"]) {
  test(`pending distance cannot restore selection or lines after ${reset}`, async (t) => {
    const { page } = await editor(browser, t);
    await points(page);
    await page.locator(".points-distance-control").check();
    for (let i = 0; i < 2; i++)
      await activate(page.locator('[data-point-id^="osm:"]').nth(i));
    const delayed = gate();
    await page.route("**/api/v1/osm/distances", async (route) => {
      const response = await route.fetch();
      delayed.arrived();
      await delayed.wait;
      await route.fulfill({ response }).catch(() => {});
    });
    try {
      await page.locator(".points-distance-calculate-control").click();
      await delayed.arrival;
      if (reset === "reset")
        await page.locator(".points-distance-reset-control").click();
      else if (reset === "hide points")
        await page.locator(".points-control").uncheck();
      else await page.locator(".points-distance-control").uncheck();
      delayed.release();
      await page.waitForLoadState("networkidle");
      await until(
        () => page.locator(".points-distance-selected-list li").count(),
        0,
      );
      assert.equal(await page.locator("[data-distance-line]").count(), 0);
      assert.doesNotMatch(
        await page.locator(".distance-status").textContent(),
        /[1-9]\d*[.,]\d+\s*м/,
      );
    } finally {
      delayed.release();
    }
  });
}

test("recalculation after reset preserves the new ordered point snapshot when old response arrives last", async (t) => {
  const { page } = await editor(browser, t);
  await points(page);
  await page.locator(".points-distance-control").check();
  const markers = page.locator('[data-point-id^="osm:"]');
  for (let i = 0; i < 2; i++) await activate(markers.nth(i));
  const first = gate();
  let calls = 0;
  await page.route("**/api/v1/osm/distances", async (route) => {
    const response = await route.fetch();
    if (++calls === 1) {
      first.arrived();
      await first.wait;
    }
    await route.fulfill({ response }).catch(() => {});
  });
  try {
    await page.locator(".points-distance-calculate-control").click();
    await first.arrival;
    await page.locator(".points-distance-reset-control").click();
    await activate(markers.nth(2));
    await activate(markers.nth(0));
    const expected = await page
      .locator(".points-distance-selected-list li")
      .allTextContents();
    await page.locator(".points-distance-calculate-control").click();
    await page.locator("[data-distance-line]").waitFor({ state: "attached" });
    first.release();
    await page.waitForLoadState("networkidle");
    await activate(page.locator("[data-distance-line]"));
    const popup = await page
      .locator(".leaflet-popup-content")
      .last()
      .textContent();
    assert.ok(popup.includes(expected[0]) && popup.includes(expected[1]));
    assert.deepEqual(
      await page.locator(".points-distance-selected-list li").allTextContents(),
      expected,
    );
  } finally {
    first.release();
  }
});

test("375px viewport keeps map and attribution visible, supports keyboard controls and has no axe violations", async (t) => {
  const { page } = await editor(browser, t, {
    viewport: { width: 375, height: 812 },
    isMobile: true,
    hasTouch: true,
  });
  await points(page);
  const control = page.locator(".points-distance-control");
  await control.focus();
  await page.keyboard.press("Space");
  assert.equal(await control.isChecked(), true);
  await activate(page.locator('[data-point-id^="osm:"]').first());
  await until(
    () => page.locator(".points-distance-selected-list li").count(),
    1,
  );
  await page.locator(".points-distance-reset-control").focus();
  await page.keyboard.press("Enter");
  await until(
    () => page.locator(".points-distance-selected-list li").count(),
    0,
  );
  assert.equal(await page.locator("#map.leaflet-container").count(), 1);
  const attribution = page.locator(".leaflet-control-attribution");
  assert.equal(await attribution.isVisible(), true);
  assert.match(await attribution.textContent(), /OpenStreetMap/);
  assert.equal(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
    true,
  );
  await settlePopups(page);
  const result = await new AxeBuilder({ page }).analyze();
  assert.deepEqual(
    result.violations.map((v) => ({
      id: v.id,
      nodes: v.nodes.map((node) => node.target),
    })),
    [],
  );
});

test("real map click creates one draft only while add mode is enabled", async (t) => {
  const { page } = await editor(browser, t);
  const map = page.locator("#map");
  const box = await map.boundingBox();
  assert.ok(box);
  const position = { x: box.width * 0.7, y: box.height * 0.65 };
  await map.click({ position });
  assert.equal(await page.locator(".change-add-point-name").count(), 0);
  await page.locator(".add-point-control input").check();
  await map.click({ position });
  await until(() => page.locator(".change-add-point-name").count(), 1);
  await page.locator(".add-point-control input").uncheck();
  await map.click({ position: { x: position.x + 35, y: position.y } });
  assert.equal(await page.locator(".change-add-point-name").count(), 1);
});
