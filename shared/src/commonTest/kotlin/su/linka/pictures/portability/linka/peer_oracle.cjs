// Read-only peer source oracle. The only temporary ZIP is synthetic, inside this test directory.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { createRequire } = require("node:module");
const { execFileSync } = require("node:child_process");

const peer = "/Volumes/data/linka/linka.looks-electron";
const expected = "c540646e1bf06d5f4ea76d6db6b2ab1b82bfe89b";
assert.equal(execFileSync("git", ["rev-parse", "HEAD"], { cwd: peer, encoding: "utf8" }).trim(), expected);
const status = () => execFileSync("git", ["status", "--porcelain=v1", "--untracked-files=all"], { cwd: peer, encoding: "utf8" });
const before = status();
const peerRequire = createRequire(path.join(peer, "package.json"));
const ts = peerRequire("typescript");
function load(relative) {
  const filename = path.join(peer, relative);
  const input = fs.readFileSync(filename, "utf8");
  const output = ts.transpileModule(input, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText;
  const module = { exports: {} };
  vm.runInNewContext("(function(require,module,exports){" + output + "\n})", { Buffer }, { filename })(peerRequire, module, module.exports);
  return module.exports;
}
const { normalizeConfigFile, getCardGridPlacements } = load("src/common/interfaces/ConfigFile.ts");
const { openLinkaZip } = load("src/common/utils/linkaZip.ts");
const legacy = { version: "2.0", rows: "1", columns: "1", withoutSpace: false,
  cards: [{ id: 9, cardType: 0, title: "a" }, { id: 9, cardType: 0, title: "b" }] };
const normalized = normalizeConfigFile(legacy);
assert.equal(normalized.version, "3.0");
assert.equal(normalized.pages.length, 2);
assert.equal(normalized.pages.map(p => p.cards[0].title).join(","), "a,b");
assert(normalized.pages.every(p => p.mode === "standard" && p.rows === 1 && p.columns === 1));

const fixtures = JSON.parse(fs.readFileSync(0, "utf8"));
const hex = fixtures.writer;
assert.match(hex, /^[0-9a-f]+$/);
const temporary = path.join(__dirname, ".p05-synthetic-oracle.linka");
let owned = false;
try {
  fs.writeFileSync(temporary, Buffer.from(hex, "hex"), { flag: "wx" });
  owned = true;
  const zip = openLinkaZip(temporary);
  const raw = JSON.parse(zip.readAsText("config.json"));
  const result = normalizeConfigFile(raw);
  assert.equal(raw.version, "3.0");
  assert.equal(result.version, "3.0");
  assert.equal(result.withoutSpace, true);
  assert.equal(result.directSet, true);
  assert.equal(result.description, "description");
  assert.equal(result.pages.length, 1);
  assert.equal(result.pages[0].mode, "standard");
  assert.equal(result.pages[0].rows, 1);
  assert.equal(result.pages[0].columns, 2);
  const cards = result.pages[0].cards;
  assert.equal(typeof result.pages[0].id, "string");
  assert.equal(typeof cards[0].id, "string");
  assert.notEqual(cards[0].id, cards[1].id);
  assert.equal(cards[0].title, "literal title");
  assert.equal(cards[0].audioText, "editor text, not spoken title");
  assert.equal(cards[0].audioVoice, "voice");
  assert.equal(cards[1].title, "no recording");
  assert.equal(cards[1].audioPath, undefined);
  assert.equal(zip.readFile(cards[0].imagePath).toString("hex"), "0102");
  assert.equal(zip.readFile(cards[0].audioPath).toString("hex"), "030405");
  fs.unlinkSync(temporary);
  owned = false;
  assert.match(fixtures.sparse, /^[0-9a-f]+$/);
  fs.writeFileSync(temporary, Buffer.from(fixtures.sparse, "hex"), { flag: "wx" });
  owned = true;
  const sparseZip = openLinkaZip(temporary);
  const sparse = normalizeConfigFile(JSON.parse(sparseZip.readAsText("config.json")));
  const placements = getCardGridPlacements(sparse.pages[0]);
  const content = placements.filter(p => p.card.cardType === 0);
  assert.equal(content.map(p => p.card.title).join(","), "wide,covered,last");
  assert.equal(content.map(p => p.index).join(","), "0,2,3");
  assert(content.every(p => !p.covered));
  assert.equal(status(), before);
  console.log("PASS peer=" + expected + " node=" + process.version + " legacy2-flat + Kotlin-writer-ZIP3 reader/normalizer + sparse/span visibility (not runtime/media decoding)");
} finally {
  if (owned) fs.unlinkSync(temporary);
}
