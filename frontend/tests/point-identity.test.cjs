const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const test = require("node:test");

function editor() {
  const nodes = new Map();
  const events = {};
  function node(tag = "div") {
    return {
      tag, children: [], dataset: {}, style: {}, handlers: {},
      classList: { add() {}, remove() {} },
      addEventListener(name, handler) { this.handlers[name] = handler; },
      appendChild(child) { child.parent = this; this.children.push(child); },
      remove() { this.removed = true; },
      closest(selector) { return selector.startsWith("button") ? this : this.parent; },
    };
  }
  const context = vm.createContext({
    document: {
      querySelector(selector) { if (!nodes.has(selector)) nodes.set(selector, node()); return nodes.get(selector); },
      createElement: node,
    },
    // Reproduce the old implementation's collisions deterministically.
    Math: { random: () => 0.5, floor: Math.floor },
    map: { on(name, handler) { events[name] = handler; } },
    L: { circleMarker() { return {
      addTo() { return this; }, bindPopup() { return this; }, openPopup() { return this; },
      remove() { this.removed = true; },
    }; } },
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, "../add-point.js"), "utf8"), context);
  nodes.get(".add-point-control").handlers.click({});
  return { context, nodes, add() { events.click({ latlng: { lat: 52, lng: 35 } }); } };
}

test("draft IDs stay distinct beyond the old 1000-value range", () => {
  const app = editor();
  for (let i = 0; i < 2000; i++) app.add();
  const ids = vm.runInContext("newMarkers.map(marker => marker.id)", app.context);
  assert.equal(new Set(ids).size, 2000);
  assert.ok(ids.every(id => typeof id === "string"));
});

test("deleting a draft by its dataset ID removes only that draft", () => {
  const app = editor();
  app.add();
  app.add();
  const list = app.nodes.get(".add-point-points-container");
  const deleteButton = list.children[1].children[2];
  // Browser DOMStringMap always exposes string values.
  deleteButton.dataset.id = String(deleteButton.dataset.id);
  list.handlers.click({ target: deleteButton });
  assert.equal(vm.runInContext("newMarkers.length", app.context), 1);
  assert.equal(vm.runInContext("newMarkers[0].marker.removed === true", app.context), false);
  assert.equal(list.children[1].removed, true);
});
