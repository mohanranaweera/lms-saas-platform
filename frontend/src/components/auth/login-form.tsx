"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { Eye, EyeOff } from "lucide-react";
import { loginSchema, type LoginFormValues } from "@/lib/validation/auth";
import { useAuth, type LoginResult } from "@/lib/auth/auth-context";
import type { PrincipalKind } from "@/lib/api/auth";
import { isApiClientError } from "@/lib/api/error";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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

/**
 * Shared real login form for both the tenant-branded (`/login`) and Platform
 * Admin (`/platform-admin/login`) sign-in pages — same validation, loading,
 * error, and accessibility behavior, differing only in copy, which endpoint
 * `kind` calls, and the post-login redirect rule.
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

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: "", password: "" },
  });

  const mutation = useMutation<LoginResult, unknown, LoginFormValues>({
    mutationFn: (values) => login(kind, values),
    onSuccess: (result) => {
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
          if (fieldError.field === "email" || fieldError.field === "password") {
            setError(fieldError.field, { type: "server", message: fieldError.message });
          }
        }
      }
    },
  });

  const sessionExpired = searchParams.get("reason") === "session_expired";
  const apiError = isApiClientError(mutation.error) ? mutation.error : null;

  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {sessionExpired ? (
          <Alert role="status">
            <AlertTitle>Your session has expired</AlertTitle>
            <AlertDescription>Sign in again to continue.</AlertDescription>
          </Alert>
        ) : null}

        {apiError ? (
          <Alert variant="destructive">
            <AlertTitle>Sign-in failed</AlertTitle>
            {/* Backend copy only — never invent a more specific message than
                `error.message` here (docs/api/identity-access-service.md: the
                same generic text covers INVALID_CREDENTIALS / TENANT_UNAVAILABLE
                / USER_SUSPENDED by design, anti-enumeration). */}
            <AlertDescription>{apiError.message}</AlertDescription>
          </Alert>
        ) : null}

        {unresolvedRole ? (
          <Alert role="status">
            <AlertTitle>Signed in</AlertTitle>
            <AlertDescription>
              No dashboard is available for your role ({unresolvedRole}) yet. Contact
              your administrator.
            </AlertDescription>
          </Alert>
        ) : null}

        <form
          className="flex flex-col gap-4"
          noValidate
          onSubmit={handleSubmit((values) => mutation.mutate(values))}
        >
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
