#!/usr/bin/env node
// I18N-01: apply draft translations from a JSON map { "<key>": "<translation>" } to
// i18n/po/<locale>.po. Fills only empty entries, validates each value against the
// English source, and flags every applied entry `fuzzy` with a provenance comment, so
// nothing ships before human review.
//
//   node scripts/i18n/apply-drafts.mjs <locale> <drafts.json> "<provenance comment>"
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { validateMessage } from "../../frontend/src/i18n/icu.ts";
import { parsePo, serializePo } from "./po.mjs";

const [locale, draftsFile, comment] = process.argv.slice(2);
if (!locale || !draftsFile || !comment) {
  console.error('usage: apply-drafts.mjs <locale> <drafts.json> "<provenance comment>"');
  process.exit(1);
}
const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const file = join(root, "i18n", "po", `${locale}.po`);
const po = parsePo(readFileSync(file, "utf8"));
const drafts = JSON.parse(readFileSync(draftsFile, "utf8"));
const known = new Set(po.entries.filter((e) => !e.obsolete).map((e) => e.msgctxt));
let applied = 0;
const kept = [];
const rejected = [];
for (const entry of po.entries) {
  if (entry.obsolete || !(entry.msgctxt in drafts)) continue;
  if (entry.msgstr.trim() !== "") {
    kept.push(entry.msgctxt); // never overwrite existing work
    continue;
  }
  const value = drafts[entry.msgctxt];
  const problems = typeof value === "string" && value.trim() !== "" ? validateMessage(value, locale, entry.msgid) : ["EMPTY"];
  if (problems.length) {
    rejected.push(`${entry.msgctxt}: ${problems.join(", ")}`);
    continue;
  }
  entry.msgstr = value;
  if (!entry.flags.includes("fuzzy")) entry.flags.push("fuzzy");
  if (!entry.comments.includes(comment)) entry.comments.push(comment);
  applied++;
}
writeFileSync(file, serializePo(po), "utf8");
const unknown = Object.keys(drafts).filter((k) => !known.has(k));
const missing = [...known].filter((k) => !(k in drafts));
console.log(`apply-drafts: ${applied} applied as fuzzy, ${kept.length} kept (already translated), ${rejected.length} rejected`);
for (const r of rejected) console.log(`  rejected ${r}`);
if (unknown.length) console.log(`  unknown keys ignored: ${unknown.join(", ")}`);
if (missing.length) console.log(`  no draft for: ${missing.join(", ")}`);
