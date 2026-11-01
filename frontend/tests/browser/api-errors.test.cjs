const test = require('node:test');
const assert = require('node:assert/strict');
const {randomUUID} = require('node:crypto');
const {chromium} = require('playwright');
const {baseURL, editor} = require('./support.cjs');
let browser;
test.before(async () => { browser = await chromium.launch({headless:true}); });
test.after(async () => { await browser?.close(); });

test('actual HTTP errors from MVC, CORS and firewall share the safe JSON DTO', async t => {
  const {context}=await editor(browser,t);
  for(const [path,options,status,code] of [
    ['/api/v1/osm/distances',{method:'POST',headers:{'Content-Type':'application/json'},data:'{'},400,'invalid_request'],
    ['/api/v1/osm/distances',{method:'POST',data:{points:[[0,91],[0,0]]}},400,'invalid_distance_request'],
    ['/api/v1/osm/points/add',{method:'OPTIONS',headers:{Origin:'https://untrusted.example','Access-Control-Request-Method':'POST'}},403,'forbidden'],
    ['/api/v1/missing',{method:'GET'},404,'not_found'],
    ['/api/v1/osm/points;bad',{method:'GET'},400,'invalid_request'],
  ]){
    const response=await context.request.fetch(baseURL+path,options);
    assert.equal(response.status(),status);
    const body=await response.json();
    assert.deepEqual(Object.keys(body).sort(),['code','message']);
    assert.equal(body.code,code);assert.equal(typeof body.message,'string');
  }
});

test('distance and layer failures display API messages and can be retried', async t => {
  const {page} = await editor(browser,t);
  const scriptErrors=[];page.on('pageerror',error=>scriptErrors.push(error.message));
  await page.route('**/api/v1/osm/roads',route=>route.fulfill({status:500,
    json:{code:'internal_error',message:'Дороги временно недоступны'}}));
  const roadNotice=page.waitForEvent('dialog');
  await page.locator('.roads-control').check();
  assert.equal((await roadNotice).message(),'Дороги временно недоступны');
  await page.unroute('**/api/v1/osm/roads');
  await page.locator('.roads-control').uncheck();await page.locator('.roads-control').check();
  await page.waitForFunction(()=>roadsMarkers.getLayers().length===2);
  await page.locator('.points-control').check();
  await page.waitForFunction(()=>pointsMarkers.getLayers().length>=3);
  await page.locator('.points-distance > input').check();
  await page.evaluate(()=>{for(const group of pointsMarkers.getLayers().filter(g=>g.pointId.startsWith('osm:')).slice(0,2))group.getLayers()[0].fire('click');});
  await page.route('**/api/v1/osm/distances',route=>route.fulfill({status:400,
    json:{code:'invalid_distance_request',message:'Проверьте координаты выбранных точек'}}));
  const distanceNotice=page.waitForEvent('dialog');
  await page.locator('.points-distance-calculate-control').click();
  assert.equal((await distanceNotice).message(),'Проверьте координаты выбранных точек');
  assert.equal(await page.evaluate(()=>selectedMarkers.length),2);
  await page.unroute('**/api/v1/osm/distances');
  const calculated=page.waitForResponse(r=>r.url().endsWith('/distances')&&r.status()===200);
  await page.locator('.points-distance-calculate-control').click();await calculated;
  await page.waitForFunction(()=>distanceLayerGroup.getLayers().length===1);
  assert.deepEqual(scriptErrors,[]);
});

test('deleting a point removed by another client reports actual404', async t => {
  const {context,page} = await editor(browser,t);
  const csrf=await(await context.request.get(baseURL+'/api/v1/session')).json();
  const headers={[csrf.csrf_header]:csrf.csrf_token};
  const added=await context.request.post(baseURL+'/api/v1/osm/points/add',
    {headers,data:[{name:'stale-point-'+randomUUID(),lat:52.34,lng:35.35}]});
  assert.equal(added.status(),201);const id=(await added.json()).points[0].id;
  await page.locator('.points-control').check();
  await page.waitForFunction(id=>pointsMarkers.getLayers().some(g=>g.pointId===id),id);
  await page.evaluate(id=>pointsMarkers.getLayers().find(g=>g.pointId===id).getLayers()[0].fire('click'),id);
  assert.equal((await context.request.delete(baseURL+'/api/v1/osm/points/'+id,{headers})).status(),204);
  const notice=page.waitForEvent('dialog');
  const response=page.waitForResponse(r=>r.request().method()==='DELETE'&&r.status()===404);
  await page.locator('.delete-points-control').click();
  assert.equal((await response).status(),404);
  assert.match((await notice).message(),/не найдена/);
  await page.waitForFunction(()=>!pendingPointWrites.delete);
  assert.equal(await page.locator('.delete-points-control').isEnabled(),true);
});
