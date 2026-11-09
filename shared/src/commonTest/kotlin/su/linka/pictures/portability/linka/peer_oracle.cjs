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
const temporary = path.join(__dirname, ".p05-synthetic-oracle.linka");
let owned = false;
function openFixture(hex) {
  assert.match(hex, /^[0-9a-f]+$/);
  if (owned) { fs.unlinkSync(temporary); owned = false; }
  fs.writeFileSync(temporary, Buffer.from(hex, "hex"), { flag: "wx" });
  owned = true;
  return openLinkaZip(temporary);
}
try {
  const zip = openFixture(fixtures.writer);
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
  const sparseZip = openFixture(fixtures.sparse);
  const sparseRaw = JSON.parse(sparseZip.readAsText("config.json"));
  assert.equal(sparseRaw.custom, "root metadata");
  assert.equal(sparseRaw.pages[0].custom, "page metadata");
  assert.equal(sparseRaw.pages[0].cards[0].custom, "card metadata");
  assert.equal(sparseRaw.pages[0].cards.length, 3);
  assert(sparseRaw.pages[0].cards.every(c => c.width === 1 && c.height === 1));
  const sparse = normalizeConfigFile(sparseRaw);
  assert.equal(sparse.version, "3.0");
  assert.equal(sparse.pages.length, 1);
  assert.equal(sparse.pages[0].mode, "standard");
  assert.equal(sparse.pages[0].rows, 2);
  assert.equal(sparse.pages[0].columns, 2);
  const placements = getCardGridPlacements(sparse.pages[0]);
  const content = placements.filter(p => p.card.cardType === 0);
  assert.equal(content.length, 3);
  assert.equal(content.map(p => p.card.title).join(","), "wide,covered,last");
  assert.equal(content.map(p => p.index).join(","), "0,1,2");
  assert(content.every(p => !p.covered && p.width === 1 && p.height === 1));
  const editedZip = openFixture(fixtures.edited);
  const editedRaw = JSON.parse(editedZip.readAsText("config.json"));
  assert.equal(editedRaw.pages[0].cards[2].cardType, 2);
  assert.equal(editedRaw.pages[0].cards[1].custom, "card metadata");
  const edited = normalizeConfigFile(editedRaw);
  assert.equal(edited.version, "3.0");
  assert.equal(edited.pages.length, 1);
  assert.equal(edited.pages[0].mode, "standard");
  assert.equal(edited.pages[0].rows, 1);
  assert.equal(edited.pages[0].columns, 4);
  const moved = getCardGridPlacements(edited.pages[0]).filter(p => p.card.cardType === 0);
  assert.equal(moved.length, 3);
  assert.equal(moved.map(p => p.card.title).join(","), "last,wide,covered");
  assert.equal(moved.map(p => p.index).join(","), "0,1,3");
  assert(moved.every(p => !p.covered && p.width === 1 && p.height === 1));
  assert.equal(moved[1].card.audioText, "editor text");
  assert.equal(moved[1].card.audioVoice, "voice");
  assert.equal(editedZip.readFile(moved[1].card.audioPath).toString("hex"), "030405");
  const holesZip = openFixture(fixtures.holes);
  const holesRaw = JSON.parse(holesZip.readAsText("config.json"));
  assert.equal(holesRaw.pages[0].cards.length, 7);
  for (const position of [0, 1, 2, 4, 5]) assert.equal(holesRaw.pages[0].cards[position].cardType, 2);
  assert.equal(holesRaw.pages[0].cards[6].custom, "keep empty metadata");
  const holes = normalizeConfigFile(holesRaw);
  assert.equal(holes.version, "3.0");
  assert.equal(holes.pages.length, 1);
  assert.equal(holes.pages[0].mode, "standard");
  assert.equal(holes.pages[0].rows, 64);
  assert.equal(holes.pages[0].columns, 64);
  assert.equal(holes.pages[0].cards[6].custom, "keep empty metadata");
  const visible = getCardGridPlacements(holes.pages[0]).filter(p => p.card.cardType === 0);
  assert.equal(visible.length, 1);
  assert.equal(visible[0].index, 3);
  assert.equal(visible[0].card.title, "at three");
  assert(visible.every(p => !p.covered && p.width === 1 && p.height === 1));
  assert.equal(status(), before);
  console.log("PASS peer=" + expected + " node=" + process.version + " legacy2-flat + Kotlin-writer-ZIP3 + uniform1x1 import/edit/sparse visibility + compact padding/metadata (not runtime/media decoding)");
} finally {
  if (owned) fs.unlinkSync(temporary);
}
