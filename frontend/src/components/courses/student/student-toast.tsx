"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { AlertCircle, CheckCircle2, XIcon } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Per-page toast for the student course screens (the project has no toast
 * library). Both live regions stay mounted and only their content changes so
 * screen readers announce reliably: `status` for success, `alert` for errors.
 */
export interface StudentToastMessage {
  kind: "success" | "error";
  text: string;
}

export function useStudentToast() {
  const [message, setMessage] = useState<StudentToastMessage | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const dismiss = useCallback(() => {
    if (timer.current) clearTimeout(timer.current);
    setMessage(null);
  }, []);

  const show = useCallback((next: StudentToastMessage) => {
    if (timer.current) clearTimeout(timer.current);
    setMessage(next);
    timer.current = setTimeout(() => setMessage(null), next.kind === "error" ? 8000 : 5000);
  }, []);

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    []
  );

  return { message, show, dismiss };
}

export function StudentToast({
  message,
  onDismiss,
}: {
  message: StudentToastMessage | null;
  onDismiss: () => void;
}) {
  const box = (current: StudentToastMessage) => (
    <div
      data-testid="student-toast"
      data-kind={current.kind}
      className={cn(
        "pointer-events-auto flex max-w-lg items-start gap-2 rounded-lg border bg-background px-4 py-3 text-sm shadow-lg",
        current.kind === "error" ? "border-destructive/40 text-destructive" : "border-border text-foreground"
      )}
    >
      {current.kind === "error" ? (
        <AlertCircle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
      ) : (
        <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-green-600" aria-hidden="true" />
      )}
      <span className="flex-1">{current.text}</span>
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
      <div role="status" aria-live="polite" aria-atomic="true">
        {message?.kind === "success" ? box(message) : null}
      </div>
      <div role="alert" aria-live="assertive" aria-atomic="true">
        {message?.kind === "error" ? box(message) : null}
      </div>
    </div>
  );
}
