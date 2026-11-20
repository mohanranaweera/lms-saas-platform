"use client";

import { useEffect, useId, useState } from "react";
import { ChevronDown, ShoppingCart, XIcon } from "lucide-react";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { usePaidSessions, type PaidSessionView } from "@/lib/api/tutes";
import { formatTutePrice } from "@/lib/tutes/tute-form";

function optionLabel(session: PaidSessionView): string {
  return session.lessonLabel?.trim() ? `${session.title} (${session.lessonLabel})` : session.title;
}

/**
 * "Sell through a paid session (optional)" - an ARIA 1.2 combobox: type to
 * search the course's PAID sessions (server-side search), Arrow keys move,
 * Enter picks, Escape closes, the X button clears. The server re-checks that
 * the session is a PAID session of this course (`TUTE_SESSION_NOT_ELIGIBLE`).
 */
export function PaidSessionCombobox({
  courseId,
  inputId,
  value,
  selectedTitle,
  onChange,
  invalid,
  describedById,
  disabled,
}: {
  courseId: string;
  inputId: string;
  value: string;
  /** Display text for the current value (e.g. the tute's `paidLessonTitle` when editing). */
  selectedTitle: string | null;
  onChange: (session: { lessonId: string; title: string } | null) => void;
  invalid?: boolean;
  describedById?: string;
  disabled?: boolean;
}) {
  const listboxId = useId();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  const [search, setSearch] = useState("");
  const [activeIndex, setActiveIndex] = useState(-1);
  const query = usePaidSessions(courseId, search, open);
  // Only results for the text currently typed are offered: while the debounce
  // or the request is in flight, stale results are hidden so Enter can't pick them.
  const settled = search === text.trim() && !query.isPlaceholderData;
  const options = settled ? (query.data ?? []) : [];

  useEffect(() => {
    const handle = setTimeout(() => setSearch(text.trim()), 250);
    return () => clearTimeout(handle);
  }, [text]);

  const close = () => {
    setOpen(false);
    setText("");
    setActiveIndex(-1);
  };

  const pick = (session: PaidSessionView) => {
    onChange({ lessonId: session.lessonId, title: optionLabel(session) });
    close();
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      if (!open) setOpen(true);
      setActiveIndex((current) => Math.min(current + 1, options.length - 1));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex((current) => Math.max(current - 1, 0));
    } else if (event.key === "Enter") {
      if (open && activeIndex >= 0 && options[activeIndex]) {
        event.preventDefault();
        pick(options[activeIndex]);
      }
    } else if (event.key === "Escape") {
      if (open) {
        event.preventDefault();
        event.stopPropagation();
        close();
      }
    }
  };

  const shown = open ? text : value ? selectedTitle ?? "Selected session" : "";
  const activeId = open && activeIndex >= 0 && options[activeIndex] ? `${listboxId}-${activeIndex}` : undefined;

  return (
    <div className="relative">
      <ShoppingCart
        aria-hidden="true"
        className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground"
      />
      <Input
        id={inputId}
        role="combobox"
        type="text"
        autoComplete="off"
        aria-expanded={open}
        aria-controls={listboxId}
        aria-autocomplete="list"
        aria-activedescendant={activeId}
        aria-invalid={invalid || undefined}
        aria-describedby={describedById}
        placeholder={value ? undefined : "Search paid sessions"}
        className="pr-16 pl-8"
        value={shown}
        disabled={disabled}
        onFocus={() => setOpen(true)}
        onClick={() => setOpen(true)}
        onBlur={() => setTimeout(close, 120)}
        onChange={(event) => {
          setText(event.target.value);
          setOpen(true);
          setActiveIndex(-1);
        }}
        onKeyDown={onKeyDown}
        data-testid="tute-form-session"
      />
      <div className="absolute top-1/2 right-1.5 flex -translate-y-1/2 items-center gap-0.5">
        {value ? (
          <button
            type="button"
            aria-label="Clear paid session"
            onClick={() => onChange(null)}
            disabled={disabled}
            className="rounded p-1 text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
            data-testid="tute-form-session-clear"
          >
            <XIcon className="size-3.5" aria-hidden="true" />
          </button>
        ) : null}
        <ChevronDown className="size-4 text-muted-foreground" aria-hidden="true" />
      </div>
      {open ? (
        <ul
          id={listboxId}
          role="listbox"
          aria-label="Paid sessions"
          className="absolute z-20 mt-1 max-h-60 w-full overflow-y-auto rounded-lg border border-border bg-popover p-1 text-sm shadow-md"
          data-testid="tute-form-session-options"
        >
          {query.isPending || (!settled && !query.isError) ? (
            <li role="presentation" className="px-2 py-1.5 text-muted-foreground">
              Loading sessions…
            </li>
          ) : query.isError ? (
            <li role="presentation" className="px-2 py-1.5 text-destructive">
              Paid sessions could not be loaded.
            </li>
          ) : options.length === 0 ? (
            <li role="presentation" className="px-2 py-1.5 text-muted-foreground">
              {search ? "No paid sessions match." : "This course has no paid sessions."}
            </li>
          ) : (
            options.map((session, index) => (
              <li
                key={session.lessonId}
                id={`${listboxId}-${index}`}
                role="option"
                aria-selected={session.lessonId === value}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => pick(session)}
                onMouseEnter={() => setActiveIndex(index)}
                className={cn(
                  "flex cursor-pointer flex-col rounded-md px-2 py-1.5",
                  index === activeIndex && "bg-muted",
                  session.lessonId === value && "font-medium"
                )}
              >
                <span>{optionLabel(session)}</span>
                <span className="text-xs text-muted-foreground">
                  {[session.moduleTitle, session.price !== null ? formatTutePrice(session.price, session.currency) : null]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
              </li>
            ))
          )}
        </ul>
      ) : null}
    </div>
  );
}
