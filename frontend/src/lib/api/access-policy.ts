import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Wave 10 - centralized access policy: tenant grace period, per-course
 * overrides (grace period, course device limit) and manual access extensions.
 * Contract: `docs/api/enrollment-management.md` (Access policy).
 *
 * The backend decides every outcome (`ACCESS_EXPIRY` V / C/E, `DEVICES` C/E for
 * course device limits; extensions only for a current, time-limited
 * enrollment). These hooks only reflect it.
 */

/** Mirrors `TenantAccessPolicyView`. */
export interface TenantAccessPolicy {
  gracePeriodDays: number;
  updatedAt: string | null;
}

/** Mirrors `CourseAccessPolicyView`. Null values mean "no override". */
export interface CourseAccessPolicy {
  courseId: string;
  courseName: string | null;
  gracePeriodDays: number | null;
  maxDevices: number | null;
  updatedAt: string | null;
}

/** Mirrors `AccessExtensionView`. */
export interface AccessExtension {
  id: string;
  enrollmentId: string;
  previousExpiresAt: string;
  newExpiresAt: string;
  reason: string;
  grantedBy: string;
  grantedAt: string;
}

export const accessPolicyKeys = {
  all: ["access-policy"] as const,
  tenant: () => [...accessPolicyKeys.all, "tenant"] as const,
  courses: () => [...accessPolicyKeys.all, "courses"] as const,
  extensions: (enrollmentId: string) => [...accessPolicyKeys.all, "extensions", enrollmentId] as const,
};

export function useTenantAccessPolicy() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: accessPolicyKeys.tenant(),
    queryFn: () => authorizedFetch<TenantAccessPolicy>("tenant", "/v1/access-policy"),
  });
}

export function useUpdateTenantAccessPolicy() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { gracePeriodDays: number }) =>
      authorizedFetch<TenantAccessPolicy>("tenant", "/v1/access-policy", {
        method: "PUT",
        body: JSON.stringify(input),
      }),
    onSuccess: (policy) => {
      queryClient.setQueryData(accessPolicyKeys.tenant(), policy);
    },
  });
}

export function useCourseAccessPolicies() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: accessPolicyKeys.courses(),
    queryFn: () => authorizedFetch<CourseAccessPolicy[]>("tenant", "/v1/access-policy/courses"),
  });
}

/** Both values null removes the course override (the response data is then null). */
export function useSaveCourseAccessPolicy() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { courseId: string; gracePeriodDays: number | null; maxDevices: number | null }) =>
      authorizedFetch<CourseAccessPolicy | null>("tenant", `/v1/access-policy/courses/${input.courseId}`, {
        method: "PUT",
        body: JSON.stringify({ gracePeriodDays: input.gracePeriodDays, maxDevices: input.maxDevices }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: accessPolicyKeys.courses() });
    },
  });
}

export function useAccessExtensions(enrollmentId: string, enabled: boolean) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: accessPolicyKeys.extensions(enrollmentId),
    queryFn: () => authorizedFetch<AccessExtension[]>("tenant", `/v1/enrollments/${enrollmentId}/extensions`),
    enabled: enabled && enrollmentId.length > 0,
  });
}

/** `studentId` (student_profile id) is only used to refresh that student's enrollment history and activity. */
export function useExtendAccess(studentId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { enrollmentId: string; newExpiresAt: string; reason: string }) =>
      authorizedFetch<AccessExtension>("tenant", `/v1/enrollments/${input.enrollmentId}/extensions`, {
        method: "POST",
        body: JSON.stringify({ newExpiresAt: input.newExpiresAt, reason: input.reason }),
      }),
    onSuccess: (extension) => {
      void queryClient.invalidateQueries({ queryKey: accessPolicyKeys.extensions(extension.enrollmentId) });
      void queryClient.invalidateQueries({ queryKey: ["students", studentId, "enrollments"] });
      void queryClient.invalidateQueries({ queryKey: ["students", studentId, "activity"] });
    },
  });
}
