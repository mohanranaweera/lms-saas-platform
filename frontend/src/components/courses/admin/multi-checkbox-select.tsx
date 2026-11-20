"use client";

import { useId, useState, type Ref } from "react";
import { ChevronDown } from "lucide-react";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

export interface CheckboxOption {
  value: string;
  label: string;
}

/**
 * Disclosure-style multi-select: a button summarising the selection that
 * expands an inline, scrollable checkbox list (inline rather than a floating
 * popover so it stays inside the modal's focus trap and scroll area).
 *
 * Validation state: `aria-invalid` is not supported on the button role, so
 * the button references the helper/error text through `aria-describedby`
 * (the label's "(required)" text conveys required) and the checkboxes, which
 * do support it, carry `aria-invalid` plus the same description.
 */
export function MultiCheckboxSelect({
  id,
  options,
  value,
  onChange,
  placeholder,
  invalid,
  describedBy,
  disabled,
  loading,
  loadError,
  emptyText,
  testId,
  onBlur,
  disabledValues,
  ref,
}: {
  id: string;
  options: CheckboxOption[];
  value: string[];
  onChange: (next: string[]) => void;
  placeholder: string;
  invalid?: boolean;
  describedBy?: string;
  disabled?: boolean;
  loading?: boolean;
  loadError?: string | null;
  emptyText: string;
  testId?: string;
  onBlur?: () => void;
  /** Options shown checked-or-unchecked but not toggleable (e.g. a primary teacher only a Tenant Admin may change). */
  disabledValues?: string[];
  /** Forwarded to the summary button so react-hook-form's `setFocus` can reach it. */
  ref?: Ref<HTMLButtonElement>;
}) {
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState("");
  const panelId = useId();
  const selectedLabels = options.filter((option) => value.includes(option.value)).map((option) => option.label);
  const term = filter.trim().toLowerCase();
  const visible = term ? options.filter((option) => option.label.toLowerCase().includes(term)) : options;

  const toggle = (optionValue: string) => {
    if (disabledValues?.includes(optionValue)) return;
    onChange(value.includes(optionValue) ? value.filter((v) => v !== optionValue) : [...value, optionValue]);
  };

  return (
    <div className="flex flex-col gap-1" data-testid={testId}>
      <button
        ref={ref}
        id={id}
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        data-invalid={invalid || undefined}
        aria-describedby={describedBy}
        disabled={disabled}
        onClick={() => {
          if (open) onBlur?.();
          setOpen((current) => !current);
        }}
        className={cn(
          "flex min-h-8 w-full items-center justify-between gap-2 rounded-lg border border-input bg-transparent px-2.5 py-1 text-left text-sm outline-none transition-colors focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 data-invalid:border-destructive data-invalid:ring-3 data-invalid:ring-destructive/20 dark:bg-input/30",
          selectedLabels.length === 0 && "text-muted-foreground"
        )}
      >
        <span className="line-clamp-2">
          {selectedLabels.length > 0 ? selectedLabels.join(", ") : placeholder}
        </span>
        <ChevronDown
          aria-hidden="true"
          className={cn("size-4 shrink-0 text-muted-foreground transition-transform", open && "rotate-180")}
        />
      </button>
      <div
        id={panelId}
        hidden={!open}
        className="flex flex-col gap-2 rounded-lg border border-border bg-background p-2"
      >
        {loading ? (
          <p className="px-1 py-2 text-xs text-muted-foreground" role="status">
            Loading options…
          </p>
        ) : loadError ? (
          <p className="px-1 py-2 text-xs text-destructive" role="alert">
            {loadError}
          </p>
        ) : options.length === 0 ? (
          <p className="px-1 py-2 text-xs text-muted-foreground">{emptyText}</p>
        ) : (
          <>
            {options.length > 8 ? (
              <Input
                type="search"
                aria-label="Filter options"
                placeholder="Filter…"
                value={filter}
                onChange={(event) => setFilter(event.target.value)}
              />
            ) : null}
            <fieldset className="flex max-h-48 flex-col gap-0.5 overflow-y-auto" aria-describedby={describedBy}>
              <legend className="sr-only">{placeholder}</legend>
              {visible.map((option) => {
                const checkboxId = `${id}-${option.value}`;
                return (
                  <label
                    key={option.value}
                    htmlFor={checkboxId}
                    className="flex cursor-pointer items-center gap-2 rounded-md px-1.5 py-1 hover:bg-muted"
                  >
                    <input
                      id={checkboxId}
                      type="checkbox"
                      className="size-4 rounded border-input"
                      checked={value.includes(option.value)}
                      disabled={disabledValues?.includes(option.value) || undefined}
                      aria-invalid={invalid || undefined}
                      onChange={() => toggle(option.value)}
                    />
                    <span>{option.label}</span>
                  </label>
                );
              })}
              {visible.length === 0 ? (
                <p className="px-1 py-2 text-xs text-muted-foreground">No matches.</p>
              ) : null}
            </fieldset>
          </>
        )}
      </div>
    </div>
  );
}
