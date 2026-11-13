#!/usr/bin/env node
// I18N-01 catalog pipeline (docs/architecture/i18n-translation-management.md §3.4).
//
//   node scripts/i18n/catalog.mjs extract   registry + code scan -> i18n/messages.pot, merge into i18n/po/*.po
//   node scripts/i18n/catalog.mjs generate  validated PO -> backend/frontend JSON
//   node scripts/i18n/catalog.mjs check     CI: fail on unregistered keys, invalid or stale catalogs
//   node scripts/i18n/catalog.mjs mo <loc>  optional gettext MO export -> i18n/build/<loc>.mo
//
// Deterministic: sorted keys, no timestamps, single-line PO strings, "\n" line endings.
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { validateMessage } from "../../frontend/src/i18n/icu.ts";
import { mergePo } from "./merge.mjs";
import { parsePo, serializePo, writeMo } from "./po.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const I18N = join(ROOT, "i18n");
const SOURCE_DIR = join(I18N, "source");
const PO_DIR = join(I18N, "po");
const POT_FILE = join(I18N, "messages.pot");
const BACKEND_OUT = join(ROOT, "backend", "src", "main", "resources", "i18n", "platform-catalog.json");
const FRONTEND_OUT = join(ROOT, "frontend", "src", "i18n", "generated", "catalog.json");
const SCAN_ROOTS = [
  { dir: join(ROOT, "frontend", "src"), exts: [".ts", ".tsx"] },
  { dir: join(ROOT, "backend", "src", "main", "java"), exts: [".java"] },
];
// t("ns.key") in React, msg("ns.key") / I18nKeys constants in Java. Dynamic keys are registered explicitly.
const KEY_CALL = /\b(?:t|msg|tKey)\(\s*"([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_]+)+)"/g;
const KEY_SHAPE = /^[a-z][A-Za-z0-9]*(\.[A-Za-z0-9_]+)+$/;

const rel = (p) => relative(ROOT, p).split(sep).join("/");
const stableJson = (value) => JSON.stringify(value, null, 2) + "\n";

function loadLocales() {
  return JSON.parse(readFileSync(join(I18N, "locales.json"), "utf8"));
}

function loadRegistry(errors) {
  const registry = new Map();
  for (const file of readdirSync(SOURCE_DIR).filter((f) => f.endsWith(".json")).sort()) {
    const namespace = file.slice(0, -5);
    const entries = JSON.parse(readFileSync(join(SOURCE_DIR, file), "utf8"));
    for (const [key, entry] of Object.entries(entries)) {
      if (!KEY_SHAPE.test(key) || key.split(".")[0] !== namespace) {
        errors.push(`${file}: key '${key}' must match the namespace '${namespace}.' and the key shape`);
        continue;
      }
      if (typeof entry?.source !== "string" || entry.source.trim() === "") {
        errors.push(`${file}: '${key}' has no English source`);
        continue;
      }
      const problems = validateMessage(entry.source, "en-LK");
      if (problems.length) {
        errors.push(`${file}: '${key}' source is invalid: ${problems.join(", ")}`);
      }
      registry.set(key, {
        source: entry.source,
        context: typeof entry.context === "string" ? entry.context : "",
        namespace,
        file: `i18n/source/${file}`,
      });
    }
  }
  return new Map([...registry.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
}

function walk(dir, exts, out) {
  for (const name of readdirSync(dir)) {
    if (name === "node_modules" || name === "generated" || name.startsWith(".")) continue;
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full, exts, out);
    else if (exts.some((e) => name.endsWith(e))) out.push(full);
  }
  return out;
}

function scanUsedKeys() {
  const used = new Map();
  for (const { dir, exts } of SCAN_ROOTS) {
    if (!existsSync(dir)) continue;
    for (const file of walk(dir, exts, [])) {
      const text = readFileSync(file, "utf8");
      for (const match of text.matchAll(KEY_CALL)) {
        if (!used.has(match[1])) used.set(match[1], rel(file));
      }
    }
  }
  return used;
}

function potEntries(registry) {
  return [...registry.entries()].map(([key, e]) => ({
    msgctxt: key,
    msgid: e.source,
    msgstr: "",
    extracted: e.context ? [e.context] : [],
    references: [e.file],
    flags: ["icu-format"],
    comments: [],
    previous: null,
    obsolete: false,
  }));
}

function poHeader(locale) {
  return [
    "Project-Id-Version: lms-saas-platform",
    `Language: ${locale}`,
    "MIME-Version: 1.0",
    "Content-Type: text/plain; charset=UTF-8",
    "Content-Transfer-Encoding: 8bit",
    "X-Message-Format: icu-subset (docs/architecture/i18n-translation-management.md)",
    "X-Generator: scripts/i18n/catalog.mjs",
    "",
  ].join("\n");
}

function poLocales(locales) {
  return locales.locales.map((l) => l.tag).filter((t) => t !== locales.sourceLocale);
}

/** Computes every output in memory: { path -> content }. */
function build({ requireMerged }) {
  const errors = [];
  const locales = loadLocales();
  const registry = loadRegistry(errors);
  const used = scanUsedKeys();
  for (const [key, file] of used) {
    if (!registry.has(key)) errors.push(`${file}: key '${key}' is not registered in i18n/source/`);
  }
  const outputs = new Map();
  outputs.set(POT_FILE, serializePo({ header: poHeader(""), entries: potEntries(registry) }));

  const translations = {};
  const coverage = {};
  for (const locale of poLocales(locales)) {
    const file = join(PO_DIR, `${locale}.po`);
    const existing = existsSync(file)
      ? parsePo(readFileSync(file, "utf8"))
      : { header: poHeader(locale), entries: [] };
    const merged = mergePo({ ...existing, header: poHeader(locale) }, registry);
    outputs.set(file, serializePo(merged));
    const source = requireMerged ? merged : existing;
    const messages = {};
    const needsReview = {};
    let missing = 0;
    for (const entry of source.entries) {
      if (entry.obsolete) continue;
      const reg = registry.get(entry.msgctxt);
      if (!reg) continue;
      if (entry.msgid_plural !== undefined) {
        errors.push(`${locale}.po: '${entry.msgctxt}' uses msgid_plural; plurals belong inside the ICU message`);
        continue;
      }
      if (entry.msgstr.trim() === "") {
        missing++;
        continue;
      }
      const problems = validateMessage(entry.msgstr, locale, reg.source);
      if (problems.length) {
        errors.push(`${locale}.po: '${entry.msgctxt}' is invalid: ${problems.join(", ")}`);
        continue;
      }
      if (entry.flags.includes("fuzzy") || entry.msgid !== reg.source) needsReview[entry.msgctxt] = entry.msgstr;
      else messages[entry.msgctxt] = entry.msgstr;
    }
    translations[locale] = { messages, needsReview };
    coverage[locale] = {
      total: registry.size,
      translated: Object.keys(messages).length,
      needsReview: Object.keys(needsReview).length,
      missing: registry.size - Object.keys(messages).length - Object.keys(needsReview).length,
    };
  }

  const source = {};
  for (const [key, e] of registry) source[key] = { source: e.source, context: e.context, namespace: e.namespace };
  const version = createHash("sha256")
    .update(JSON.stringify({ source, translations }))
    .digest("hex")
    .slice(0, 12);
  outputs.set(
    BACKEND_OUT,
    stableJson({ version, sourceLocale: locales.sourceLocale, locales: locales.locales, source, translations }),
  );
  const english = {};
  for (const [key, e] of registry) english[key] = e.source;
  outputs.set(
    FRONTEND_OUT,
    stableJson({ version, sourceLocale: locales.sourceLocale, locales: locales.locales, messages: english }),
  );
  return { errors, outputs, coverage, version };
}

function writeOutputs(outputs, filter) {
  for (const [path, content] of outputs) {
    if (!filter(path)) continue;
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, content, "utf8");
  }
}

function fail(errors) {
  for (const e of errors) console.error(`i18n: ${e}`);
  process.exit(1);
}

const [command, arg] = process.argv.slice(2);
const isCatalogFile = (p) => p === POT_FILE || p.startsWith(PO_DIR);

switch (command) {
  case "extract": {
    const { errors, outputs } = build({ requireMerged: true });
    if (errors.length) fail(errors);
    writeOutputs(outputs, isCatalogFile);
    console.log("i18n: wrote i18n/messages.pot and merged i18n/po/*.po");
    break;
  }
  case "generate": {
    const { errors, outputs, coverage, version } = build({ requireMerged: false });
    if (errors.length) fail(errors);
    writeOutputs(outputs, (p) => !isCatalogFile(p));
    console.log(`i18n: generated catalogs version ${version}`);
    console.table(coverage);
    break;
  }
  case "check": {
    const { errors, outputs, coverage } = build({ requireMerged: true });
    for (const [path, content] of outputs) {
      const current = existsSync(path) ? readFileSync(path, "utf8").replace(/\r\n/g, "\n") : null;
      if (current !== content) errors.push(`${rel(path)} is stale; run extract then generate`);
    }
    if (errors.length) fail(errors);
    console.log("i18n: catalogs are valid and up to date");
    console.table(coverage);
    break;
  }
  case "mo": {
    const locale = arg;
    const file = join(PO_DIR, `${locale}.po`);
    if (!locale || !existsSync(file)) fail([`usage: mo <locale>; no ${rel(file)}`]);
    const po = parsePo(readFileSync(file, "utf8"));
    const out = join(I18N, "build", `${locale}.mo`);
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(out, writeMo(po, locale));
    console.log(`i18n: wrote ${rel(out)}`);
    break;
  }
  default:
    fail(["usage: catalog.mjs extract|generate|check|mo <locale>"]);
}
