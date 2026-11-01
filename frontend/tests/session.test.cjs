const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function app(responses) {
  const calls = [], nodes = new Map();
  const context = vm.createContext({
    serverURL: '/api/v1/osm',
    document: {
      querySelector(selector) {
        if (!nodes.has(selector)) nodes.set(selector, {addEventListener() {}, classList: {add() {}}});
        return nodes.get(selector);
      },
      addEventListener() {},
    },
    fetch: async (url, options) => { calls.push({url, options}); return responses.shift(); },
  });
  vm.runInContext(fs.readFileSync(require('node:path').join(__dirname, '../session.js'), 'utf8'), context);
  return {context, calls, nodes};
}
const session = can_edit => ({ok: true, json: async () => ({editing_enabled: true, can_edit,
  csrf_header: 'X-CSRF-TOKEN', csrf_token: 'fresh-session-token'})});

test('point write obtains current permission and CSRF before sending JSON', async () => {
  const state = app([session(true), {ok:true, status:201}]);
  await vm.runInContext('pointWrite("/points/add", {method:"POST", headers:{"Content-Type":"application/json"}, body:"[]"})', state.context);
  assert.equal(state.calls[1].url, '/api/v1/osm/points/add');
  assert.equal(state.calls[1].options.headers['X-CSRF-TOKEN'], 'fresh-session-token');
  assert.equal(state.calls[1].options.headers['Content-Type'], 'application/json');
  assert.equal(state.calls[1].options.credentials, 'same-origin');
});

test('expired editor permission prevents mutation and disables editing controls', async () => {
  const state = app([session(false)]);
  await assert.rejects(vm.runInContext('pointWrite("/points/id", {method:"DELETE"})', state.context));
  assert.equal(state.calls.length, 1);
  assert.equal(state.nodes.get('.add-point-control input').disabled, true);
  assert.equal(state.nodes.get('.add-point-save').disabled, true);
});

test('server auth rejection refreshes permissions without retrying a mutation', async () => {
  const state = app([session(true), {ok:false, status:403}, session(false)]);
  await assert.rejects(vm.runInContext('pointWrite("/points/id", {method:"DELETE"})', state.context));
  assert.equal(state.calls.filter(call => call.options.method === 'DELETE').length, 1);
  assert.equal(state.nodes.get('.add-point-save').disabled, true);
});

test('permission refresh cannot unlock a pending save or delete', async () => {
  const state = app([session(true)]);
  await vm.runInContext('pendingPointWrites.create=true; pendingPointWrites.delete=true; refreshAccess()', state.context);
  assert.equal(state.nodes.get('.add-point-save').disabled, true);
  assert.equal(state.nodes.get('.delete-points-control').disabled, true);
});
