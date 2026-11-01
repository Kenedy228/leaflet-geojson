const test = require("node:test");
const assert = require("node:assert/strict");
const { chromium } = require("playwright");
const { randomUUID } = require("node:crypto");
const {
  editor,
  baseURL,
  layerURL,
  until,
  assertEventually,
  points,
  drafts,
  activate,
  gate,
} = require("./support.cjs");

let browser;
test.before(async () => {
  browser = await chromium.launch({ headless: true });
});
test.after(async () => {
  await browser?.close();
});

function spatialQuery(request) {
  const url = new URL(request.url());
  assert.deepEqual([...url.searchParams.keys()].sort(), ["bbox", "zoom"]);
  const bbox = url.searchParams.get("bbox").split(",").map(Number);
  const zoom = Number(url.searchParams.get("zoom"));
  assert.equal(bbox.length, 4);
  assert.ok(bbox.every(Number.isFinite));
  assert.ok(bbox[0] >= -180 && bbox[0] <= 180);
  assert.ok(bbox[2] >= -180 && bbox[2] <= 180 && bbox[0] !== bbox[2]);
  assert.ok(bbox[1] >= -90 && bbox[3] <= 90 && bbox[1] < bbox[3]);
  assert.ok(Number.isInteger(zoom) && zoom >= 0 && zoom <= 22);
  return { bbox, zoom };
}

function nextLayerRequest(page, layer) {
  return page.waitForRequest(
    (request) =>
      request.method() === "GET" && layerURL(layer).test(request.url()),
  );
}

async function panRight(page) {
  await page.locator("#map").focus();
  await page.keyboard.press("ArrowRight");
}

function paths(page, layer) {
  return page.locator(
    layer === "points"
      ? "[data-point-id]"
      : `[data-layer="${layer}"].leaflet-interactive`,
  );
}

for (const layer of ["points", "roads", "polygons"]) {
  test(`${layer}: real keyboard pan and zoom send the displayed viewport and zoom`, async (t) => {
    const { page } = await editor(browser, t);
    const initialRequest = nextLayerRequest(page, layer);
    await page.locator(`.${layer}-control`).check();
    const initial = spatialQuery(await initialRequest);
    await paths(page, layer).first().waitFor();
    const mapWidth = await page
      .locator("#map")
      .evaluate((map) => map.clientWidth);
    // Web Mercator spans 360 longitude degrees over 256 * 2^zoom CSS pixels.
    const expectedWidth = (mapWidth * 360) / (256 * 2 ** initial.zoom);
    assert.ok(
      Math.abs(initial.bbox[2] - initial.bbox[0] - expectedWidth) < 1e-6,
    );
    assert.ok(initial.bbox[0] < 35.35 && initial.bbox[2] > 35.35);
    assert.ok(initial.bbox[1] < 52.339 && initial.bbox[3] > 52.339);

    const pannedRequest = nextLayerRequest(page, layer);
    await panRight(page);
    const panned = spatialQuery(await pannedRequest);
    assert.equal(panned.zoom, initial.zoom);
    assert.ok(panned.bbox[0] > initial.bbox[0]);
    assert.ok(panned.bbox[2] > initial.bbox[2]);
    assert.ok(Math.abs(panned.bbox[2] - panned.bbox[0] - expectedWidth) < 1e-6);
    await paths(page, layer).first().waitFor();

    const zoomedRequest = nextLayerRequest(page, layer);
    await page.locator(".leaflet-control-zoom-in").click();
    const zoomed = spatialQuery(await zoomedRequest);
    assert.equal(zoomed.zoom, panned.zoom + 1);
    assert.ok(
      Math.abs(zoomed.bbox[2] - zoomed.bbox[0] - expectedWidth / 2) < 1e-6,
    );
    assert.ok(zoomed.bbox[1] > panned.bbox[1]);
    assert.ok(zoomed.bbox[3] < panned.bbox[3]);
    await paths(page, layer).first().waitFor();
  });

  test(`${layer}: an older viewport response arriving last cannot replace the current geometry`, async (t) => {
    const { page } = await editor(browser, t);
    await page.locator(`.${layer}-control`).check();
    await paths(page, layer).first().waitFor();
    const delayed = gate();
    let calls = 0;
    const requested = [];
    await page.route(layerURL(layer), async (route) => {
      const call = ++calls;
      requested.push(spatialQuery(route.request()));
      const response = await route.fetch();
      const data = await response.json();
      data.forEach((item) => {
        item.name = call === 1 ? "Старый вид карты" : "Текущий вид карты";
      });
      if (call === 1) {
        delayed.arrived();
        await delayed.wait;
      }
      await route.fulfill({ response, json: data }).catch(() => {});
    });
    try {
      await panRight(page);
      await delayed.arrival;
      assert.equal(await paths(page, layer).count(), 0);
      await panRight(page);
      await paths(page, layer).first().waitFor();
      delayed.release();
      await page.waitForLoadState("networkidle");
      assert.ok(requested.length >= 2);
      assert.ok(requested[1].bbox[0] > requested[0].bbox[0]);
      await activate(paths(page, layer).first());
      await assertEventually(async () => {
        const popup = await page
          .locator(".leaflet-popup-content")
          .last()
          .textContent();
        assert.match(popup, /Текущий вид карты/);
        assert.doesNotMatch(popup, /Старый вид карты/);
      });
    } finally {
      delayed.release();
    }
  });

  test(`${layer}: HTTP 413 explains the spatial limit and zooming retries the selected layer`, async (t) => {
    const { page } = await editor(browser, t);
    await page.route(layerURL(layer), (route) =>
      route.fulfill({
        status: 413,
        json: {
          code: "spatial_limit_exceeded",
          message: "Слишком много объектов. Увеличьте масштаб карты.",
        },
      }),
    );
    const failedRequest = nextLayerRequest(page, layer);
    await page.locator(`.${layer}-control`).check();
    const failed = spatialQuery(await failedRequest);
    const status = page.locator(`.layer-status[data-layer="${layer}"]`);
    await assertEventually(async () =>
      assert.match(await status.textContent(), /Увеличьте масштаб карты/),
    );
    assert.equal(await paths(page, layer).count(), 0);
    assert.equal(await page.locator(`.${layer}-control`).isChecked(), true);
    await page.unroute(layerURL(layer));
    const retriedRequest = nextLayerRequest(page, layer);
    await page.locator(".leaflet-control-zoom-in").click();
    const retried = spatialQuery(await retriedRequest);
    assert.equal(retried.zoom, failed.zoom + 1);
    assert.ok(
      retried.bbox[2] - retried.bbox[0] < failed.bbox[2] - failed.bbox[0],
    );
    await paths(page, layer).first().waitFor();
    await assertEventually(async () =>
      assert.doesNotMatch(await status.textContent(), /Слишком много объектов/),
    );
  });
}

test("viewport reload keeps unsaved drafts and the calculated ordered selection snapshot", async (t) => {
  const { page } = await editor(browser, t);
  await points(page);
  await drafts(page, ["Черновик при перемещении карты"]);
  await page.locator(".points-distance-control").check();
  for (let i = 0; i < 2; i++)
    await activate(page.locator('[data-point-id^="osm:"]').nth(i));
  const selected = await page
    .locator(".points-distance-selected-list li")
    .allTextContents();
  await page.locator(".points-distance-calculate-control").click();
  await until(() => page.locator("[data-distance-line]").count(), 1);
  const refresh = nextLayerRequest(page, "points");
  await panRight(page);
  await refresh;
  await paths(page, "points").first().waitFor();
  assert.deepEqual(
    await page.locator(".points-distance-selected-list li").allTextContents(),
    selected,
  );
  assert.equal(
    await page.locator(".change-add-point-name input").inputValue(),
    "Черновик при перемещении карты",
  );
  assert.equal(
    await page.locator("[data-draft-id].leaflet-interactive").count(),
    1,
  );
  assert.equal(await page.locator("[data-distance-line]").count(), 1);
});

test("crossing the dateline renders canonical points, drafts, GeoJSON and distance in the visible world copy", async (t) => {
  const { context, page } = await editor(browser, t);
  const session = await (
    await context.request.get(baseURL + "/api/v1/session")
  ).json();
  const headers = { [session.csrf_header]: session.csrf_token };
  const name = "Точка через антимеридиан " + randomUUID();
  const created = await context.request.post(
    baseURL + "/api/v1/osm/points/add",
    {
      headers,
      data: [
        { name, lng: -179, lat: 52.339 },
        { name: name + " запад", lng: 179, lat: 52.5 },
      ],
    },
  );
  assert.equal(created.status(), 201);
  const ids = (await created.json()).points.map((point) => point.id);
  try {
    await points(page);
    let query;
    for (let zoom = 13; zoom > 4; zoom--) {
      const requested = nextLayerRequest(page, "points");
      await page.locator(".leaflet-control-zoom-out").click();
      query = spatialQuery(await requested);
      assert.equal(query.zoom, zoom - 1);
    }
    // Leaflet's public Shift+Arrow shortcut pans three keyboard steps (240px).
    // Follow actual emitted bounds until the map center passes +180 longitude.
    for (let step = 0; step < 12; step++) {
      const requested = nextLayerRequest(page, "points");
      await page.locator("#map").focus();
      await page.keyboard.press("Shift+ArrowRight");
      query = spatialQuery(await requested);
      if (
        query.bbox[0] > query.bbox[2] &&
        (query.bbox[0] + query.bbox[2] + 360) / 2 > 180
      )
        break;
    }
    assert.ok(
      query.bbox[0] > query.bbox[2],
      "The requested viewport crosses the dateline",
    );
    assert.ok(
      (query.bbox[0] + query.bbox[2] + 360) / 2 > 180,
      "The displayed world copy has crossed +180",
    );

    async function insideMap(locator) {
      await assertEventually(async () => {
        const map = await page.locator("#map").boundingBox();
        const object = await locator.boundingBox();
        assert.ok(map && object && object.width > 0 && object.height > 0);
        assert.ok(
          object.x >= map.x && object.x + object.width <= map.x + map.width,
        );
        assert.ok(
          object.y >= map.y && object.y + object.height <= map.y + map.height,
        );
      });
    }
    const marker = page.locator(`[data-point-id="${ids[0]}"]`);
    await marker.waitFor();
    await insideMap(marker);
    await activate(marker);
    assert.match(
      await page.locator(".leaflet-popup-content").last().textContent(),
      /Точка через антимеридиан/,
    );

    for (const [layer, geojson] of [
      [
        "roads",
        {
          type: "LineString",
          coordinates: [
            [-179.2, 52.2],
            [-178.8, 52.4],
          ],
        },
      ],
      [
        "polygons",
        {
          type: "Polygon",
          coordinates: [
            [
              [-179.2, 52.2],
              [-178.8, 52.2],
              [-178.8, 52.4],
              [-179.2, 52.4],
              [-179.2, 52.2],
            ],
          ],
        },
      ],
    ]) {
      await page.route(layerURL(layer), (route) =>
        route.fulfill({
          json: [
            { id: "osm:900001", name: "Объект через антимеридиан", geojson },
          ],
        }),
      );
      await page.locator(`.${layer}-control`).check();
      await paths(page, layer).first().waitFor();
      await insideMap(paths(page, layer).first());
      await activate(paths(page, layer).first());
      assert.match(
        await page.locator(".leaflet-popup-content").last().textContent(),
        /Объект через антимеридиан/,
      );
    }

    await page.locator(".add-point-control input").check();
    await page.getByLabel("Широта", { exact: true }).fill("52.339");
    await page.getByLabel("Долгота", { exact: true }).fill("-179");
    await page.getByRole("button", { name: "Добавить по координатам" }).click();
    await insideMap(page.locator("[data-draft-id].leaflet-interactive"));
    await page.locator(".points-distance-control").check();
    for (const id of ids)
      await activate(page.locator(`[data-point-id="${id}"]`));
    const calculated = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === "/api/v1/osm/distances" &&
        response.status() === 200,
    );
    await page.locator(".points-distance-calculate-control").click();
    const distance = await calculated;
    assert.deepEqual(distance.request().postDataJSON().points, [
      [-179, 52.339],
      [179, 52.5],
    ]);
    const line = page.locator("[data-distance-line]");
    await line.waitFor();
    await insideMap(line);
    const lineBox = await line.boundingBox();
    const mapBox = await page.locator("#map").boundingBox();
    assert.ok(
      lineBox.width < mapBox.width / 4,
      "The segment crosses two degrees, not almost the entire world",
    );
    const persisted = await (
      await context.request.get(baseURL + "/api/v1/osm/points")
    ).json();
    assert.deepEqual(
      JSON.parse(persisted.find((point) => point.id === ids[0]).geojson)
        .coordinates,
      [-179, 52.339],
    );
  } finally {
    for (const id of ids)
      await context.request.delete(baseURL + "/api/v1/osm/points/" + id, {
        headers,
      });
  }
});
