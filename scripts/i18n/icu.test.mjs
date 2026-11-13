// I18N-01: the TypeScript formatter against the shared vectors (node --test scripts/i18n).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { formatMessage, validateMessage } from "../../frontend/src/i18n/icu.ts";

const vectors = JSON.parse(readFileSync(new URL("../../i18n/spec/format-vectors.json", import.meta.url), "utf8"));

for (const [i, v] of vectors.format.entries()) {
  test(`format #${i}: ${v.message}`, () => {
    assert.equal(formatMessage(v.message, v.locale, v.params), v.expected);
  });
}

for (const [i, v] of vectors.validate.entries()) {
  test(`validate #${i}: ${v.message}`, () => {
    assert.deepEqual(validateMessage(v.message, v.locale, v.source), v.problems);
  });
}
