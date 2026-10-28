"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useAuth } from "@/lib/auth/auth-context";

function dashboardHref(role: string | null, kind: string) {
  if (kind === "platform-admin") return "/platform-admin/dashboard";
  if (role === "STUDENT") return "/student/courses";
  if (role === "TEACHER") return "/teacher/dashboard";
  return "/tenant-admin/dashboard";
}

export function PublicHeaderNav() {
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

  async function handleLogout() {
    if (!session || loggingOut) return;
    setLoggingOut(true);
    try {
      await logout(session.kind);
    } finally {
      setLoggingOut(false);
    }
  }

  return (
    <nav aria-label="Account" className="flex flex-wrap items-center gap-x-4 gap-y-2">
      <Link href="/teachers" className="text-sm font-medium text-foreground hover:underline">
        Teachers
      </Link>
      <Link href="/courses" className="text-sm font-medium text-foreground hover:underline">
        Courses
      </Link>
      {!checkingSession && session ? (
        <>
          <Link
            href={dashboardHref(session.role, session.kind)}
            className="text-sm font-medium text-foreground hover:underline"
          >
            Dashboard
          </Link>
          <button
            type="button"
            onClick={() => void handleLogout()}
            disabled={loggingOut}
            className="text-sm font-medium text-foreground hover:underline disabled:cursor-not-allowed disabled:opacity-60"
          >
            {loggingOut ? "Signing out…" : "Sign out"}
          </button>
        </>
      ) : !checkingSession ? (
        <>
          <Link href="/login" className="text-sm font-medium text-foreground hover:underline">
            Sign in
          </Link>
          <Link
            href="/register-institute"
            className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/80"
          >
            Get started
          </Link>
        </>
      ) : null}
    </nav>
  );
}
