const test = require('node:test');
const assert = require('node:assert/strict');
const {randomUUID} = require('node:crypto');
const {chromium} = require('playwright');
const AxeBuilder = require('@axe-core/playwright').default;
const {baseURL, editor} = require('./support.cjs');
let browser;
test.before(async () => { browser = await chromium.launch({headless:true}); });
test.after(async () => { await browser?.close(); });

async function drafts(page, names) {
  if (!(await page.locator('.add-point-control input').isChecked())) {
    await page.locator('.add-point-control input').check();
  }
  for (const [index, name] of names.entries()) {
    await page.evaluate(index => map.fire('click', {latlng:L.latLng(52.34 + index * .001,35.35)}), index);
    await page.locator('.change-add-point-name input').last().fill(name);
  }
}
async function save(page) {
  const response = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/points/add'));
  await page.locator('.add-point-save').click();
  const result = await response;
  await page.waitForFunction(() => !pendingPointWrites.create);
  return result;
}
async function saved(context, names) {
  return (await (await context.request.get(baseURL + '/api/v1/osm/points')).json())
    .filter(point => names.includes(point.name));
}
async function preserved(page, names) {
  assert.deepEqual(await page.locator('.change-add-point-name input').evaluateAll(inputs => inputs.map(input => input.value)), names);
  assert.equal(await page.locator('.add-point-save').isEnabled(), true);
  assert.equal(await page.locator('.change-add-point-name input:disabled, .change-add-point-name button:disabled').count(), 0);
  assert.equal(await page.locator('.point-save-status').count(), 1);
}

test('batch save sends visible typed names without per-row confirmation', async t => {
  const {context,page} = await editor(browser,t);
  const names = ['Первое "имя" & ' + randomUUID(), 'Второе имя ' + randomUUID()];
  await drafts(page,names);
  const response = await save(page);
  assert.equal(response.status(),201);
  assert.deepEqual(response.request().postDataJSON().map(point => point.name),names);
  const result = await response.json();
  assert.equal(new Set(result.points.map(point => point.id)).size,2);
  assert.deepEqual((await saved(context,names)).map(point => point.name).sort(),[...names].sort());
  assert.equal(await page.locator('.change-add-point-name').count(),0);
});

for (const failure of ['invalid second point','database rejects second insert']) {
  test(`${failure}: zero records, names retained, correction and retry saves entire batch`, async t => {
    const {context,page} = await editor(browser,t);
    const names = ['Первая ' + randomUUID(), failure.startsWith('invalid') ? '' : 'browser-test-db-failure'];
    await drafts(page,names);
    const response = await save(page);
    assert.equal(response.status(), failure.startsWith('invalid') ? 400 : 500);
    assert.equal((await saved(context,names)).length,0);
    await preserved(page,names);
    const status = await page.locator('.point-save-status').textContent();
    assert.match(status, failure.startsWith('invalid') ? /назван|координат|пакет/i : /сервер/i);
    assert.doesNotMatch(status,/SQL|constraint|Exception|browser_test_batch_failure/);
    assert.deepEqual((await new AxeBuilder({page}).include('.point-save-status').analyze()).violations.map(v => v.id),[]);
    names[1] = 'Исправленная ' + randomUUID();
    await page.locator('.change-add-point-name input').nth(1).fill(names[1]);
    assert.equal((await save(page)).status(),201);
    assert.equal((await saved(context,names)).length,2);
    assert.equal(await page.locator('.change-add-point-name').count(),0);
  });
}

test('unconfirmed response and network error retain drafts for explicit retry', async t => {
  const {context,page} = await editor(browser,t);
  const names = ['Повтор ' + randomUUID()];
  await drafts(page,names);
  await page.route('**/api/v1/osm/points/add',route => route.fulfill({json:{status:'ok',inserted:0}}));
  await save(page);
  await preserved(page,names);
  assert.match(await page.locator('.point-save-status').textContent(),/подтверд/i);
  assert.equal((await saved(context,names)).length,0);
  await page.unroute('**/api/v1/osm/points/add');
  await page.route('**/api/v1/osm/points/add',route => route.abort('failed'));
  await page.locator('.add-point-save').click();
  await page.waitForFunction(() => !pendingPointWrites.create);
  await preserved(page,names);
  assert.match(await page.locator('.point-save-status').textContent(),/подтвержд|сеть|соединен/i);
  assert.equal((await saved(context,names)).length,0);
  await page.unroute('**/api/v1/osm/points/add');
  assert.equal((await save(page)).status(),201);
  assert.equal((await saved(context,names)).length,1);
});

for (const kind of ['malformed','duplicate']) {
  test(`${kind} point IDs cannot confirm a saved batch`, async t => {
    const {context,page} = await editor(browser,t);
    const names = ['ID A ' + randomUUID(),'ID B ' + randomUUID()];
    await drafts(page,names);
    const id = kind === 'malformed' ? 'user:not-a-uuid' : 'user:00000000-0000-0000-0000-000000000001';
    await page.route('**/api/v1/osm/points/add',route => route.fulfill({json:{inserted:2,
      points:[{source:'user',id},{source:'user',id}]}}));
    await save(page);
    await preserved(page,names);
    assert.match(await page.locator('.point-save-status').textContent(),/подтверд/i);
    assert.equal((await saved(context,names)).length,0);
    await page.unroute('**/api/v1/osm/points/add');
    assert.equal((await save(page)).status(),201);
    assert.equal((await saved(context,names)).length,2);
  });
}

test('pending batch cannot be resubmitted and does not clear later drafts', async t => {
  const {context,page} = await editor(browser,t);
  const names = ['Снимок ' + randomUUID()];
  await drafts(page,names);
  let release,arrive;
  const gate = new Promise(resolve => release=resolve);
  const arrived = new Promise(resolve => arrive=resolve);
  let requests=0;
  await page.route('**/api/v1/osm/points/add',async route => {requests++;arrive();await gate;await route.continue();});
  try {
  const response = page.waitForResponse(r => r.url().endsWith('/points/add') && r.status()===201);
  await page.locator('.add-point-save').click();await arrived;
  assert.equal(await page.locator('.add-point-save').isDisabled(),true);
  assert.equal(await page.locator('.change-add-point-name input').first().isDisabled(),true);
  await page.evaluate(() => document.querySelector('.add-point-save').dispatchEvent(new MouseEvent('click')));
  const later='Позже ' + randomUUID();await drafts(page,[later]);
  release();await response;await page.waitForFunction(() => !pendingPointWrites.create);
  assert.equal(requests,1);
  assert.equal((await saved(context,names)).length,1);
  assert.equal((await saved(context,[later])).length,0);
  await preserved(page,[later]);
  } finally { release(); }
});
