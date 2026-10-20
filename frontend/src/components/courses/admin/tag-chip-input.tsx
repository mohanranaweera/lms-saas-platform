"use client";

import { useState, type KeyboardEvent, type Ref } from "react";
import { XIcon } from "lucide-react";
import { Input } from "@/components/ui/input";

/**
 * Adds `raw` tag names to `current`: trims, drops blanks, de-duplicates
 * case-insensitively, and prefers an existing tenant tag's spelling. The
 * server repeats this normalisation; this is only so the chips match.
 */
export function mergeTagNames(current: string[], raw: string[], existing: string[]): string[] {
  const next = current.slice();
  const seen = new Set(next.map((name) => name.toLowerCase()));
  for (const candidate of raw) {
    const trimmed = candidate.trim().replace(/\s+/g, " ");
    if (!trimmed) continue;
    const key = trimmed.toLowerCase();
    if (seen.has(key)) continue;
    const known = existing.find((name) => name.toLowerCase() === key);
    next.push(known ?? trimmed);
    seen.add(key);
  }
  return next;
}

/** Chip input: pick existing tenant tags or create new ones with comma or Enter. */
export function TagChipInput({
  id,
  value,
  onChange,
  existingTags,
  describedBy,
  disabled,
  ref,
}: {
  id: string;
  value: string[];
  onChange: (next: string[]) => void;
  existingTags: string[];
  describedBy?: string;
  disabled?: boolean;
  /** Forwarded to the text input so react-hook-form's `setFocus` can reach it. */
  ref?: Ref<HTMLInputElement>;
}) {
  const [draft, setDraft] = useState("");

  const commit = (text: string) => {
    const parts = text.split(",");
    const next = mergeTagNames(value, parts, existingTags);
    if (next.length !== value.length) onChange(next);
    setDraft("");
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter" || event.key === ",") {
      event.preventDefault();
      commit(draft);
    } else if (event.key === "Backspace" && draft === "" && value.length > 0) {
      onChange(value.slice(0, -1));
    }
  };

  const selectedKeys = new Set(value.map((name) => name.toLowerCase()));
  const term = draft.trim().toLowerCase();
  const suggestions = existingTags
    .filter((name) => !selectedKeys.has(name.toLowerCase()))
    .filter((name) => (term ? name.toLowerCase().includes(term) : true))
    .slice(0, 12);

  return (
    <div className="flex flex-col gap-2" data-testid="course-tags-input">
      {value.length > 0 ? (
        <ul className="flex flex-wrap gap-1.5" aria-label="Selected tags">
          {value.map((name) => (
            <li
              key={name.toLowerCase()}
              className="inline-flex items-center gap-1 rounded-full border border-border bg-muted px-2 py-0.5 text-xs text-foreground"
            >
              {name}
              <button
                type="button"
                disabled={disabled}
                onClick={() => onChange(value.filter((tag) => tag !== name))}
                aria-label={`Remove tag ${name}`}
                className="rounded-full text-muted-foreground outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring/50"
              >
                <XIcon className="size-3" aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <Input
        ref={ref}
        id={id}
        value={draft}
        disabled={disabled}
        placeholder="Type a tag, then press Enter or comma"
        aria-describedby={describedBy}
        onChange={(event) => {
          const text = event.target.value;
          if (text.includes(",")) {
            commit(text);
          } else {
            setDraft(text);
          }
        }}
        onKeyDown={handleKeyDown}
        onBlur={() => {
          if (draft.trim()) commit(draft);
        }}
      />
      {suggestions.length > 0 ? (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-xs text-muted-foreground">Existing tags:</span>
          {suggestions.map((name) => (
            <button
              key={name}
              type="button"
              disabled={disabled}
              onClick={() => onChange(mergeTagNames(value, [name], existingTags))}
              className="rounded-full border border-dashed border-border px-2 py-0.5 text-xs text-muted-foreground outline-none hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring/50"
              aria-label={`Add existing tag ${name}`}
            >
              + {name}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
