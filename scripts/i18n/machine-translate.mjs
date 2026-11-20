#!/usr/bin/env node
// I18N-01: fill untranslated PO entries with Google Cloud Translation (Basic, v2) output,
// flagged `fuzzy` (needs review) so nothing machine-translated ships until a reviewer
// clears the flag. See docs/operations/i18n-runbook.md "Machine translation".
//
//   GOOGLE_TRANSLATE_API_KEY=... node scripts/i18n/machine-translate.mjs si-LK [--dry-run] [--limit N]
//
// Only empty, non-obsolete entries are touched; existing translations are never replaced.
// Placeholders, `#` and plural structure are protected: only literal text is sent, with
// placeholders wrapped in <span class="notranslate">, and each plural branch is sent on
// its own. Every result is re-validated against the English source; failures stay empty.
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { languageOf, parseMessage, validateMessage } from "../../frontend/src/i18n/icu.ts";
import { parsePo, serializePo } from "./po.mjs";

export const MT_COMMENT = "machine translation (Google Cloud Translation), needs human review";
const API_URL = "https://translation.googleapis.com/language/translate/v2";
const BATCH = 100; // API limit is 128 strings per request

const escapeHtml = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

export function decodeHtml(s) {
  return s
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&");
}

/** ICU-escapes literal text: apostrophes doubled, braces (and `#` in plurals) quoted. */
function escapeIcu(text, inPlural) {
  return text.replace(/'/g, "''").replace(inPlural ? /[{}#]/g : /[{}]/g, (c) => `'${c}'`);
}

const KEEP = (token) => `<span class="notranslate">${token}</span>`;
const SPAN = /<span class=["']?notranslate["']?\s*>\s*(.*?)\s*<\/span>/gi;

/** Splits nodes into translatable units (HTML strings) and plural structure. */
function plan(nodes, inPlural, units) {
  const parts = [];
  let html = "";
  let hasText = false;
  const flush = () => {
    if (html !== "") {
      parts.push({ kind: "unit", index: hasText ? units.push(html) - 1 : -1, html, inPlural });
    }
    html = "";
    hasText = false;
  };
  for (const node of nodes) {
    if (node.type === "text") {
      html += escapeHtml(node.value);
      if (/\p{L}/u.test(node.value)) hasText = true;
    } else if (node.type === "arg") {
      html += KEEP(`{${node.name}}`);
    } else if (node.type === "pound") {
      html += KEEP("#");
    } else {
      flush();
      const options = {};
      for (const [selector, branch] of Object.entries(node.options)) {
        options[selector] = plan(branch, true, units);
      }
      parts.push({ kind: "plural", name: node.name, options });
    }
  }
  flush();
  return parts;
}

function renderUnit(html, inPlural) {
  let out = "";
  let last = 0;
  for (const m of html.matchAll(SPAN)) {
    out += escapeIcu(decodeHtml(html.slice(last, m.index)), inPlural);
    const token = decodeHtml(m[1]).trim();
    out += token === "#" ? "#" : /^\{[A-Za-z][A-Za-z0-9_]*\}$/.test(token) ? token : escapeIcu(token, inPlural);
    last = m.index + m[0].length;
  }
  return out + escapeIcu(decodeHtml(html.slice(last)), inPlural);
}

function render(parts, translated) {
  let out = "";
  for (const part of parts) {
    if (part.kind === "unit") {
      out += renderUnit(part.index >= 0 ? translated[part.index] : part.html, part.inPlural);
    } else {
      const branches = Object.entries(part.options).map(([sel, sub]) => `${sel} {${render(sub, translated)}}`);
      out += `{${part.name}, plural, ${branches.join(" ")}}`;
    }
  }
  return out;
}

/**
 * Machine-translates English ICU sources. `translateBatch(htmlStrings) -> Promise<string[]>`.
 * Returns Map<key, { value } | { problems }>.
 */
export async function machineTranslate(entries, locale, translateBatch) {
  const units = [];
  const plans = entries.map(({ key, source }) => ({ key, source, parts: plan(parseMessage(source), false, units) }));
  const translated = [];
  for (let i = 0; i < units.length; i += BATCH) {
    translated.push(...(await translateBatch(units.slice(i, i + BATCH))));
  }
  const results = new Map();
  for (const { key, source, parts } of plans) {
    const value = render(parts, translated);
    const problems = validateMessage(value, locale, source);
    results.set(key, problems.length || value.trim() === "" ? { problems: problems.length ? problems : ["EMPTY"] } : { value });
  }
  return results;
}

function googleTranslator(apiKey, target) {
  return async (q) => {
    const response = await fetch(API_URL, {
      method: "POST",
      // The key travels in a header, never in the URL, so it cannot leak into logs.
      headers: { "Content-Type": "application/json", "X-goog-api-key": apiKey },
      body: JSON.stringify({ q, source: "en", target, format: "html" }),
    });
    const body = await response.json().catch(() => null);
    if (!response.ok) {
      throw new Error(`Google Translate HTTP ${response.status}: ${body?.error?.message ?? "no details"}`);
    }
    return body.data.translations.map((t) => t.translatedText);
  };
}

async function main() {
  const args = process.argv.slice(2);
  const locale = args.find((a) => !a.startsWith("--"));
  const dryRun = args.includes("--dry-run");
  const limitAt = args.indexOf("--limit");
  const limit = limitAt >= 0 ? Number(args[limitAt + 1]) : Infinity;
  const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
  const file = join(root, "i18n", "po", `${locale}.po`);
  if (!locale) throw new Error("usage: machine-translate.mjs <locale> [--dry-run] [--limit N]");
  const po = parsePo(readFileSync(file, "utf8"));
  const todo = po.entries.filter((e) => !e.obsolete && e.msgstr.trim() === "").slice(0, limit);
  console.log(`i18n-mt: ${todo.length} untranslated ${locale} entries${dryRun ? " (dry run, nothing sent)" : ""}`);
  if (dryRun || todo.length === 0) return;
  const apiKey = process.env.GOOGLE_TRANSLATE_API_KEY;
  if (!apiKey) throw new Error("set GOOGLE_TRANSLATE_API_KEY (Cloud Translation API key) in your own terminal");
  const results = await machineTranslate(
    todo.map((e) => ({ key: e.msgctxt, source: e.msgid })),
    locale,
    googleTranslator(apiKey, languageOf(locale)),
  );
  let filled = 0;
  const rejected = [];
  for (const entry of todo) {
    const result = results.get(entry.msgctxt);
    if (!result.value) {
      rejected.push(`${entry.msgctxt}: ${result.problems.join(", ")}`);
      continue;
    }
    entry.msgstr = result.value;
    if (!entry.flags.includes("fuzzy")) entry.flags.push("fuzzy");
    if (!entry.comments.includes(MT_COMMENT)) entry.comments.push(MT_COMMENT);
    filled++;
  }
  writeFileSync(file, serializePo(po), "utf8");
  console.log(`i18n-mt: filled ${filled} entries as fuzzy (needs review); ${rejected.length} left empty`);
  for (const r of rejected) console.log(`  rejected ${r}`);
  console.log("i18n-mt: next run `node scripts/i18n/catalog.mjs generate`");
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main().catch((error) => {
    console.error(`i18n-mt: ${error.message}`);
    process.exit(1);
  });
}
