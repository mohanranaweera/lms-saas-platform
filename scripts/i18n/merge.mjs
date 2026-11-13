// I18N-01 PO merge rules (docs/architecture/i18n-translation-management.md §3.3): never drop work.

/** registry: Map<key, {source, context, file}> sorted by key. */
export function mergePo(po, registry) {
  const byKey = new Map(po.entries.map((e) => [e.msgctxt, e]));
  const merged = [];
  for (const [key, e] of registry.entries()) {
    const old = byKey.get(key);
    byKey.delete(key);
    const entry = {
      msgctxt: key,
      msgid: e.source,
      msgstr: old?.msgstr ?? "",
      extracted: e.context ? [e.context] : [],
      references: [e.file],
      flags: ["icu-format"],
      comments: old?.comments ?? [],
      previous: old?.previous ?? null,
      obsolete: false,
    };
    const wasFuzzy = old?.flags.includes("fuzzy") ?? false;
    if (old && old.msgid !== e.source && old.msgstr !== "") {
      // Source changed: keep the translation, flag it for review, remember the old source.
      entry.flags.push("fuzzy");
      entry.previous = entry.previous ?? old.msgid;
    } else if (wasFuzzy && entry.msgstr !== "") {
      entry.flags.push("fuzzy");
    }
    if (!entry.flags.includes("fuzzy")) entry.previous = null;
    merged.push(entry);
  }
  // Keys no longer in the registry are kept as obsolete entries, never dropped.
  for (const old of [...byKey.values()].sort((a, b) => (a.msgctxt < b.msgctxt ? -1 : 1))) {
    if (old.msgstr === "" && !old.obsolete) continue; // nothing to preserve
    merged.push({ ...old, obsolete: true });
  }
  return { header: po.header, entries: merged };
}
