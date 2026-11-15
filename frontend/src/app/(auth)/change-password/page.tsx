"use client";

import { useState } from "react";
import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2 } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import { useChangePassword } from "@/lib/api/passwords";
import { useAuth } from "@/lib/auth/auth-context";
import { tenantDashboardPath } from "@/lib/auth/dashboard-paths";
import { changePasswordSchema, type ChangePasswordFormValues } from "@/lib/validation/auth";
import { RouteGuard } from "@/components/auth/route-guard";
import { ErrorState } from "@/components/states/error-state";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

import { useT, type TranslateFn } from "@/i18n/i18n-provider";
import { translateSource } from "@/i18n/translate-source";
type FieldName = keyof ChangePasswordFormValues;

const FIELDS = (t: TranslateFn): { name: FieldName; label: string; autoComplete: string; hint?: string }[] => ([
  { name: "currentPassword", label: t("auth.changePassword.currentPassword"), autoComplete: "current-password" },
  { name: "newPassword", label: t("auth.changePassword.newPassword"), autoComplete: "new-password", hint: t("auth.changePassword.atLeast8Characters") },
  { name: "confirmPassword", label: t("auth.changePassword.confirmNewPassword"), autoComplete: "new-password" },
]);

/**
 * AUTH-PWD-01 — change password for any signed-in tenant user. Also where an
 * account with a temporary password is sent (after login, or on any
 * `403 PASSWORD_CHANGE_REQUIRED`): the backend lets such an account do nothing
 * else until this succeeds. Other devices are signed out; this one stays in.
 */
function ChangePasswordForm() {
  const t = useT();
  const { session } = useAuth();
  const mutation = useChangePassword();
  const [done, setDone] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<ChangePasswordFormValues>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: "", newPassword: "", confirmPassword: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setFailure(null);
    try {
      await mutation.mutateAsync({ currentPassword: values.currentPassword, newPassword: values.newPassword });
      reset();
      setDone(true);
    } catch (error) {
      if (isApiClientError(error) && error.fieldErrors.length > 0) {
        for (const fieldError of error.fieldErrors) {
          if (fieldError.field === "currentPassword" || fieldError.field === "newPassword") {
            const label = fieldError.field === "currentPassword" ? t("auth.changePassword.currentPassword") : "Password";
            setError(fieldError.field, { type: "server", message: `${label} ${fieldError.message}.` });
          }
        }
        return;
      }
      setFailure(isApiClientError(error) ? error.message : t("auth.changePassword.somethingWentWrongPlease"));
    }
  });

  const dashboard = tenantDashboardPath(session?.role ?? null) ?? "/login";

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("auth.changePassword.changeYourPassword")}</CardTitle>
        <CardDescription>
          {t("auth.changePassword.ifYourPasswordWas")}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {done ? (
          <>
            <Alert role="status">
              <CheckCircle2 aria-hidden="true" />
              <AlertDescription>{t("auth.changePassword.yourPasswordHasBeen")}</AlertDescription>
            </Alert>
            <Link href={dashboard} className={buttonVariants()}>
              {t("auth.changePassword.continue")}
            </Link>
          </>
        ) : (
          <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
            {failure ? <ErrorState message={failure} /> : null}
            {FIELDS(t).map((field) => {
              const error = translateSource(t, errors[field.name]?.message);
              const describedBy = [field.hint ? `${field.name}-hint` : null, error ? `${field.name}-error` : null]
                .filter(Boolean)
                .join(" ");
              return (
                <div key={field.name} className="flex flex-col gap-1.5">
                  <Label htmlFor={field.name}>{field.label}</Label>
                  <Input
                    id={field.name}
                    type="password"
                    autoComplete={field.autoComplete}
                    aria-invalid={error ? true : undefined}
                    aria-describedby={describedBy || undefined}
                    {...register(field.name)}
                  />
                  {field.hint ? (
                    <p id={`${field.name}-hint`} className="text-xs text-muted-foreground">
                      {field.hint}
                    </p>
                  ) : null}
                  {error ? (
                    <p id={`${field.name}-error`} role="alert" className="text-sm text-destructive">
                      {error}
                    </p>
                  ) : null}
                </div>
              );
            })}
            <Button type="submit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
              {mutation.isPending ? t("auth.changePassword.saving") : t("auth.changePassword.changePassword")}
            </Button>
            <Link href={dashboard} className="text-center text-sm font-medium text-muted-foreground hover:text-foreground hover:underline">
              {t("auth.changePassword.back")}
            </Link>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

export default function ChangePasswordPage() {
  return (
    <RouteGuard kind="tenant" loginPath="/login">
      <ChangePasswordForm />
    </RouteGuard>
  );
}
