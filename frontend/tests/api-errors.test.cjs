const test = require("node:test");
const assert = require("node:assert/strict");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");

function client() {
  const context = vm.createContext({});
  vm.runInContext(
    fs.readFileSync(path.join(__dirname, "../api-errors.js"), "utf8"),
    context,
  );
  return context.apiErrorMessage;
}
test("uses a typed API error message without interpreting markup", async () => {
  const message = "<b>Причина</b>";
  assert.equal(
    await client()(
      { json: async () => ({ code: "invalid_request", message }) },
      "fallback",
    ),
    message,
  );
});
test("does not display proxy HTML, debug objects or incompatible error bodies", async () => {
  const parse = client();
  for (const body of [
    null,
    { error: "legacy", trace: "private" },
    { code: "internal_error", message: { sql: "private" } },
    { code: "invalid_request", message: "" },
  ]) {
    assert.equal(
      await parse({ json: async () => body }, "fallback"),
      "fallback",
    );
  }
  assert.equal(
    await parse(
      {
        json: async () => {
          throw new SyntaxError("HTML instead of JSON");
        },
      },
      "fallback",
    ),
    "fallback",
  );
});
