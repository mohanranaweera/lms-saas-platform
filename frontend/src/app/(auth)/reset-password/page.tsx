"use client";

import { Suspense } from "react";
import { SetPasswordCard } from "@/components/auth/set-password-card";
import { LoadingState } from "@/components/states/loading-state";

import { useT } from "@/i18n/i18n-provider";
/** AUTH-PWD-01: forgot-password link - choose a new password. */
export default function ResetPasswordPage() {
  const t = useT();
  return (
    <Suspense fallback={<LoadingState label={t("auth.reset.checkingYourLink")} />}>
      <SetPasswordCard variant="reset" />
    </Suspense>
  );
}
