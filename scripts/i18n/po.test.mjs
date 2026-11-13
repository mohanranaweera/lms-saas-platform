// I18N-01: PO round trip keeps context, comments, flags, previous source, plurals and placeholders.
import { test } from "node:test";
import assert from "node:assert/strict";
import { parsePo, serializePo, writeMo } from "./po.mjs";

const SAMPLE = String.raw`msgid ""
msgstr "Language: si-LK\n"

# reviewer note
#. Shown above the list
#: i18n/source/teacher.json
#, icu-format, fuzzy
#| msgid "{count, plural, one {# class} other {# classes}}"
msgctxt "teacher.courses.count"
msgid "{count, plural, one {# course} other {# courses}}"
msgstr "{count, plural, one {පාඨමාලා #} other {පාඨමාලා #ක්}}"

#, icu-format
msgctxt "common.greeting"
msgid "Hello {name}, \"quoted\"\n"
msgstr "ආයුබෝවන් {name}, \"quoted\"\n"

#~ msgctxt "old.key"
#~ msgid "Old"
#~ msgstr "පැරණි"
`;

test("PO parse/serialize round trip is lossless for supported structures", () => {
  const po = parsePo(SAMPLE);
  assert.equal(po.entries.length, 3);
  const [count, greeting, old] = po.entries;
  assert.equal(count.msgctxt, "teacher.courses.count");
  assert.deepEqual(count.flags, ["icu-format", "fuzzy"]);
  assert.equal(count.previous, "{count, plural, one {# class} other {# classes}}");
  assert.deepEqual(count.comments, ["reviewer note"]);
  assert.equal(greeting.msgstr, 'ආයුබෝවන් {name}, "quoted"\n');
  assert.equal(old.obsolete, true);
  const again = parsePo(serializePo(po));
  assert.deepEqual(again, po);
  assert.equal(serializePo(again), serializePo(po));
});

test("multi-line PO strings are joined", () => {
  const po = parsePo('msgctxt "a.b"\nmsgid ""\n"Hello "\n"{name}"\nmsgstr ""\n"Hi {name}"\n');
  assert.equal(po.entries[0].msgid, "Hello {name}");
  assert.equal(po.entries[0].msgstr, "Hi {name}");
});

test("msgid_plural is surfaced so callers can reject it", () => {
  const po = parsePo('msgctxt "a.b"\nmsgid "one"\nmsgid_plural "many"\nmsgstr[0] "x"\nmsgstr[1] "y"\n');
  assert.notEqual(po.entries[0].msgid_plural, undefined);
});

test("MO export skips fuzzy entries and keys by context", () => {
  const mo = writeMo(parsePo(SAMPLE), "si-LK");
  assert.equal(mo.readUInt32LE(0), 0x950412de);
  assert.equal(mo.readUInt32LE(8), 2); // header + common.greeting (fuzzy and obsolete skipped)
  assert.ok(mo.includes(Buffer.from("common.greeting\u0004Hello", "utf8")));
});
