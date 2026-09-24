import { useMutation, useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";

/**
 * Typed client for the public, unauthenticated student self-registration
 * endpoints (Wave 3, PAR-03-01 —
 * `usermanagement.student.web.StudentRegistrationController`) plus the
 * public registration-policy read that gates what this form renders. Tenant
 * identity is resolved entirely server-side from the request's subdomain
 * (`TenantResolutionFilter`) — this client never sends or receives a
 * `tenantId`, mirroring `lib/api/tenant-registrations.ts`'s bare
 * function + `useMutation`/`useQuery` shape (no `useAuth()`/`authorizedFetch`
 * here: an anonymous visitor filling out this form has no session to
 * attach).
 *
 * `GET /api/v1/public/tenant-config/student-registration-policy` (no auth,
 * tenant resolved by host — mirrors `PublicBrandingController`'s precedent
 * for `ConfigDomain.BRANDING`) closes the contract gap this file previously
 * documented: `app/(auth)/register/page.tsx` now pre-fetches this policy on
 * mount and renders conditionally from it (which fields are required, the
 * OTP step, the closed state), rather than discovering those flags
 * adaptively from `register`/`sendOtp` error responses. See that page's own
 * doc comment for the resulting render flow; submit-time errors (409
 * duplicate email, 400 field errors that still fire despite matching
 * client-side validation) are still handled there — only the *discovery* of
 * the policy moved to this prefetch.
 */

export interface StudentRegistrationInput {
  name: string;
  email: string;
  password: string;
  guardianName?: string;
  guardianPhone?: string;
  school?: string;
  grade?: string;
  stream?: string;
  mobile?: string;
}

/** Mirrors `StudentRegistrationResponse`. `pendingApproval` reflects the calling tenant's `approval_required` policy at registration time. */
export interface StudentRegistrationResult {
  studentProfileId: string;
  email: string;
  pendingApproval: boolean;
}

export function sendRegistrationOtp(email: string): Promise<void> {
  return apiFetch<null>("/v1/students/register/otp/send", {
    method: "POST",
    body: JSON.stringify({ email }),
  }).then(() => undefined);
}

export function verifyRegistrationOtp(email: string, otp: string): Promise<void> {
  return apiFetch<null>("/v1/students/register/otp/verify", {
    method: "POST",
    body: JSON.stringify({ email, otp }),
  }).then(() => undefined);
}

export function registerStudent(
  input: StudentRegistrationInput
): Promise<StudentRegistrationResult> {
  return apiFetch<StudentRegistrationResult>("/v1/students/register", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function useSendRegistrationOtp() {
  return useMutation({ mutationFn: sendRegistrationOtp });
}

export function useVerifyRegistrationOtp() {
  return useMutation({
    mutationFn: ({ email, otp }: { email: string; otp: string }) =>
      verifyRegistrationOtp(email, otp),
  });
}

export function useRegisterStudent() {
  return useMutation({ mutationFn: registerStudent });
}

/**
 * Mirrors the backend's `StudentRegistrationPolicyResponse` field-for-field
 * (`GET /api/v1/public/tenant-config/student-registration-policy`).
 * `requireGuardianInfo` gates both `guardianName` and `guardianPhone` in the
 * form (there is no separate name/phone split server-side); the remaining
 * four `require*` flags each gate exactly one field.
 */
export interface StudentRegistrationPolicy {
  publicRegistrationEnabled: boolean;
  approvalRequired: boolean;
  otpRequired: boolean;
  requireGuardianInfo: boolean;
  requireSchool: boolean;
  requireGrade: boolean;
  requireStream: boolean;
  requireMobile: boolean;
}

export const studentRegistrationPolicyKeys = {
  all: ["student-registration-policy"] as const,
};

export function getStudentRegistrationPolicy(): Promise<StudentRegistrationPolicy> {
  return apiFetch<StudentRegistrationPolicy>(
    "/v1/public/tenant-config/student-registration-policy"
  );
}

/**
 * Fetched once on `/register` mount, before any field renders — see
 * `app/(auth)/register/page.tsx`. Unauthenticated by design (`apiFetch`, not
 * `authorizedFetch`): an anonymous visitor has no session, and the backend
 * resolves the tenant from the request's subdomain alone.
 */
export function useStudentRegistrationPolicy() {
  return useQuery({
    queryKey: studentRegistrationPolicyKeys.all,
    queryFn: getStudentRegistrationPolicy,
  });
}
