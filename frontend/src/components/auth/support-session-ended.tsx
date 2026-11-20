"use client";

import { ShieldOff } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * ADR-033: shown in a support ("Log as Student") tab once its session can no
 * longer be used — expired (30-min limit), ended from the banner elsewhere,
 * replaced by a newer support session, or the admin's own sign-in ended. The
 * tab never silently becomes an admin or student tab again.
 */
export function SupportSessionEnded() {
  const { leaveEndedImpersonation } = useAuth();
  return (
    <main
      className="flex min-h-svh flex-1 items-center justify-center p-6"
      data-testid="support-session-ended"
    >
      <section
        role="alert"
        className="flex max-w-md flex-col items-center gap-4 rounded-xl border border-border bg-card p-8 text-center shadow-sm"
      >
        <ShieldOff className="size-10 text-muted-foreground" aria-hidden="true" />
        <h1 className="text-xl font-semibold text-foreground">Support session ended</h1>
        <p className="text-sm text-muted-foreground">
          This read-only support session is no longer active. It may have reached its 30-minute
          limit, been ended from another tab, or been replaced by a newer support session.
        </p>
        <Button type="button" onClick={leaveEndedImpersonation}>
          Back to students
        </Button>
      </section>
    </main>
  );
}
