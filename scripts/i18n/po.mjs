// I18N-01 PO reader/writer and MO writer (no dependencies). The Java twin is
// com.lms.localizationmanagement.po.PoFile; both follow the mapping in
// docs/architecture/i18n-translation-management.md §3.3.

const unescape = (s) =>
  s.replace(/\\(.)/g, (_, c) => ({ n: "\n", t: "\t", r: "\r", '"': '"', "\\": "\\" })[c] ?? c);

const escape = (s) =>
  s.replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/\n/g, "\\n").replace(/\t/g, "\\t").replace(/\r/g, "\\r");

function quoted(line) {
  const m = line.match(/^"(.*)"\s*$/);
  if (!m) throw new Error(`PO: expected a quoted string, got: ${line}`);
  return unescape(m[1]);
}

/**
 * Parses PO text into { header, entries[] }. Entry: { msgctxt, msgid, msgstr,
 * msgid_plural?, extracted[], references[], flags[], comments[], previous, obsolete }.
 */
export function parsePo(text) {
  const lines = text.replace(/^﻿/, "").replace(/\r\n/g, "\n").split("\n");
  const entries = [];
  let header = "";
  let cur = null;
  let field = null;
  const fresh = () => ({
    msgctxt: null, msgid: null, msgstr: null, extracted: [], references: [], flags: [], comments: [],
    previous: null, obsolete: false,
  });
  const finish = () => {
    if (cur && cur.msgid !== null) {
      if (cur.msgid === "" && cur.msgctxt === null) header = cur.msgstr ?? "";
      else entries.push({ ...cur, msgctxt: cur.msgctxt ?? "", msgstr: cur.msgstr ?? "" });
    }
    cur = null;
    field = null;
  };
  for (let raw of lines) {
    let line = raw.trim();
    if (line === "") {
      finish();
      continue;
    }
    let obsolete = false;
    if (line.startsWith("#~")) {
      obsolete = true;
      line = line.slice(2).trim();
    }
    if (!cur) cur = fresh();
    if (obsolete) cur.obsolete = true;
    if (line.startsWith("#|")) {
      const rest = line.slice(2).trim();
      if (rest.startsWith("msgid ")) cur.previous = quoted(rest.slice(6));
      else if (rest.startsWith('"') && cur.previous !== null) cur.previous += quoted(rest);
      continue;
    }
    if (line.startsWith("#.")) { cur.extracted.push(line.slice(2).trim()); continue; }
    if (line.startsWith("#:")) { cur.references.push(...line.slice(2).trim().split(/\s+/).filter(Boolean)); continue; }
    if (line.startsWith("#,")) {
      cur.flags.push(...line.slice(2).split(",").map((f) => f.trim()).filter(Boolean));
      continue;
    }
    if (line.startsWith("#")) { cur.comments.push(line.slice(1).trim()); continue; }
    const kw = line.match(/^(msgctxt|msgid_plural|msgid|msgstr(?:\[\d+\])?)\s+(.*)$/);
    if (kw) {
      if ((kw[1] === "msgctxt" || (kw[1] === "msgid" && cur.msgctxt === null)) && cur.msgid !== null) {
        // A new entry started without a blank line.
        const keep = cur.obsolete;
        finish();
        cur = fresh();
        cur.obsolete = keep;
      }
      field = kw[1].startsWith("msgstr[") ? "msgstr_plural" : kw[1];
      const value = quoted(kw[2]);
      if (field === "msgstr_plural") {
        cur.msgid_plural = cur.msgid_plural ?? "";
        cur.msgstr = (cur.msgstr ?? "") + value;
      } else {
        cur[field] = value;
      }
      continue;
    }
    if (line.startsWith('"') && field) {
      const value = quoted(line);
      if (field === "msgstr_plural") cur.msgstr += value;
      else cur[field] = (cur[field] ?? "") + value;
      continue;
    }
    throw new Error(`PO: cannot parse line: ${raw}`);
  }
  finish();
  return { header, entries };
}

export function serializePo({ header, entries }) {
  const out = [];
  out.push('msgid ""', `msgstr "${escape(header)}"`, "");
  for (const e of entries) {
    const p = e.obsolete ? "#~ " : "";
    for (const c of e.comments ?? []) out.push(`# ${c}`.trimEnd());
    for (const x of e.extracted ?? []) out.push(`#. ${x}`);
    if (e.references?.length) out.push(`#: ${e.references.join(" ")}`);
    if (e.flags?.length) out.push(`#, ${[...new Set(e.flags)].join(", ")}`);
    if (e.previous !== null && e.previous !== undefined) out.push(`#| msgid "${escape(e.previous)}"`);
    out.push(`${p}msgctxt "${escape(e.msgctxt)}"`);
    out.push(`${p}msgid "${escape(e.msgid)}"`);
    out.push(`${p}msgstr "${escape(e.msgstr)}"`);
    out.push("");
  }
  return out.join("\n");
}

/** GNU MO (little-endian, no hash table). Non-fuzzy, non-empty, non-obsolete entries only. */
export function writeMo(po, locale) {
  const header =
    `Content-Type: text/plain; charset=UTF-8\nLanguage: ${locale}\n` +
    "Plural-Forms: nplurals=1; plural=0;\nX-Message-Format: icu-subset\n";
  const pairs = [["", header]];
  for (const e of po.entries) {
    if (e.obsolete || e.msgstr === "" || e.flags.includes("fuzzy")) continue;
    pairs.push([`${e.msgctxt}\u0004${e.msgid}`, e.msgstr]);
  }
  pairs.sort(([a], [b]) => Buffer.compare(Buffer.from(a, "utf8"), Buffer.from(b, "utf8")));
  const n = pairs.length;
  const keys = pairs.map(([k]) => Buffer.from(k, "utf8"));
  const values = pairs.map(([, v]) => Buffer.from(v, "utf8"));
  const tableStart = 28;
  let offset = tableStart + n * 16;
  const head = Buffer.alloc(offset);
  head.writeUInt32LE(0x950412de, 0);
  head.writeUInt32LE(0, 4);
  head.writeUInt32LE(n, 8);
  head.writeUInt32LE(tableStart, 12);
  head.writeUInt32LE(tableStart + n * 8, 16);
  head.writeUInt32LE(0, 20);
  head.writeUInt32LE(offset, 24);
  const blobs = [];
  keys.forEach((k, i) => {
    head.writeUInt32LE(k.length, tableStart + i * 8);
    head.writeUInt32LE(offset, tableStart + i * 8 + 4);
    blobs.push(k, Buffer.from([0]));
    offset += k.length + 1;
  });
  values.forEach((v, i) => {
    head.writeUInt32LE(v.length, tableStart + n * 8 + i * 8);
    head.writeUInt32LE(offset, tableStart + n * 8 + i * 8 + 4);
    blobs.push(v, Buffer.from([0]));
    offset += v.length + 1;
  });
  return Buffer.concat([head, ...blobs]);
}
