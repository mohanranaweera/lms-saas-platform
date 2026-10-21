"use client";

import { Suspense } from "react";
import { SetPasswordCard } from "@/components/auth/set-password-card";
import { LoadingState } from "@/components/states/loading-state";

/** AUTH-PWD-01: forgot-password link - choose a new password. */
export default function ResetPasswordPage() {
  return (
    <Suspense fallback={<LoadingState label="Checking your link…" />}>
      <SetPasswordCard variant="reset" />
    </Suspense>
  );
}
