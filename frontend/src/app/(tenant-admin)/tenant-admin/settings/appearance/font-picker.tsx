"use client";

import { useId, useState } from "react";
import { RotateCcw, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { APPEARANCE_FONT_OPTIONS, DEFAULT_APPEARANCE_FONT, type AppearanceFontId } from "@/lib/fonts/font-options";
import { PUBLIC_FONTS, SCRIPT_FALLBACK_FONT_CLASSES } from "@/lib/fonts/public-fonts";

/** Every curated family's `next/font` variable class — applied only on the picker. */
const ALL_FONT_CLASSES = [...Object.values(PUBLIC_FONTS).map((font) => font.variableClass), SCRIPT_FALLBACK_FONT_CLASSES]
  .filter(Boolean)
  .join(" ");

const SAMPLES = {
  heading: "Learn with confidence",
  body: "Explore practical courses, follow your progress and learn at your own pace with guidance from experienced teachers.",
} as const;

/**
 * Searchable single-choice font list (native radios, so arrow keys move the
 * selection and Tab leaves the group). Each option renders in its own family,
 * with a live sample of the current choice and "Reset to default".
 */
export function FontPicker({
  label,
  helper,
  kind,
  value,
  onChange,
  disabled,
}: {
  label: string;
  helper: string;
  kind: "heading" | "body";
  value: AppearanceFontId;
  onChange: (value: AppearanceFontId) => void;
  disabled: boolean;
}) {
  const id = useId();
  const [query, setQuery] = useState("");
  const normalized = query.trim().toLowerCase();
  const shown = normalized
    ? APPEARANCE_FONT_OPTIONS.filter((option) => option.label.toLowerCase().includes(normalized) || option.value === value)
    : APPEARANCE_FONT_OPTIONS;
  const selected = PUBLIC_FONTS[value];

  return (
    <fieldset className={cn("flex min-w-0 flex-col gap-3", ALL_FONT_CLASSES)} disabled={disabled} data-testid={`font-picker-${kind}`}>
      <legend className="mb-1 text-sm font-medium text-foreground">{label}</legend>
      <p id={`${id}-helper`} className="-mt-2 text-xs text-muted-foreground">
        {helper}
      </p>
      <div className="relative">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
        <label htmlFor={`${id}-search`} className="sr-only">
          Search {label.toLowerCase()} families
        </label>
        <Input
          id={`${id}-search`}
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search fonts…"
          className="pl-8"
          autoComplete="off"
        />
      </div>
      <div
        role="radiogroup"
        aria-label={label}
        aria-describedby={`${id}-helper`}
        className="flex max-h-60 flex-col gap-0.5 overflow-y-auto rounded-lg border border-border p-1"
      >
        {shown.map((option) => {
          const font = PUBLIC_FONTS[option.value];
          const checked = option.value === value;
          return (
            <label
              key={option.value}
              className={cn(
                "flex cursor-pointer items-center justify-between gap-3 rounded-md px-2.5 py-2 text-sm hover:bg-muted has-focus-visible:ring-3 has-focus-visible:ring-ring/50",
                checked && "bg-primary/10 ring-1 ring-primary/40"
              )}
            >
              <span className="flex items-center gap-2.5">
                <input
                  type="radio"
                  name={`${id}-font`}
                  value={option.value}
                  checked={checked}
                  onChange={() => onChange(option.value)}
                  className="size-4 accent-primary"
                />
                <span style={{ fontFamily: font.stack }} className="text-base">
                  {option.label}
                </span>
              </span>
              <span className="text-xs text-muted-foreground">
                {option.value === DEFAULT_APPEARANCE_FONT ? "Default" : option.legacy ? "Legacy" : option.serif ? "Serif" : "Sans-serif"}
              </span>
            </label>
          );
        })}
        {shown.length === 0 ? (
          <p role="status" className="px-2.5 py-3 text-xs text-muted-foreground">
            No font matches “{query.trim()}”.
          </p>
        ) : null}
      </div>
      <div className="rounded-lg border border-border bg-muted/40 p-4" aria-label={`${label} sample`} role="group">
        <p className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">Sample · {selected.label}</p>
        {kind === "heading" ? (
          <p style={{ fontFamily: selected.stack }} className="text-2xl font-semibold leading-tight text-foreground" data-testid={`font-sample-${kind}`}>
            {SAMPLES.heading}
          </p>
        ) : (
          <p style={{ fontFamily: selected.stack }} className="text-sm leading-6 text-foreground" data-testid={`font-sample-${kind}`}>
            {SAMPLES.body}
          </p>
        )}
      </div>
      <Button
        type="button"
        variant="outline"
        size="sm"
        className="w-fit"
        disabled={disabled || value === DEFAULT_APPEARANCE_FONT}
        onClick={() => onChange(DEFAULT_APPEARANCE_FONT)}
      >
        <RotateCcw aria-hidden="true" /> Reset to default
      </Button>
    </fieldset>
  );
}
