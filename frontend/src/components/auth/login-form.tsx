"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { AlertCircle, Eye, EyeOff, Info } from "lucide-react";
import { tenantLoginSchema } from "@/lib/validation/auth";
import { useAuth, type LoginResult } from "@/lib/auth/auth-context";
import type { LoginRequest, PrincipalKind } from "@/lib/api/auth";
import { usePublicHome } from "@/lib/api/public-home";
import { isApiClientError } from "@/lib/api/error";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tabs } from "@/components/ui/tabs";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

interface LoginFormProps {
  kind: PrincipalKind;
  title: string;
  description: string;
  /**
   * Resolves the post-login redirect target from the JWT's decoded `role` claim
   * (display/routing convenience only — see lib/auth/jwt.ts). Return `null` when
   * the role has no known dashboard route in this codebase yet.
   */
  resolveDashboardPath: (role: string | null) => string | null;
  showRegisterLink?: boolean;
}

type LoginMethod = "email" | "mobile";

interface LoginFields {
  email: string;
  mobile: string;
  password: string;
}

/**
 * Shared real login form for both the tenant-branded (`/login`) and Platform
 * Admin (`/platform-admin/login`) sign-in pages — same validation, loading,
 * error, and accessibility behavior, differing only in copy, which endpoint
 * `kind` calls, and the post-login redirect rule.
 *
 * ADR-031: tenant sign-in offers EMAIL / MOBILE tabs (Klass parity) unless the
 * institute switched mobile sign-in off (`loginOptions.mobileEnabled` from the
 * host tenant's public home). Platform Admin sign-in stays email-only. The
 * backend still refuses a mobile sign-in when the switch is off.
 */
export function LoginForm({
  kind,
  title,
  description,
  resolveDashboardPath,
  showRegisterLink = false,
}: LoginFormProps) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { login } = useAuth();
  const [showPassword, setShowPassword] = useState(false);
  const [unresolvedRole, setUnresolvedRole] = useState<string | null>(null);
  const [selectedMethod, setSelectedMethod] = useState<LoginMethod>("email");
  const home = usePublicHome();
  const mobileOffered = kind === "tenant" && home.data?.loginOptions?.mobileEnabled !== false && home.isSuccess;
  const method: LoginMethod = mobileOffered ? selectedMethod : "email";

  const {
    register,
    handleSubmit,
    setError,
    clearErrors,
    formState: { errors },
  } = useForm<LoginFields>({
    // The schema follows the selected tab; the other identifier is ignored.
    resolver: zodResolver(tenantLoginSchema(method)),
    defaultValues: { email: "", mobile: "", password: "" },
  });

  const mutation = useMutation<LoginResult, unknown, LoginRequest>({
    mutationFn: (request) => login(kind, request),
    onSuccess: (result) => {
      // AUTH-PWD-01: a temporary password must be replaced first (the backend
      // refuses everything else with 403 PASSWORD_CHANGE_REQUIRED anyway).
      if (kind === "tenant" && result.mustChangePassword) {
        router.push("/change-password");
        return;
      }
      const target = resolveDashboardPath(result.role);
      if (target) {
        router.push(target);
        return;
      }
      setUnresolvedRole(result.role);
    },
    onError: (error) => {
      if (isApiClientError(error) && error.fieldErrors.length > 0) {
        for (const fieldError of error.fieldErrors) {
          if (fieldError.field === "email" || fieldError.field === "mobile" || fieldError.field === "password") {
            setError(fieldError.field, { type: "server", message: fieldError.message });
          }
        }
      }
    },
  });

  const onSubmit = handleSubmit((values) =>
    mutation.mutate(
      method === "mobile"
        ? { mobile: values.mobile.trim(), password: values.password }
        : { email: values.email, password: values.password }
    )
  );

  const sessionExpired = searchParams.get("reason") === "session_expired";
  const apiError = isApiClientError(mutation.error) ? mutation.error : null;

  const emailField = (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor="login-email">Email</Label>
      <Input
        id="login-email"
        type="email"
        autoComplete="email"
        aria-invalid={!!errors.email}
        aria-describedby={errors.email ? "login-email-error" : undefined}
        disabled={mutation.isPending}
        {...register("email")}
      />
      {errors.email ? (
        <p id="login-email-error" className="text-xs text-destructive">
          {errors.email.message}
        </p>
      ) : null}
    </div>
  );

  const mobileField = (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor="login-mobile">Mobile number</Label>
      <Input
        id="login-mobile"
        type="tel"
        inputMode="tel"
        autoComplete="tel"
        placeholder="e.g. 077 123 4567"
        aria-invalid={!!errors.mobile}
        aria-describedby={errors.mobile ? "login-mobile-error" : undefined}
        disabled={mutation.isPending}
        {...register("mobile")}
      />
      {errors.mobile ? (
        <p id="login-mobile-error" className="text-xs text-destructive">
          {errors.mobile.message}
        </p>
      ) : null}
    </div>
  );

  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {sessionExpired ? (
          <Alert role="status">
            <Info aria-hidden="true" />
            <AlertTitle>Your session has expired</AlertTitle>
            <AlertDescription>Sign in again to continue.</AlertDescription>
          </Alert>
        ) : null}

        {apiError ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertTitle>
              {apiError.code === "DEVICE_LIMIT_REACHED"
                ? "Device limit reached"
                : apiError.code === "TOO_MANY_REQUESTS"
                  ? "Too many attempts"
                  : "Sign-in failed"}
            </AlertTitle>
            {/* Backend copy only — never invent a more specific message than
                `error.message` here (docs/api/identity-access-service.md: the
                same generic text covers INVALID_CREDENTIALS / TENANT_UNAVAILABLE
                / USER_SUSPENDED by design, anti-enumeration). */}
            <AlertDescription>{apiError.message}</AlertDescription>
          </Alert>
        ) : null}

        {unresolvedRole ? (
          <Alert role="status">
            <Info aria-hidden="true" />
            <AlertTitle>Signed in</AlertTitle>
            <AlertDescription>
              No dashboard is available for your role ({unresolvedRole}) yet. Contact
              your administrator.
            </AlertDescription>
          </Alert>
        ) : null}

        <form className="flex flex-col gap-4" noValidate onSubmit={onSubmit}>
          {mobileOffered ? (
            <Tabs
              aria-label="Sign in with"
              value={method}
              onValueChange={(next) => {
                setSelectedMethod(next as LoginMethod);
                clearErrors();
                mutation.reset();
              }}
              items={[
                { value: "email", label: "Email", content: emailField },
                { value: "mobile", label: "Mobile", content: mobileField },
              ]}
            />
          ) : (
            emailField
          )}

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="login-password">Password</Label>
            <div className="relative">
              <Input
                id="login-password"
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                aria-invalid={!!errors.password}
                aria-describedby={errors.password ? "login-password-error" : undefined}
                disabled={mutation.isPending}
                className="pr-9"
                {...register("password")}
              />
              <button
                type="button"
                onClick={() => setShowPassword((value) => !value)}
                aria-label={showPassword ? "Hide password" : "Show password"}
                className="absolute inset-y-0 right-0 flex items-center px-2.5 text-muted-foreground hover:text-foreground"
              >
                {showPassword ? (
                  <EyeOff className="size-4" aria-hidden="true" />
                ) : (
                  <Eye className="size-4" aria-hidden="true" />
                )}
              </button>
            </div>
            {errors.password ? (
              <p id="login-password-error" className="text-xs text-destructive">
                {errors.password.message}
              </p>
            ) : null}
            {kind === "tenant" ? (
              <Link href="/forgot-password" className="self-end text-xs font-medium text-muted-foreground hover:text-foreground hover:underline">
                Forgot password?
              </Link>
            ) : null}
          </div>

          <Button
            type="submit"
            className="w-full"
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? "Signing in…" : "Sign in"}
          </Button>
        </form>

        {showRegisterLink ? (
          <p className="text-center text-sm text-muted-foreground">
            Don&apos;t have an account?{" "}
            <Link href="/register" className="font-medium text-foreground hover:underline">
              Create one
            </Link>
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}
