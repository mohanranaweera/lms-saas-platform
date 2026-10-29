"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Button, buttonVariants } from "@/components/ui/button";
import { useAuth } from "@/lib/auth/auth-context";
import { cn } from "@/lib/utils";

function dashboardHref(role: string | null, kind: string) {
  if (kind === "platform-admin") return "/platform-admin/dashboard";
  if (role === "STUDENT") return "/student/courses";
  if (role === "TEACHER") return "/teacher/dashboard";
  return "/tenant-admin/dashboard";
}

/**
 * Restores an existing session once when the public chrome mounts, so a
 * signed-in visitor sees "Dashboard / Sign out" instead of Login/Register.
 * Purely UX — every portal route re-checks the session server-side.
 */
export function usePublicSession() {
  const { session, ensureAccessToken, logout } = useAuth();
  const [checkingSession, setCheckingSession] = useState(true);
  const [loggingOut, setLoggingOut] = useState(false);

  useEffect(() => {
    let cancelled = false;

    async function restoreSession() {
      try {
        await ensureAccessToken("tenant");
      } catch {
        try {
          await ensureAccessToken("platform-admin");
        } catch {
          // Anonymous visitor.
        }
      } finally {
        if (!cancelled) setCheckingSession(false);
      }
    }

    void restoreSession();
    return () => {
      cancelled = true;
    };
    // Auth restoration runs once when public chrome mounts.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function signOut() {
    if (!session || loggingOut) return;
    setLoggingOut(true);
    try {
      await logout(session.kind);
    } finally {
      setLoggingOut(false);
    }
  }

  return { session, checkingSession, loggingOut, signOut };
}

export type PublicSessionState = ReturnType<typeof usePublicSession>;

/**
 * Right-hand account actions. `tenantResolved=false` (no institute matched the
 * host — e.g. the platform's own domain) offers institute self-registration
 * instead of student registration.
 */
export function PublicAccountActions({
  state,
  tenantResolved,
  layout = "row",
}: {
  state: PublicSessionState;
  tenantResolved: boolean;
  layout?: "row" | "stack";
}) {
  const { session, checkingSession, loggingOut, signOut } = state;
  const wrapper = cn("flex gap-2", layout === "stack" ? "flex-col items-stretch" : "items-center");
  const full = layout === "stack" ? "w-full justify-center" : undefined;

  if (checkingSession) return <div className={cn(wrapper, "min-h-8")} aria-hidden="true" />;

  if (session) {
    return (
      <div className={wrapper}>
        <Link href={dashboardHref(session.role, session.kind)} className={cn(buttonVariants({ size: "lg" }), full)}>
          Dashboard
        </Link>
        <Button size="lg" variant="outline" className={full} onClick={() => void signOut()} disabled={loggingOut}>
          {loggingOut ? "Signing out…" : "Sign out"}
        </Button>
      </div>
    );
  }

  return (
    <div className={wrapper}>
      <Link href="/login" className={cn(buttonVariants({ variant: "outline", size: "lg" }), cn("border-primary text-primary hover:text-primary", full))}>
        {tenantResolved ? "Login" : "Sign in"}
      </Link>
      {tenantResolved ? (
        <Link href="/register" className={cn(buttonVariants({ size: "lg" }), cn("tenant-primary-hover", full))}>
          Register
        </Link>
      ) : (
        <Link href="/register-institute" className={cn(buttonVariants({ size: "lg" }), full)}>
          Get started
        </Link>
      )}
    </div>
  );
}
