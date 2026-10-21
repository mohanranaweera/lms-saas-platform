"use client";

import { useId, useMemo, useRef, useState } from "react";
import { Popover } from "@base-ui/react/popover";
import { ChevronDownIcon, SearchIcon } from "lucide-react";
import { cn } from "@/lib/utils";

export interface MultiSelectOption {
  value: string;
  label: string;
}

/**
 * Dropdown multi-select with checkboxes and type-to-filter (Klass-style course
 * picker). The popup holds a search box, an "All" checkbox that toggles every
 * option currently shown, and one native checkbox per option - so it is fully
 * keyboard operable (Tab / Space, Escape closes and returns focus to the
 * trigger). Selection is always the caller's controlled `value`.
 */
export function MultiSelect({
  id,
  options,
  value,
  onChange,
  placeholder = "Select…",
  searchPlaceholder = "Type to filter…",
  emptyText = "No options available.",
  invalid = false,
  disabled = false,
  testId,
  "aria-describedby": describedBy,
}: {
  id?: string;
  options: MultiSelectOption[];
  value: string[];
  onChange: (next: string[]) => void;
  placeholder?: string;
  searchPlaceholder?: string;
  /** Shown in the popup when there are no options at all. */
  emptyText?: string;
  invalid?: boolean;
  disabled?: boolean;
  testId?: string;
  "aria-describedby"?: string;
}) {
  const generatedId = useId();
  const searchRef = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");

  const selected = useMemo(() => new Set(value), [value]);
  const labelFor = useMemo(() => new Map(options.map((option) => [option.value, option.label])), [options]);
  const normalized = query.trim().toLowerCase();
  const shown = normalized
    ? options.filter((option) => option.label.toLowerCase().includes(normalized))
    : options;
  const allShownSelected = shown.length > 0 && shown.every((option) => selected.has(option.value));
  const selectedLabels = value.map((entry) => labelFor.get(entry)).filter((label): label is string => !!label);

  const toggle = (optionValue: string, checked: boolean) =>
    onChange(checked ? [...value, optionValue] : value.filter((entry) => entry !== optionValue));

  const toggleAllShown = (checked: boolean) => {
    const shownValues = new Set(shown.map((option) => option.value));
    onChange(
      checked
        ? [...value, ...shown.map((option) => option.value).filter((entry) => !selected.has(entry))]
        : value.filter((entry) => !shownValues.has(entry))
    );
  };

  return (
    <Popover.Root
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setQuery("");
      }}
    >
      <Popover.Trigger
        id={id}
        disabled={disabled}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
        data-testid={testId}
        className="flex min-h-8 w-full items-center justify-between gap-1.5 rounded-lg border border-input bg-transparent py-1 pr-2 pl-2.5 text-left text-sm transition-colors outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20 dark:bg-input/30"
      >
        <span className="flex min-w-0 flex-1 flex-wrap gap-1">
          {selectedLabels.length === 0 ? (
            <span className="text-muted-foreground">{placeholder}</span>
          ) : (
            <>
              {selectedLabels.slice(0, 3).map((label) => (
                <span key={label} className="max-w-full truncate rounded-md bg-muted px-1.5 py-0.5 text-xs text-foreground">
                  {label}
                </span>
              ))}
              {selectedLabels.length > 3 ? (
                <span className="rounded-md bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                  +{selectedLabels.length - 3} more
                </span>
              ) : null}
            </>
          )}
        </span>
        <ChevronDownIcon
          aria-hidden="true"
          className={cn("size-4 shrink-0 text-muted-foreground transition-transform", open && "rotate-180")}
        />
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Positioner sideOffset={4} align="start" className="isolate z-50">
          <Popover.Popup
            initialFocus={searchRef}
            data-testid={testId ? `${testId}-popup` : undefined}
            className="flex max-h-[min(22rem,var(--available-height))] w-(--anchor-width) min-w-64 flex-col overflow-hidden rounded-lg bg-popover text-sm text-popover-foreground shadow-md ring-1 ring-foreground/10 outline-none"
          >
            <div className="flex items-center gap-2 border-b border-border px-2.5 py-2">
              <SearchIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <input
                ref={searchRef}
                type="search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder={searchPlaceholder}
                aria-label={searchPlaceholder}
                aria-controls={`${generatedId}-options`}
                autoComplete="off"
                className="min-w-0 flex-1 bg-transparent outline-none placeholder:text-muted-foreground"
                data-testid={testId ? `${testId}-search` : undefined}
              />
            </div>
            <div id={`${generatedId}-options`} role="group" aria-label="Options" className="flex flex-col overflow-y-auto p-1">
              {options.length === 0 ? (
                <p className="px-2 py-3 text-xs text-muted-foreground">{emptyText}</p>
              ) : shown.length === 0 ? (
                <p className="px-2 py-3 text-xs text-muted-foreground" role="status">
                  Nothing matches &ldquo;{query.trim()}&rdquo;.
                </p>
              ) : (
                <>
                  <OptionRow
                    label={normalized ? `All matching (${shown.length})` : "All"}
                    checked={allShownSelected}
                    onChange={toggleAllShown}
                    bold
                  />
                  {shown.map((option) => (
                    <OptionRow
                      key={option.value}
                      label={option.label}
                      checked={selected.has(option.value)}
                      onChange={(checked) => toggle(option.value, checked)}
                    />
                  ))}
                </>
              )}
            </div>
            <div className="flex items-center justify-between gap-2 border-t border-border px-2.5 py-1.5 text-xs text-muted-foreground">
              <span role="status">{value.length} selected</span>
              <Popover.Close className="rounded-md px-2 py-1 font-medium text-foreground outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50">
                Done
              </Popover.Close>
            </div>
          </Popover.Popup>
        </Popover.Positioner>
      </Popover.Portal>
    </Popover.Root>
  );
}

function OptionRow({
  label,
  checked,
  onChange,
  bold = false,
}: {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  bold?: boolean;
}) {
  return (
    <label
      className={cn(
        "flex cursor-pointer items-start gap-2 rounded-md px-2 py-1.5 hover:bg-muted has-focus-visible:bg-muted",
        bold && "font-medium"
      )}
    >
      <input
        type="checkbox"
        className="mt-0.5 size-4 shrink-0 accent-blue-600"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
      />
      <span className="min-w-0 break-words">{label}</span>
    </label>
  );
}
