"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth/auth-context";
import { LoadingState } from "@/components/states/loading-state";
import { SupportSessionEnded } from "@/components/auth/support-session-ended";

/**
 * ADR-033 support-tab entry point. Opened in a new tab by "Log as Student"
 * after the server set the HttpOnly handoff cookie. Exchanges it for an
 * in-memory impersonation token (`POST /auth/impersonation/token`), marks
 * this tab as a support tab (non-credential sessionStorage marker) and moves
 * on to the student dashboard with a client-side navigation so the in-memory
 * session survives. Nothing credential-like is ever read from or put in the URL.
 */
export default function ImpersonatePage() {
  const { startImpersonation } = useAuth();
  const router = useRouter();
  const [failed, setFailed] = useState(false);
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    startImpersonation()
      .then(() => router.replace("/student/dashboard"))
      .catch(() => setFailed(true));
  }, [startImpersonation, router]);

  if (failed) return <SupportSessionEnded />;
  return <LoadingState label="Starting the support session…" />;
}
