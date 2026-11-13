/**
 * I18N-01 shared message format: an ICU MessageFormat subset (docs/architecture/
 * i18n-translation-management.md §3.2). The Java twin is
 * com.lms.localizationmanagement.format.MessageFormatter; both must pass
 * i18n/spec/format-vectors.json.
 *
 * This file is also executed directly by Node (scripts/i18n), so it uses only
 * erasable TypeScript syntax and has no imports.
 *
 * Output is always plain text. Nothing here evaluates or renders HTML.
 */

export type MessageNode =
  | { type: "text"; value: string }
  | { type: "arg"; name: string }
  | { type: "pound" }
  | { type: "plural"; name: string; options: Record<string, MessageNode[]> };

export type MessageParams = Record<string, string | number | null | undefined>;

export class MessageSyntaxError extends Error {
  readonly position: number;

  constructor(message: string, position: number) {
    super(`${message} at ${position}`);
    this.name = "MessageSyntaxError";
    this.position = position;
  }
}

export const MAX_MESSAGE_LENGTH = 5000;

const NAME_START = /[A-Za-z]/;
const NAME_PART = /[A-Za-z0-9_]/;

/** CLDR integer plural categories for the supported languages (non-integers → "other"). */
const PLURAL_CATEGORIES: Record<string, readonly string[]> = {
  en: ["one", "other"],
  si: ["one", "other"],
  ta: ["one", "other"],
};

export function languageOf(locale: string): string {
  return locale.split("-")[0].toLowerCase();
}

export function pluralCategoriesFor(locale: string): readonly string[] {
  return PLURAL_CATEGORIES[languageOf(locale)] ?? ["one", "other"];
}

export function pluralCategory(locale: string, n: number): string {
  if (!Number.isInteger(n)) {
    return "other";
  }
  const abs = Math.abs(n);
  switch (languageOf(locale)) {
    case "si":
      return abs === 0 || abs === 1 ? "one" : "other";
    default:
      return abs === 1 ? "one" : "other";
  }
}

export function parseMessage(text: string): MessageNode[] {
  const parser = new Parser(text);
  const nodes = parser.parseNodes(false, 0);
  if (parser.pos < text.length) {
    throw new MessageSyntaxError("Unmatched '}'", parser.pos);
  }
  return nodes;
}

class Parser {
  readonly text: string;
  pos = 0;

  constructor(text: string) {
    this.text = text;
  }

  /** Parses until end of input (depth 0) or the `}` closing a plural branch (left unconsumed). */
  parseNodes(inPlural: boolean, depth: number): MessageNode[] {
    const nodes: MessageNode[] = [];
    let buffer = "";
    const flush = () => {
      if (buffer) {
        nodes.push({ type: "text", value: buffer });
        buffer = "";
      }
    };
    while (this.pos < this.text.length) {
      const ch = this.text[this.pos];
      if (ch === "'") {
        const next = this.text[this.pos + 1];
        if (next === "'") {
          buffer += "'";
          this.pos += 2;
        } else if (next === "{" || next === "}" || (next === "#" && inPlural)) {
          this.pos += 1;
          let closed = false;
          while (this.pos < this.text.length) {
            const q = this.text[this.pos];
            if (q === "'") {
              if (this.text[this.pos + 1] === "'") {
                buffer += "'";
                this.pos += 2;
                continue;
              }
              this.pos += 1;
              closed = true;
              break;
            }
            buffer += q;
            this.pos += 1;
          }
          if (!closed) {
            throw new MessageSyntaxError("Unterminated quote", this.pos);
          }
        } else {
          buffer += "'";
          this.pos += 1;
        }
      } else if (ch === "{") {
        flush();
        nodes.push(this.parseArgument(depth));
      } else if (ch === "}") {
        if (depth === 0) {
          throw new MessageSyntaxError("Unmatched '}'", this.pos);
        }
        flush();
        return nodes;
      } else if (ch === "#" && inPlural) {
        flush();
        nodes.push({ type: "pound" });
        this.pos += 1;
      } else {
        buffer += ch;
        this.pos += 1;
      }
    }
    if (depth > 0) {
      throw new MessageSyntaxError("Unclosed plural branch", this.pos);
    }
    flush();
    return nodes;
  }

  parseArgument(depth: number): MessageNode {
    const start = this.pos;
    this.pos += 1; // {
    this.skipWhitespace();
    const name = this.readName();
    if (!name) {
      throw new MessageSyntaxError("Expected placeholder name", this.pos);
    }
    this.skipWhitespace();
    const ch = this.text[this.pos];
    if (ch === "}") {
      this.pos += 1;
      return { type: "arg", name };
    }
    if (ch !== ",") {
      throw new MessageSyntaxError("Expected '}' or ','", this.pos);
    }
    this.pos += 1;
    this.skipWhitespace();
    const kind = this.readWord();
    if (kind !== "plural") {
      throw new MessageSyntaxError(`Unsupported argument type '${kind}'`, this.pos);
    }
    this.skipWhitespace();
    if (this.text[this.pos] !== ",") {
      throw new MessageSyntaxError("Expected ',' after plural", this.pos);
    }
    this.pos += 1;
    const options: Record<string, MessageNode[]> = {};
    for (;;) {
      this.skipWhitespace();
      if (this.pos >= this.text.length) {
        throw new MessageSyntaxError("Unclosed plural argument", start);
      }
      if (this.text[this.pos] === "}") {
        this.pos += 1;
        break;
      }
      const selector = this.readSelector();
      if (!selector) {
        throw new MessageSyntaxError("Expected plural selector", this.pos);
      }
      if (Object.prototype.hasOwnProperty.call(options, selector)) {
        throw new MessageSyntaxError(`Duplicate plural selector '${selector}'`, this.pos);
      }
      this.skipWhitespace();
      if (this.text[this.pos] !== "{") {
        throw new MessageSyntaxError("Expected '{' after selector", this.pos);
      }
      this.pos += 1;
      options[selector] = this.parseNodes(true, depth + 1);
      if (this.text[this.pos] !== "}") {
        throw new MessageSyntaxError("Unclosed plural branch", this.pos);
      }
      this.pos += 1;
    }
    return { type: "plural", name, options };
  }

  skipWhitespace() {
    while (this.pos < this.text.length && /\s/.test(this.text[this.pos])) {
      this.pos += 1;
    }
  }

  readName(): string {
    if (this.pos >= this.text.length || !NAME_START.test(this.text[this.pos])) {
      return "";
    }
    const start = this.pos;
    while (this.pos < this.text.length && NAME_PART.test(this.text[this.pos])) {
      this.pos += 1;
    }
    return this.text.slice(start, this.pos);
  }

  readWord(): string {
    const start = this.pos;
    while (this.pos < this.text.length && /[a-z]/.test(this.text[this.pos])) {
      this.pos += 1;
    }
    return this.text.slice(start, this.pos);
  }

  readSelector(): string {
    if (this.text[this.pos] === "=") {
      const start = this.pos;
      this.pos += 1;
      while (this.pos < this.text.length && /[0-9]/.test(this.text[this.pos])) {
        this.pos += 1;
      }
      return this.pos - start > 1 ? this.text.slice(start, this.pos) : "";
    }
    return this.readWord();
  }
}

/** Placeholder names and plural-argument names used anywhere in the message. */
export function collectArguments(nodes: MessageNode[]): { placeholders: Set<string>; plurals: Set<string> } {
  const placeholders = new Set<string>();
  const plurals = new Set<string>();
  const walk = (list: MessageNode[]) => {
    for (const node of list) {
      if (node.type === "arg") {
        placeholders.add(node.name);
      } else if (node.type === "plural") {
        plurals.add(node.name);
        for (const branch of Object.values(node.options)) {
          walk(branch);
        }
      }
    }
  };
  walk(nodes);
  return { placeholders, plurals };
}

/**
 * Validation problems as stable codes (`CODE` or `CODE:detail`), sorted. Empty =
 * valid. `source` is the English source the translation must stay compatible with.
 */
export function validateMessage(text: string, locale: string, source?: string): string[] {
  const problems = new Set<string>();
  if (text.length > MAX_MESSAGE_LENGTH) {
    problems.add("TOO_LONG");
  }
  let nodes: MessageNode[];
  try {
    nodes = parseMessage(text);
  } catch {
    problems.add("SYNTAX");
    return [...problems].sort();
  }
  const allowed = new Set(pluralCategoriesFor(locale));
  const checkPlurals = (list: MessageNode[]) => {
    for (const node of list) {
      if (node.type === "plural") {
        if (!Object.prototype.hasOwnProperty.call(node.options, "other")) {
          problems.add(`PLURAL_MISSING_OTHER:${node.name}`);
        }
        for (const [selector, branch] of Object.entries(node.options)) {
          if (!selector.startsWith("=") && !allowed.has(selector)) {
            problems.add(`PLURAL_INVALID_CATEGORY:${selector}`);
          }
          checkPlurals(branch);
        }
      }
    }
  };
  checkPlurals(nodes);
  if (source !== undefined) {
    let sourceNodes: MessageNode[] | null = null;
    try {
      sourceNodes = parseMessage(source);
    } catch {
      sourceNodes = null;
    }
    if (sourceNodes) {
      const want = collectArguments(sourceNodes);
      const have = collectArguments(nodes);
      for (const name of want.placeholders) {
        if (!have.placeholders.has(name)) problems.add(`PLACEHOLDER_MISSING:${name}`);
      }
      for (const name of have.placeholders) {
        if (!want.placeholders.has(name)) problems.add(`PLACEHOLDER_UNKNOWN:${name}`);
      }
      for (const name of want.plurals) {
        if (!have.plurals.has(name)) problems.add(`PLURAL_MISSING:${name}`);
      }
      for (const name of have.plurals) {
        if (!want.plurals.has(name)) problems.add(`PLURAL_UNKNOWN:${name}`);
      }
    }
  }
  return [...problems].sort();
}

const numberFormats = new Map<string, Intl.NumberFormat>();

function formatNumber(locale: string, n: number): string {
  let format = numberFormats.get(locale);
  if (!format) {
    format = new Intl.NumberFormat(locale);
    numberFormats.set(locale, format);
  }
  return format.format(n);
}

function render(nodes: MessageNode[], locale: string, params: MessageParams, count: number | null): string {
  let out = "";
  for (const node of nodes) {
    switch (node.type) {
      case "text":
        out += node.value;
        break;
      case "pound":
        out += count === null ? "#" : formatNumber(locale, count);
        break;
      case "arg": {
        const value = params[node.name];
        if (value === undefined || value === null) {
          out += `{${node.name}}`;
        } else {
          out += typeof value === "number" ? formatNumber(locale, value) : String(value);
        }
        break;
      }
      case "plural": {
        const raw = params[node.name];
        const n = typeof raw === "number" ? raw : Number(raw);
        if (raw === undefined || raw === null || Number.isNaN(n)) {
          out += render(node.options.other ?? [], locale, params, null);
          break;
        }
        const exact = node.options[`=${n}`];
        const branch = exact ?? node.options[pluralCategory(locale, n)] ?? node.options.other ?? [];
        out += render(branch, locale, params, n);
        break;
      }
    }
  }
  return out;
}

/** Formats `text`; throws MessageSyntaxError for an unparseable message. */
export function formatMessage(text: string, locale: string, params: MessageParams = {}): string {
  return render(parseMessage(text), locale, params, null);
}
