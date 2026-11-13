// I18N-01: machine translation keeps placeholders, `#` and plural structure intact,
// and only literal text reaches the translator (stubbed here; no network).
import { test } from "node:test";
import assert from "node:assert/strict";
import { machineTranslate } from "./machine-translate.mjs";

// Fake translator: uppercases text outside protected spans, and (like Google) may
// reorder content and re-encode entities.
const fake = async (q) =>
  q.map((html) =>
    html
      .split(/(<span class="notranslate">.*?<\/span>)/)
      .map((piece) => (piece.startsWith("<span") ? piece : piece.toUpperCase()))
      .join("")
      .replace(/'/g, "&#39;"),
  );

test("placeholders and plurals survive and every branch is translated", async () => {
  const results = await machineTranslate(
    [
      { key: "a.hello", source: "Hello {name}, it's {count} o'clock" },
      { key: "a.count", source: "{count, plural, =0 {No courses} one {# course} other {# courses}}" },
      { key: "a.brace", source: "Use '{literal}' and #" },
    ],
    "si-LK",
    fake,
  );
  assert.equal(results.get("a.hello").value, "HELLO {name}, IT''S {count} O''CLOCK");
  assert.equal(results.get("a.count").value, "{count, plural, =0 {NO COURSES} one {# COURSE} other {# COURSES}}");
  assert.equal(results.get("a.brace").value, "USE '{'LITERAL'}' AND #");
});

test("a translator that drops a placeholder is rejected, not written", async () => {
  const dropping = async (q) => q.map(() => "හලෝ");
  const results = await machineTranslate([{ key: "a.hello", source: "Hello {name}" }], "si-LK", dropping);
  assert.deepEqual(results.get("a.hello"), { problems: ["PLACEHOLDER_MISSING:name"] });
});

test("text without letters is not sent to the translator", async () => {
  let sent = 0;
  const counting = async (q) => {
    sent += q.length;
    return q;
  };
  await machineTranslate([{ key: "a.only", source: "{name}" }, { key: "a.dash", source: "—" }], "si-LK", counting);
  assert.equal(sent, 0);
});
