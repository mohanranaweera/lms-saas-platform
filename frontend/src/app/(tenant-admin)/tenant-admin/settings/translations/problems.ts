import type { TranslateFn } from "@/i18n/i18n-provider";

/**
 * Human text for message-validation problem codes (`CODE` or `CODE:detail`)
 * produced by `validateMessage` (src/i18n/icu.ts) and re-reported by the
 * server in `details.problems` (doc §3.2). Unknown codes are shown verbatim.
 */
const PROBLEM_TEXT: Record<string, (t: TranslateFn, detail: string) => string> = {
  TOO_LONG: (t) => t("i18nAdmin.problem.tooLong", { max: 5000 }),
  SYNTAX: (t) => t("i18nAdmin.problem.syntax"),
  PLURAL_MISSING_OTHER: (t, detail) => t("i18nAdmin.problem.pluralMissingOther", { name: detail }),
  PLURAL_INVALID_CATEGORY: (t, detail) => t("i18nAdmin.problem.pluralInvalidCategory", { category: detail }),
  PLACEHOLDER_MISSING: (t, detail) => t("i18nAdmin.problem.placeholderMissing", { name: detail }),
  PLACEHOLDER_UNKNOWN: (t, detail) => t("i18nAdmin.problem.placeholderUnknown", { name: detail }),
  PLURAL_MISSING: (t, detail) => t("i18nAdmin.problem.pluralMissing", { name: detail }),
  PLURAL_UNKNOWN: (t, detail) => t("i18nAdmin.problem.pluralUnknown", { name: detail }),
  EMPTY: (t) => t("i18nAdmin.problem.empty"),
};

export function describeProblem(t: TranslateFn, problem: string): string {
  const [code, ...rest] = problem.split(":");
  const detail = rest.join(":");
  const text = Object.prototype.hasOwnProperty.call(PROBLEM_TEXT, code) ? PROBLEM_TEXT[code] : undefined;
  return text ? text(t, detail) : problem;
}

/**
 * `details.problems` from a 422 `TRANSLATION_INVALID` / `PO_IMPORT_INVALID`:
 * accepts plain codes or `{ key?, problems[] }` entries (parsed defensively;
 * anything else is ignored).
 */
export function serverProblems(details: Record<string, unknown> | null | undefined): string[] {
  const raw = details?.problems;
  if (!Array.isArray(raw)) return [];
  const out: string[] = [];
  for (const entry of raw) {
    if (typeof entry === "string") out.push(entry);
    else if (entry && typeof entry === "object") {
      const record = entry as { key?: unknown; problems?: unknown; code?: unknown };
      const prefix = typeof record.key === "string" ? `${record.key}: ` : "";
      if (Array.isArray(record.problems)) {
        for (const problem of record.problems) if (typeof problem === "string") out.push(`${prefix}${problem}`);
      } else if (typeof record.code === "string") {
        out.push(`${prefix}${record.code}`);
      }
    }
  }
  return out;
}

/** Describes a possibly key-prefixed problem (`key: CODE:detail`). */
export function describeServerProblem(t: TranslateFn, problem: string): string {
  const match = /^([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9_]+)+): (.+)$/.exec(problem);
  return match ? `${match[1]}: ${describeProblem(t, match[2])}` : describeProblem(t, problem);
}
