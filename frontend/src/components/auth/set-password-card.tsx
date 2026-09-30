"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, CheckCircle2 } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import { useCompletePasswordToken, verifyPasswordToken, type PasswordTokenInfo } from "@/lib/api/passwords";
import { setPasswordSchema, type SetPasswordFormValues } from "@/lib/validation/auth";
import { LoadingState } from "@/components/states/loading-state";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

type Phase =
  | { kind: "checking" }
  | { kind: "invalid" }
  | { kind: "ready"; token: string; info: PasswordTokenInfo }
  | { kind: "done" };

const COPY = {
  setup: {
    title: "Set up your account",
    description: "Choose a password to finish setting up your account.",
    submit: "Set password",
    done: "Your account is ready. Sign in with your new password.",
  },
  reset: {
    title: "Choose a new password",
    description: "Enter a new password for your account.",
    submit: "Reset password",
    done: "Your password has been changed. Sign in with your new password.",
  },
} as const;

/**
 * AUTH-PWD-01 — shared by `/account-setup` (invitation) and `/reset-password`
 * (forgot password). Reads the one-time `#token=` fragment once, removes it from the
 * address bar straight away, verifies it with the backend, then lets the user
 * choose a password. Every unusable link shows the same generic message - the
 * backend decides validity, never this page.
 */
export function SetPasswordCard({ variant }: { variant: "setup" | "reset" }) {
  const copy = COPY[variant];
  const pathname = usePathname();
  const [phase, setPhase] = useState<Phase>({ kind: "checking" });
  const started = useRef(false);
  const complete = useCompletePasswordToken();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<SetPasswordFormValues>({
    resolver: zodResolver(setPasswordSchema),
    defaultValues: { newPassword: "", confirmPassword: "" },
  });

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    // The link carries the token in the fragment (#token=...), which browsers
    // never send to a server - so it can't reach any access log (security
    // review M-2). Read it once, then drop it from the address bar and history.
    const token = new URLSearchParams(window.location.hash.slice(1)).get("token");
    window.history.replaceState(null, "", pathname);
    const check = token ? verifyPasswordToken(token) : Promise.reject(new Error("no token"));
    check
      .then((info) => setPhase({ kind: "ready", token: token as string, info }))
      .catch(() => setPhase({ kind: "invalid" }));
  }, [pathname]);

  const onSubmit = handleSubmit(async (values) => {
    if (phase.kind !== "ready") return;
    try {
      await complete.mutateAsync({ token: phase.token, newPassword: values.newPassword });
      setPhase({ kind: "done" });
    } catch (error) {
      if (isApiClientError(error) && error.code === "TOKEN_INVALID") {
        setPhase({ kind: "invalid" });
        return;
      }
      if (isApiClientError(error)) {
        const field = error.fieldErrors.find((fieldError) => fieldError.field === "newPassword");
        setError("newPassword", { type: "server", message: field ? `Password ${field.message}.` : error.message });
        return;
      }
      setError("newPassword", { type: "server", message: "Something went wrong. Please try again." });
    }
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>{copy.title}</CardTitle>
        {phase.kind === "ready" ? <CardDescription>{copy.description}</CardDescription> : null}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {phase.kind === "checking" ? <LoadingState label="Checking your link…" /> : null}

        {phase.kind === "invalid" ? (
          <>
            <Alert variant="destructive">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>
                This link is invalid or has expired. Links work once and only for a limited time.
              </AlertDescription>
            </Alert>
            <p className="text-sm text-muted-foreground">
              {variant === "setup" ? "Ask your platform administrator to send a new invitation, or " : "You can "}
              <Link href="/forgot-password" className="font-medium text-foreground underline">
                request a new link
              </Link>
              .
            </p>
          </>
        ) : null}

        {phase.kind === "ready" ? (
          <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
            <p className="text-sm text-muted-foreground">
              Account: <span className="font-medium text-foreground">{phase.info.emailMasked}</span>
              <br />
              Link valid until {formatDateTime(phase.info.expiresAt)}.
            </p>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="new-password">New password</Label>
              <Input
                id="new-password"
                type="password"
                autoComplete="new-password"
                aria-invalid={errors.newPassword ? true : undefined}
                aria-describedby={errors.newPassword ? "new-password-hint new-password-error" : "new-password-hint"}
                {...register("newPassword")}
              />
              <p id="new-password-hint" className="text-xs text-muted-foreground">
                At least 8 characters, and not your email address.
              </p>
              {errors.newPassword ? (
                <p id="new-password-error" role="alert" className="text-sm text-destructive">
                  {errors.newPassword.message}
                </p>
              ) : null}
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="confirm-password">Confirm new password</Label>
              <Input
                id="confirm-password"
                type="password"
                autoComplete="new-password"
                aria-invalid={errors.confirmPassword ? true : undefined}
                aria-describedby={errors.confirmPassword ? "confirm-password-error" : undefined}
                {...register("confirmPassword")}
              />
              {errors.confirmPassword ? (
                <p id="confirm-password-error" role="alert" className="text-sm text-destructive">
                  {errors.confirmPassword.message}
                </p>
              ) : null}
            </div>
            <Button type="submit" disabled={complete.isPending} aria-busy={complete.isPending}>
              {complete.isPending ? "Saving…" : copy.submit}
            </Button>
          </form>
        ) : null}

        {phase.kind === "done" ? (
          <>
            <Alert role="status">
              <CheckCircle2 aria-hidden="true" />
              <AlertDescription>{copy.done}</AlertDescription>
            </Alert>
            <Link href="/login" className={buttonVariants()}>
              Go to sign in
            </Link>
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
