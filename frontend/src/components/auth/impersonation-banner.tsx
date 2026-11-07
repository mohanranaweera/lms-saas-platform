"use client";

import { useState } from "react";
import { Eye, LogOut, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/lib/auth/auth-context";
import { formatZonedDateTime } from "@/lib/datetime";

/**
 * ADR-033 §7: persistent, visually loud banner for a support ("Log as
 * Student") tab. Rendered by the student layout only while the in-memory
 * session is an impersonation session. Exit ends the session server-side,
 * clears the query cache and the tab marker, and returns to the admin list.
 */
export function ImpersonationBanner() {
  const { session, endImpersonation, readOnlyNotice, dismissReadOnlyNotice } = useAuth();
  const [exiting, setExiting] = useState(false);
  const info = session?.impersonation;
  if (!info) return null;

  const endsAt = formatZonedDateTime(info.expiresAt).slice(11);
  const student = info.student;
  const label = student.indexNumber ? `${student.fullName} (${student.indexNumber})` : student.fullName;

  return (
    <div className="sticky top-0 z-40" data-testid="impersonation-banner">
      <div
        role="region"
        aria-label="Support session"
        className="flex flex-col gap-2 border-b-2 border-amber-600 bg-amber-100 px-4 py-2 text-amber-950 sm:flex-row sm:items-center sm:justify-between dark:bg-amber-950 dark:text-amber-50"
      >
        <p className="flex items-start gap-2 text-sm font-medium">
          <Eye className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          <span>
            Viewing as {label} — read-only support session · ends {endsAt}
            <span className="sr-only"> (started by {info.actorName})</span>
          </span>
        </p>
        <Button
          type="button"
          size="sm"
          className="bg-amber-900 text-white hover:bg-amber-800"
          disabled={exiting}
          onClick={() => {
            setExiting(true);
            void endImpersonation();
          }}
        >
          <LogOut aria-hidden="true" />
          {exiting ? "Exiting…" : "Exit to admin"}
        </Button>
      </div>
      <div role="status" aria-live="polite">
        {readOnlyNotice ? (
          <div className="flex items-center justify-between gap-3 border-b border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-950 dark:bg-amber-900 dark:text-amber-50">
            <span data-testid="impersonation-read-only-notice">{readOnlyNotice}</span>
            <Button
              type="button"
              size="icon-sm"
              variant="ghost"
              aria-label="Dismiss notice"
              onClick={dismissReadOnlyNotice}
            >
              <X aria-hidden="true" />
            </Button>
          </div>
        ) : null}
      </div>
    </div>
  );
}
