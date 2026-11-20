"use client";

import { useId } from "react";
import { Check, RotateCcw, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { isHexColor } from "@/lib/theme/color";

/**
 * One theme color: native picker + editable hex text + swatch + Reset.
 * Empty means "use the default" (shown as the placeholder and the swatch).
 */
export function ColorField({
  label,
  description,
  value,
  defaultValue,
  onChange,
  error,
  disabled,
  testId,
}: {
  label: string;
  description: string;
  value: string;
  defaultValue: string;
  onChange: (value: string) => void;
  error?: string;
  disabled: boolean;
  testId: string;
}) {
  const id = useId();
  const effective = isHexColor(value) ? value : defaultValue;
  const describedBy = [`${id}-desc`, error ? `${id}-error` : null].filter(Boolean).join(" ");

  return (
    <div className="flex flex-col gap-1.5" data-testid={testId}>
      <label htmlFor={`${id}-hex`} className="text-sm font-medium text-foreground">
        {label}
      </label>
      <p id={`${id}-desc`} className="text-xs text-muted-foreground">
        {description}
      </p>
      <div className="flex items-center gap-2">
        <span className="relative size-8 shrink-0 overflow-hidden rounded-md border border-border shadow-xs" style={{ backgroundColor: effective }}>
          <input
            type="color"
            aria-label={`${label} picker`}
            value={effective.toLowerCase()}
            disabled={disabled}
            onChange={(event) => onChange(event.target.value.toUpperCase())}
            className="absolute inset-0 size-full cursor-pointer opacity-0 disabled:cursor-not-allowed"
          />
        </span>
        <Input
          id={`${id}-hex`}
          value={value}
          placeholder={defaultValue}
          maxLength={7}
          spellCheck={false}
          autoComplete="off"
          disabled={disabled}
          aria-invalid={Boolean(error) || undefined}
          aria-describedby={describedBy}
          onChange={(event) => {
            const next = event.target.value.trim();
            onChange(next === "" ? "" : (next.startsWith("#") ? next : `#${next}`).toUpperCase());
          }}
          className="w-28 font-mono uppercase"
        />
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={disabled || value.toUpperCase() === defaultValue.toUpperCase()}
          onClick={() => onChange(defaultValue)}
          aria-label={`Reset ${label.toLowerCase()} to default ${defaultValue}`}
        >
          <RotateCcw aria-hidden="true" /> Reset
        </Button>
      </div>
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/** "AA pass 7.12:1" / "Fails 2.10:1 (needs 4.5:1)" — icon + text, never color alone. */
export function ContrastBadge({ label, ratio, minimum }: { label: string; ratio: number; minimum: number }) {
  const pass = ratio >= minimum;
  return (
    <li
      className={cn(
        "flex items-center justify-between gap-3 rounded-md border px-3 py-2 text-xs",
        pass ? "border-emerald-200 bg-emerald-50 text-emerald-900" : "border-destructive/30 bg-destructive/5 text-destructive"
      )}
      data-testid="contrast-badge"
      data-pass={pass}
    >
      <span className="font-medium">{label}</span>
      <span className="flex items-center gap-1 font-semibold tabular-nums">
        {pass ? <Check className="size-3.5" aria-hidden="true" /> : <X className="size-3.5" aria-hidden="true" />}
        {pass ? "AA pass" : "Fails"} {ratio.toFixed(2)}:1{pass ? "" : ` (needs ${minimum}:1)`}
      </span>
    </li>
  );
}
