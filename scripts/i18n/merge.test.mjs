// I18N-01: merge never drops translations; source changes flag entries for review.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mergePo } from "./merge.mjs";
import { parsePo, serializePo } from "./po.mjs";

const reg = (entries) =>
  new Map(entries.map(([key, source]) => [key, { source, context: "ctx", file: "i18n/source/t.json" }]));

const existing = parsePo(String.raw`msgid ""
msgstr ""

#, icu-format
msgctxt "t.same"
msgid "Same"
msgstr "එකම"

#, icu-format
msgctxt "t.changed"
msgid "Old text"
msgstr "පැරණි"

#, icu-format
msgctxt "t.removed"
msgid "Gone"
msgstr "ගියා"
`);

test("source change keeps the translation, marks fuzzy and records the previous source", () => {
  const merged = mergePo(existing, reg([["t.changed", "New text"], ["t.new", "Brand new"], ["t.same", "Same"]]));
  const byKey = Object.fromEntries(merged.entries.map((e) => [e.msgctxt, e]));
  assert.equal(byKey["t.changed"].msgstr, "පැරණි");
  assert.ok(byKey["t.changed"].flags.includes("fuzzy"));
  assert.equal(byKey["t.changed"].previous, "Old text");
  assert.equal(byKey["t.same"].msgstr, "එකම");
  assert.ok(!byKey["t.same"].flags.includes("fuzzy"));
  assert.equal(byKey["t.new"].msgstr, "");
  assert.equal(byKey["t.removed"].obsolete, true);
  assert.equal(byKey["t.removed"].msgstr, "ගියා");
});

test("merge is deterministic and idempotent", () => {
  const r = reg([["t.changed", "New text"], ["t.same", "Same"]]);
  const once = serializePo(mergePo(existing, r));
  const twice = serializePo(mergePo(parsePo(once), r));
  assert.equal(twice, once);
});

test("a removed key that comes back is revived with its translation", () => {
  const gone = mergePo(existing, reg([["t.same", "Same"]]));
  const back = mergePo(parsePo(serializePo(gone)), reg([["t.removed", "Gone"], ["t.same", "Same"]]));
  const entry = back.entries.find((e) => e.msgctxt === "t.removed");
  assert.equal(entry.obsolete, false);
  assert.equal(entry.msgstr, "ගියා");
});
