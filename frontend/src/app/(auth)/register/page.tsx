"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2, Clock, Lock } from "lucide-react";
import {
  buildStudentRegistrationSchema,
  registrationOtpSchema,
  STUDENT_REGISTRATION_DEFAULT_VALUES,
  type StudentRegistrationFormValues,
  type RegistrationOtpFormValues,
} from "@/lib/validation/student-registration";
import {
  useRegisterStudent,
  useSendRegistrationOtp,
  useStudentRegistrationPolicy,
  useVerifyRegistrationOtp,
  type StudentRegistrationInput,
  type StudentRegistrationPolicy,
} from "@/lib/api/student-registration";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
} from "@/components/ui/card";
import { ErrorState } from "@/components/states/error-state";
import { EmptyState } from "@/components/states/empty-state";
import { LoadingState } from "@/components/states/loading-state";

/**
 * Real, tenant-driven student self-registration (Wave 3, PAR-03-01 —
 * replaces the previous disabled placeholder shell).
 *
 * Renders conditionally from `GET
 * /api/v1/public/tenant-config/student-registration-policy`
 * (`useStudentRegistrationPolicy`, `lib/api/student-registration.ts`),
 * fetched once on mount before any field renders — an anonymous visitor
 * never sees a field, an OTP step, or a closed-institute message that
 * doesn't match this tenant's actual policy:
 *   - `publicRegistrationEnabled=false` -> the "registration is not open"
 *     state renders in place of the form; no submit attempt is made.
 *   - `requireGuardianInfo`/`requireSchool`/`requireGrade`/`requireStream`/
 *     `requireMobile` each drive whether the matching field is validated
 *     (and labeled) as required vs. optional, via
 *     `buildStudentRegistrationSchema`.
 *   - `otpRequired=true` reveals the email-OTP step (send + verify) between
 *     "Create account" and the actual registration submission; when
 *     `false`, submitting registers immediately.
 * The previous adaptive "submit and infer policy from the error response"
 * workaround (matching a specific 404/409 message to discover policy) is
 * gone now that the policy is known up front. Submit-time failures are
 * still handled below — a 400 with `fieldErrors` (e.g. a tenant-specific
 * rule this schema doesn't know about) still maps inline onto the matching
 * field, and a 409 duplicate-email conflict still maps onto the email field
 * — those are genuine backend responses to a real submit, not policy
 * discovery.
 *
 * Post-submit state differs by `pendingApproval` on the registration
 * response itself (not the prefetched policy) — that field is the
 * backend's confirmation, at the exact moment of registration, of what its
 * `approval_required` policy produced for this account; using the live
 * response instead of the earlier prefetch avoids ever describing an
 * account's status from a possibly-stale read. A pending account never
 * auto-signs-in and this page never claims the account is active;
 * activation is a backend-confirmed event only (staff `activate` call),
 * exactly like `register-institute/page.tsx`'s equivalent "not yet active"
 * framing for tenant applications.
 */

type Step = "form" | "otp" | "success";

interface PageErrorState {
  message: string;
  code?: string;
  fieldErrors?: FieldError[];
}

const KNOWN_FIELD_NAMES = new Set<string>([
  "name",
  "email",
  "password",
  "guardianName",
  "guardianPhone",
  "school",
  "grade",
  "stream",
  "mobile",
]);

function toRegistrationInput(values: StudentRegistrationFormValues): StudentRegistrationInput {
  return {
    name: values.name,
    email: values.email,
    password: values.password,
    guardianName: values.guardianName?.trim() ? values.guardianName.trim() : undefined,
    guardianPhone: values.guardianPhone?.trim() ? values.guardianPhone.trim() : undefined,
    school: values.school?.trim() ? values.school.trim() : undefined,
    grade: values.grade?.trim() ? values.grade.trim() : undefined,
    stream: values.stream?.trim() ? values.stream.trim() : undefined,
    mobile: values.mobile?.trim() ? values.mobile.trim() : undefined,
  };
}

export default function RegisterPage() {
  const router = useRouter();
  const policyQuery = useStudentRegistrationPolicy();

  if (policyQuery.status === "pending") {
    return (
      <Card>
        <CardContent className="py-8">
          <LoadingState label="Loading registration options…" />
        </CardContent>
      </Card>
    );
  }

  if (policyQuery.status === "error") {
    return (
      <Card>
        <CardContent className="py-8">
          <ErrorState
            message={
              isApiClientError(policyQuery.error)
                ? policyQuery.error.message
                : "We couldn't load this institute's registration settings. Please try again."
            }
            code={isApiClientError(policyQuery.error) ? policyQuery.error.code : undefined}
            onRetry={() => policyQuery.refetch()}
          />
        </CardContent>
      </Card>
    );
  }

  const policy = policyQuery.data;

  if (!policy.publicRegistrationEnabled) {
    return (
      <Card>
        <CardHeader>
          <h1 data-slot="card-title" className="font-heading text-base leading-snug font-medium">
            Registration is not open
          </h1>
        </CardHeader>
        <CardContent>
          <EmptyState
            icon={<Lock className="size-6" aria-hidden="true" />}
            title="Self-registration isn't available"
            description="This institute isn't accepting new student sign-ups right now. Contact your institute administrator for an account."
            action={{ label: "Back to sign in", onClick: () => router.push("/login") }}
          />
        </CardContent>
      </Card>
    );
  }

  return <RegisterFormFlow policy={policy} />;
}

interface RegisterFormFlowProps {
  policy: StudentRegistrationPolicy;
}

function RegisterFormFlow({ policy }: RegisterFormFlowProps) {
  const [step, setStep] = useState<Step>("form");
  const [pageError, setPageError] = useState<PageErrorState | null>(null);
  const [pendingApproval, setPendingApproval] = useState(false);
  const [heldValues, setHeldValues] = useState<StudentRegistrationFormValues | null>(null);

  const {
    register,
    handleSubmit,
    setError,
    getValues,
    formState: { errors, isDirty },
  } = useForm<StudentRegistrationFormValues>({
    resolver: zodResolver(buildStudentRegistrationSchema(policy)),
    defaultValues: STUDENT_REGISTRATION_DEFAULT_VALUES,
  });

  const registerMutation = useRegisterStudent();
  const sendOtpMutation = useSendRegistrationOtp();
  const verifyOtpMutation = useVerifyRegistrationOtp();

  // Warn before an accidental tab close/navigation once the visitor has
  // started filling in a real registration form — a UX safeguard, never a
  // substitute for the backend's own authoritative state (per .claude/rules/ui-ux.md §34).
  useEffect(() => {
    if (!isDirty || step !== "form") return;
    function handleBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
    }
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [isDirty, step]);

  // `beforeunload` above only covers a tab close/reload — it never fires for
  // in-app navigation via the Next.js client router (the "Sign in" link
  // below) or a same-page step reset (the OTP step's "Use a different
  // email" button), both of which would otherwise discard a filled-in form
  // with zero warning. No confirmation-dialog primitive/convention already
  // exists elsewhere in this codebase for "leave a dirty form via in-app
  // navigation" (unlike the AlertDialog Escape-key guards used for
  // in-progress *mutations*, e.g. `refund-dialog.tsx`), so this uses a plain
  // `window.confirm` — the simplest option, not a new bespoke UI pattern.
  function confirmDiscardUnsavedChanges(): boolean {
    if (!isDirty) return true;
    return window.confirm(
      "You have unsaved changes to this registration form. Leave without saving?"
    );
  }

  function applySubmitError(error: unknown) {
    if (isApiClientError(error)) {
      if (error.fieldErrors.length > 0) {
        const unmapped = error.fieldErrors.filter(
          (fieldError) => !KNOWN_FIELD_NAMES.has(fieldError.field)
        );
        for (const fieldError of error.fieldErrors) {
          if (KNOWN_FIELD_NAMES.has(fieldError.field)) {
            setError(fieldError.field as keyof StudentRegistrationFormValues, {
              type: "server",
              message: fieldError.message,
            });
          }
        }
        if (unmapped.length > 0) {
          setPageError({
            message: "Some information couldn't be validated.",
            code: error.code,
            fieldErrors: unmapped,
          });
        }
        return;
      }

      if (error.code === "CONFLICT") {
        // StudentRegistrationService only ever raises a CONFLICT for a
        // duplicate email once OTP verification (when required) has
        // already passed — attributing it to the email field is safe.
        setError("email", { type: "server", message: error.message });
        return;
      }

      setPageError({ message: error.message, code: error.code });
      return;
    }

    setPageError({ message: "An unexpected error occurred. Please try again." });
  }

  async function submitRegistration(values: StudentRegistrationFormValues) {
    const result = await registerMutation.mutateAsync(toRegistrationInput(values));
    setPendingApproval(result.pendingApproval);
    setStep("success");
  }

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);

    if (policy.otpRequired) {
      setHeldValues(values);
      setStep("otp");
      try {
        await sendOtpMutation.mutateAsync(values.email);
      } catch {
        // Surfaced inline by the OTP step's own error rendering below
        // (sendOtpMutation.error) — nothing further to do here.
      }
      return;
    }

    try {
      await submitRegistration(values);
    } catch (error) {
      applySubmitError(error);
    }
  });

  if (step === "success") {
    return (
      <Card>
        <CardHeader>
          <h1 data-slot="card-title" className="font-heading text-base leading-snug font-medium">
            {pendingApproval ? "Application submitted" : "Account created"}
          </h1>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div
            role="status"
            className="flex items-start gap-3 rounded-md border border-border bg-muted/40 px-3 py-3 text-sm text-foreground"
          >
            {pendingApproval ? (
              <>
                <Clock className="mt-0.5 size-5 shrink-0 text-foreground" aria-hidden="true" />
                <p>
                  Your account has been created and is{" "}
                  <span className="font-medium">pending approval</span>. You&apos;ll be able to
                  sign in once a staff member at your institute activates your account.
                </p>
              </>
            ) : (
              <>
                <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-foreground" aria-hidden="true" />
                <p>Your account has been created. You can now sign in.</p>
              </>
            )}
          </div>
          <Link
            href="/login"
            className="text-center text-sm font-medium text-foreground hover:underline"
          >
            Back to sign in
          </Link>
        </CardContent>
      </Card>
    );
  }

  if (step === "otp") {
    return (
      <OtpStep
        email={heldValues?.email ?? getValues("email")}
        sendOtpMutation={sendOtpMutation}
        verifyOtpMutation={verifyOtpMutation}
        onBack={() => {
          setStep("form");
        }}
        confirmDiscardUnsavedChanges={confirmDiscardUnsavedChanges}
        onVerified={async () => {
          if (!heldValues) return;
          setPageError(null);
          try {
            await submitRegistration(heldValues);
          } catch (error) {
            setStep("form");
            applySubmitError(error);
          }
        }}
      />
    );
  }

  const hasRequiredAdditionalField =
    policy.requireGuardianInfo ||
    policy.requireSchool ||
    policy.requireGrade ||
    policy.requireStream ||
    policy.requireMobile;

  return (
    <Card>
      <CardHeader>
        <h1 data-slot="card-title" className="font-heading text-base leading-snug font-medium">
          Create your student account
        </h1>
        <CardDescription>
          Sign up for access to your institute&apos;s LMS.
          {policy.approvalRequired
            ? " Accounts created here require staff approval before you can sign in."
            : null}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {pageError ? (
          <ErrorState
            message={pageError.message}
            code={pageError.code}
            fieldErrors={pageError.fieldErrors}
            onRetry={() => setPageError(null)}
          />
        ) : null}
        <form
          className="flex flex-col gap-4"
          onSubmit={onSubmit}
          aria-busy={registerMutation.isPending || sendOtpMutation.isPending}
          noValidate
        >
          <span role="status" aria-live="polite" className="sr-only">
            {registerMutation.isPending
              ? "Creating your account…"
              : sendOtpMutation.isPending
                ? "Sending a verification code…"
                : ""}
          </span>
          <fieldset
            disabled={registerMutation.isPending || sendOtpMutation.isPending}
            className="flex flex-col gap-4"
          >
            <legend className="sr-only">Create your student account</legend>

            <RegisterField
              name="name"
              label="Full name"
              autoComplete="name"
              register={register}
              errors={errors}
            />
            <RegisterField
              name="email"
              label="Email"
              type="email"
              autoComplete="email"
              register={register}
              errors={errors}
            />
            <RegisterField
              name="password"
              label="Password"
              type="password"
              autoComplete="new-password"
              helperText="At least 8 characters."
              register={register}
              errors={errors}
            />
            <RegisterField
              name="confirmPassword"
              label="Confirm password"
              type="password"
              autoComplete="new-password"
              register={register}
              errors={errors}
            />

            <fieldset className="flex flex-col gap-4 rounded-md border border-border p-3">
              <legend className="px-1 text-xs font-medium text-muted-foreground">
                Additional information
              </legend>
              <p className="text-xs text-muted-foreground">
                {hasRequiredAdditionalField
                  ? "Fields marked (optional) aren't required — every other field below is required by this institute."
                  : "These fields are optional for this institute."}
              </p>
              <RegisterField
                name="guardianName"
                label="Guardian name"
                optional={!policy.requireGuardianInfo}
                autoComplete="off"
                register={register}
                errors={errors}
              />
              <RegisterField
                name="guardianPhone"
                label="Guardian phone"
                type="tel"
                optional={!policy.requireGuardianInfo}
                autoComplete="off"
                register={register}
                errors={errors}
              />
              <RegisterField
                name="school"
                label="School"
                optional={!policy.requireSchool}
                autoComplete="off"
                register={register}
                errors={errors}
              />
              <RegisterField
                name="grade"
                label="Grade"
                optional={!policy.requireGrade}
                autoComplete="off"
                register={register}
                errors={errors}
              />
              <RegisterField
                name="stream"
                label="Stream"
                optional={!policy.requireStream}
                autoComplete="off"
                register={register}
                errors={errors}
              />
              <RegisterField
                name="mobile"
                label="Mobile number"
                type="tel"
                optional={!policy.requireMobile}
                autoComplete="off"
                register={register}
                errors={errors}
              />
            </fieldset>

            <Button
              type="submit"
              className="w-full"
              disabled={registerMutation.isPending || sendOtpMutation.isPending}
            >
              {registerMutation.isPending
                ? "Creating account…"
                : sendOtpMutation.isPending
                  ? "Sending code…"
                  : "Create account"}
            </Button>
          </fieldset>
        </form>
        <p className="text-center text-sm text-muted-foreground">
          Already have an account?{" "}
          <Link
            href="/login"
            className="font-medium text-foreground hover:underline"
            onClick={(event) => {
              if (!confirmDiscardUnsavedChanges()) event.preventDefault();
            }}
          >
            Sign in
          </Link>
        </p>
      </CardContent>
    </Card>
  );
}

interface RegisterFieldProps {
  name: keyof StudentRegistrationFormValues;
  label: string;
  type?: string;
  autoComplete?: string;
  helperText?: string;
  optional?: boolean;
  register: ReturnType<typeof useForm<StudentRegistrationFormValues>>["register"];
  errors: ReturnType<typeof useForm<StudentRegistrationFormValues>>["formState"]["errors"];
}

function RegisterField({
  name,
  label,
  type = "text",
  autoComplete,
  helperText,
  optional,
  register,
  errors,
}: RegisterFieldProps) {
  const fieldError = errors[name];
  const errorId = `register-${name}-error`;
  const helperId = helperText ? `register-${name}-helper` : undefined;

  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={`register-${name}`}>
        {label}
        {optional ? <span className="font-normal text-muted-foreground"> (optional)</span> : null}
      </Label>
      <Input
        id={`register-${name}`}
        type={type}
        autoComplete={autoComplete}
        aria-invalid={fieldError ? true : undefined}
        aria-describedby={
          [helperId, fieldError ? errorId : undefined].filter(Boolean).join(" ") || undefined
        }
        {...register(name)}
      />
      {helperText ? (
        <p id={helperId} className="text-xs text-muted-foreground">
          {helperText}
        </p>
      ) : null}
      {fieldError ? (
        <p id={errorId} role="alert" className="text-xs text-destructive">
          {fieldError.message as string}
        </p>
      ) : null}
    </div>
  );
}

interface OtpStepProps {
  email: string;
  sendOtpMutation: ReturnType<typeof useSendRegistrationOtp>;
  verifyOtpMutation: ReturnType<typeof useVerifyRegistrationOtp>;
  onBack: () => void;
  onVerified: () => void | Promise<void>;
  /** Returns `true` if it's safe to proceed (nothing to lose, or the visitor confirmed leaving anyway). */
  confirmDiscardUnsavedChanges: () => boolean;
}

function OtpStep({
  email,
  sendOtpMutation,
  verifyOtpMutation,
  onBack,
  onVerified,
  confirmDiscardUnsavedChanges,
}: OtpStepProps) {
  const {
    register,
    handleSubmit,
    formState: { errors },
    setError,
  } = useForm<RegistrationOtpFormValues>({
    resolver: zodResolver(registrationOtpSchema),
    defaultValues: { otp: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    try {
      await verifyOtpMutation.mutateAsync({ email, otp: values.otp });
      await onVerified();
    } catch (error) {
      if (isApiClientError(error)) {
        setError("otp", { type: "server", message: error.message });
        return;
      }
      setError("otp", { type: "server", message: "An unexpected error occurred. Please try again." });
    }
  });

  const sendError = isApiClientError(sendOtpMutation.error) ? sendOtpMutation.error.message : null;
  const busy = verifyOtpMutation.isPending;

  return (
    <Card>
      <CardHeader>
        <h1 data-slot="card-title" className="font-heading text-base leading-snug font-medium">
          Verify your email
        </h1>
        <CardDescription>
          {sendOtpMutation.isPending
            ? "Sending a verification code…"
            : sendError
              ? "We couldn't send a verification code."
              : `We sent a verification code to ${email}.`}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {sendError ? (
          <ErrorState
            message={sendError}
            onRetry={() => void sendOtpMutation.mutateAsync(email)}
          />
        ) : null}
        <form className="flex flex-col gap-4" onSubmit={onSubmit} aria-busy={busy} noValidate>
          <span role="status" aria-live="polite" className="sr-only">
            {busy ? "Verifying…" : ""}
          </span>
          <fieldset disabled={busy} className="flex flex-col gap-4">
            <legend className="sr-only">Verify your email</legend>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="register-otp">Verification code</Label>
              <Input
                id="register-otp"
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                aria-invalid={errors.otp ? true : undefined}
                aria-describedby={errors.otp ? "register-otp-error" : undefined}
                {...register("otp")}
              />
              {errors.otp ? (
                <p id="register-otp-error" role="alert" className="text-xs text-destructive">
                  {errors.otp.message}
                </p>
              ) : null}
            </div>
            <Button type="submit" className="w-full" disabled={busy}>
              {busy ? "Verifying…" : "Verify and create account"}
            </Button>
          </fieldset>
        </form>
        <div className="flex items-center justify-between text-sm">
          <button
            type="button"
            className="font-medium text-muted-foreground hover:text-foreground hover:underline"
            onClick={() => {
              if (!confirmDiscardUnsavedChanges()) return;
              onBack();
            }}
          >
            Use a different email
          </button>
          <button
            type="button"
            className="font-medium text-foreground hover:underline disabled:opacity-50"
            disabled={sendOtpMutation.isPending}
            onClick={() => void sendOtpMutation.mutateAsync(email)}
          >
            Resend code
          </button>
        </div>
      </CardContent>
    </Card>
  );
}
