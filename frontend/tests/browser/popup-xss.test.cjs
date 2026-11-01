const test = require('node:test');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const AxeBuilder = require('@axe-core/playwright').default;
const {baseURL, editor: createEditor} = require('./support.cjs');

const payload = `<b>Кавычки " ' &</b><img src="/__popup_test_missing" onerror="window.__popupXss=true"><svg onload="window.__popupXss=true"></svg>`;
let browser;
test.before(async () => { browser = await chromium.launch({headless: true}); });
test.after(async () => { await browser?.close(); });

async function editor(t) {
  return createEditor(browser, t);
}

async function literalPopup(page, expected) {
  const popup = page.locator('.leaflet-popup-content').last();
  await popup.waitFor({state: 'visible'});
  assert.equal(await popup.textContent(), expected);
  assert.equal(await popup.locator('img, svg, script, iframe, [onerror], [onload]').count(), 0);
  assert.equal(await page.evaluate(() => window.__popupXss), false);
}

test('draft, persisted point, selection and distance names remain literal after save/reload', async t => {
  const {context, page} = await editor(t);
  const ids = [];
  await page.locator('.points-control').check();
  await page.waitForFunction(() => pointsMarkers.getLayers().length >= 3);
  await page.locator('.add-point-control input').check();
  await page.evaluate(() => map.fire('click', {latlng: L.latLng(52.34, 35.35)}));
  await page.locator('.change-add-point-name input').fill(payload);
  await page.locator('.save-new-point').click();
  await page.evaluate(() => newMarkers[0].marker.openPopup());
  await literalPopup(page, payload);
  const saved = page.waitForResponse(r => r.url().endsWith('/points/add') && r.status() === 201);
  await page.locator('.add-point-save').click();
  const data = await (await saved).json();
  ids.push(data.points[0].id);
  assert.equal(data.points[0].name, payload);
  await page.reload({waitUntil: 'domcontentloaded'});
  await page.waitForFunction(() => typeof accessState !== 'undefined' && accessState.can_edit);
  const loaded = page.waitForResponse(r => r.url().endsWith('/osm/points') && r.status() === 200);
  await page.locator('.points-control').check();
  assert.equal((await (await loaded).json()).find(point => point.id === ids[0]).name, payload);
  await page.waitForFunction(id => pointsMarkers.getLayers().some(group => group.pointId === id), ids[0]);
  await page.evaluate(id => pointsMarkers.getLayers().find(group => group.pointId === id).getLayers()[0].openPopup(), ids[0]);
  await literalPopup(page, payload);
  await page.locator('.points-distance > input').check();
  const names = await page.evaluate(id => {
    const stored = pointsMarkers.getLayers().find(group => group.pointId === id).getLayers()[0];
    const osm = pointsMarkers.getLayers().find(group => group.pointId.startsWith('osm:')).getLayers()[0];
    stored.fire('click'); osm.fire('click');
    return selectedMarkers.map(point => point.name);
  }, ids[0]);
  assert.equal(await page.locator('.points-distance-selected-list li').first().textContent(), payload);
  const calculated = page.waitForResponse(r => r.url().endsWith('/distances') && r.status() === 200);
  await page.locator('.points-distance-calculate-control').click();
  const distance = await (await calculated).json();
  await page.waitForFunction(() => distanceLayerGroup.getLayers().length === 1);
  await page.evaluate(() => distanceLayerGroup.getLayers()[0].openPopup());
  const expected = `Дистанция участка ${names[0]}-${names[1]}: ${distance.lines[0].distance.toFixed(2)} м` +
    `Общая дистанция: ${distance.totalDistance.toFixed(2)} м`;
  await literalPopup(page, expected);
  assert.equal(await page.locator('.leaflet-popup-content').last().locator('br').count(), 1);
  await page.waitForFunction(() => document.querySelectorAll('.leaflet-popup').length === 1);
  const axe = await new AxeBuilder({page}).include('.leaflet-popup').analyze();
  assert.deepEqual(axe.violations.map(violation => violation.id), []);
  await page.locator('.leaflet-popup-close-button').focus();
  await page.keyboard.press('Enter');
  await page.locator('.leaflet-popup').waitFor({state:'detached'});
});

for (const layer of ['roads', 'polygons']) {
  test(`${layer} imported names and empty-name fallback are literal in real Leaflet popups`, async t => {
    const {page} = await editor(t);
    // Keep actual API geometry; inject only transport names to exercise the renderer.
    await page.route(`**/api/v1/osm/${layer}`, async route => {
      const response = await route.fetch();
      const data = await response.json();
      assert.ok(data.length >= 2);
      data[0].name = payload; data[1].name = null;
      await route.fulfill({response, json: data});
    });
    await page.locator(`.${layer}-control`).check();
    await page.waitForFunction(layer => ({roads: roadsMarkers, polygons: polygonsMarkers}[layer]).getLayers().length >= 2, layer);
    for (const [index, expected] of [[0, payload], [1, 'Без названия']]) {
      await page.evaluate(({layer, index}) => ({roads: roadsMarkers, polygons: polygonsMarkers}[layer])
        .getLayers()[index].getLayers()[0].openPopup(), {layer, index});
      await literalPopup(page, expected);
    }
  });
}
