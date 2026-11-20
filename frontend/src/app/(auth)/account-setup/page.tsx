"use client";

import { Suspense } from "react";
import { SetPasswordCard } from "@/components/auth/set-password-card";
import { LoadingState } from "@/components/states/loading-state";

import { useT } from "@/i18n/i18n-provider";
/** AUTH-PWD-01: an invited account (e.g. a new tenant's first Tenant Admin) sets its first password. */
export default function AccountSetupPage() {
  const t = useT();
  return (
    <Suspense fallback={<LoadingState label={t("auth.accountSetup.checkingYourLink")} />}>
      <SetPasswordCard variant="setup" />
    </Suspense>
  );
}
