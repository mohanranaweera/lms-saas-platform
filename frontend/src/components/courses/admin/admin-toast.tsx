"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { CheckCircle2, AlertCircle, XIcon } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Minimal per-page toast for the admin course screens. The project has no
 * toast library (see `components/ui/live-region.tsx`), so this renders one
 * fixed message at a time: inside a `role="status"` region for success and a
 * `role="alert"` region for errors, auto-dismissed after a few seconds and
 * closable by button. Both regions stay mounted (empty when idle) and only
 * their content changes, because screen readers often miss a live region
 * that is inserted together with its text.
 */
export interface AdminToastMessage {
  kind: "success" | "error";
  text: string;
  /** Optional follow-up button (e.g. "Publish now"). Clicking it dismisses the toast. */
  action?: { label: string; onClick: () => void; testId?: string };
}

export function useAdminToast() {
  const [message, setMessage] = useState<AdminToastMessage | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const dismiss = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
    setMessage(null);
  }, []);

  const show = useCallback((next: AdminToastMessage) => {
    if (timer.current) clearTimeout(timer.current);
    setMessage(next);
    timer.current = setTimeout(() => setMessage(null), next.kind === "error" || next.action ? 8000 : 4000);
  }, []);

  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);

  return { message, show, dismiss };
}

export function AdminToast({
  message,
  onDismiss,
}: {
  message: AdminToastMessage | null;
  onDismiss: () => void;
}) {
  const box = (current: AdminToastMessage) => (
    <div
      data-testid="admin-toast"
      data-kind={current.kind}
      className={cn(
        "pointer-events-auto flex max-w-lg items-start gap-2 rounded-lg border px-4 py-3 text-sm shadow-lg",
        current.kind === "error"
          ? "border-destructive/40 bg-background text-destructive"
          : "border-border bg-background text-foreground"
      )}
    >
      {current.kind === "error" ? (
        <AlertCircle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
      ) : (
        <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-green-600" aria-hidden="true" />
      )}
      <span className="flex-1">{current.text}</span>
      {current.action ? (
        <button
          type="button"
          onClick={() => {
            const action = current.action;
            onDismiss();
            action?.onClick();
          }}
          className="shrink-0 rounded font-medium text-primary underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
          data-testid={current.action.testId ?? "admin-toast-action"}
        >
          {current.action.label}
        </button>
      ) : null}
      <button
        type="button"
        onClick={onDismiss}
        aria-label="Dismiss notification"
        className="rounded text-muted-foreground outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <XIcon className="size-4" aria-hidden="true" />
      </button>
    </div>
  );

  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-4 z-[60] flex flex-col items-center px-4">
      <div role="status" aria-live="polite" aria-atomic="true" data-testid="admin-toast-status-region">
        {message?.kind === "success" ? box(message) : null}
      </div>
      <div role="alert" aria-live="assertive" aria-atomic="true" data-testid="admin-toast-alert-region">
        {message?.kind === "error" ? box(message) : null}
      </div>
    </div>
  );
}
