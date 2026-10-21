"use client";

import { Suspense } from "react";
import { SetPasswordCard } from "@/components/auth/set-password-card";
import { LoadingState } from "@/components/states/loading-state";

/** AUTH-PWD-01: an invited account (e.g. a new tenant's first Tenant Admin) sets its first password. */
export default function AccountSetupPage() {
  return (
    <Suspense fallback={<LoadingState label="Checking your link…" />}>
      <SetPasswordCard variant="setup" />
    </Suspense>
  );
}
