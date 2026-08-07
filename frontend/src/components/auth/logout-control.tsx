"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { LogOut } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { isApiClientError } from "@/lib/api/error";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { useAuth } from "@/lib/auth/auth-context";
import type { PrincipalKind } from "@/lib/api/auth";

interface LogoutControlProps {
  kind: PrincipalKind;
  /**
   * Named portal/account scope shown in the confirmation dialog so it's
   * unambiguous which account is being signed out of (.claude/rules/ui-ux.md
   * §1) — e.g. "Platform Admin", "Tenant Admin".
   */
  portalLabel: string;
  /**
   * Tenant Admin and Platform Admin require a confirmation step before logging
   * out; Student and Teacher do not (plan §11 — Logout Confirmation scoping).
   */
  requireConfirmation?: boolean;
  className?: string;
}

function loginPathFor(kind: PrincipalKind): string {
  return kind === "platform-admin" ? "/platform-admin/login" : "/login";
}

export function LogoutControl({
  kind,
  portalLabel,
  requireConfirmation = false,
  className,
}: LogoutControlProps) {
  const { logout } = useAuth();
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function performLogout() {
    setPending(true);
    setError(null);
    try {
      await logout(kind);
      router.replace(loginPathFor(kind));
    } catch (caught) {
      // Local session state is already cleared by `logout()` even on failure
      // (this tab must stop acting as the user regardless of the network
      // outcome) — but a failed server-side revoke must be surfaced, not
      // silently treated as a normal sign-out, since the `device_session` row
      // may still be active. Don't auto-redirect on failure: let the user see
      // the notice and choose when to leave.
      setError(
        isApiClientError(caught)
          ? caught.message
          : "You were signed out on this device, but we couldn't confirm it with the server. Please check your connection."
      );
    } finally {
      setPending(false);
    }
  }

  const errorNotice = error ? (
    <Alert variant="destructive">
      <AlertDescription>
        {error}{" "}
        <a href={loginPathFor(kind)} className="font-medium underline underline-offset-2">
          Continue to sign-in
        </a>
      </AlertDescription>
    </Alert>
  ) : null;

  if (!requireConfirmation) {
    return (
      <div className={className}>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={performLogout}
          disabled={pending}
          aria-busy={pending}
        >
          <LogOut aria-hidden="true" />
          {pending ? "Signing out…" : "Sign out"}
        </Button>
        {errorNotice}
      </div>
    );
  }

  return (
    <div className={className}>
      <AlertDialog>
        <AlertDialogTrigger
          render={<Button type="button" variant="ghost" size="sm" disabled={pending} />}
        >
          <LogOut aria-hidden="true" />
          Sign out
        </AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Sign out of {portalLabel}?</AlertDialogTitle>
            <AlertDialogDescription>
              You&apos;ll need to sign in again to access {portalLabel}.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {errorNotice}
          <AlertDialogFooter>
            <AlertDialogClose render={<Button type="button" variant="outline" />}>
              Cancel
            </AlertDialogClose>
            <Button
              type="button"
              variant="destructive"
              onClick={performLogout}
              disabled={pending}
              aria-busy={pending}
            >
              {pending ? "Signing out…" : "Sign out"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
