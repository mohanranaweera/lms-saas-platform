import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Wave 10 - device controls. Contract: `docs/api/identity-access-service.md`
 * (Devices) and `docs/api/user-management.md` (Student devices).
 *
 * Device identity is a server-issued `HttpOnly` cookie set at sign-in - the
 * frontend never sees, stores or sends a device id of its own. The backend
 * enforces every limit, reset, cooldown and permission (`DEVICES` V / C/E);
 * these hooks only reflect its answers.
 */

export type DeviceStatus = "ACTIVE" | "REVOKED";
export type DeviceLimitSource = "STUDENT" | "COURSE" | "TENANT" | "PLAN";

/** Mirrors `DeviceView`. */
export interface DeviceView {
  id: string;
  label: string;
  status: DeviceStatus;
  firstSeenAt: string;
  lastSeenAt: string;
  lastIp: string | null;
  revokedAt: string | null;
  revocationSource: "ADMIN" | "STUDENT" | "SYSTEM" | null;
  revokeReason: string | null;
  /** When a revoked device's slot can be used by a new device (reset cooldown). */
  slotReusableAt: string | null;
  /** True only for the device the current session runs on (My Devices). */
  current: boolean;
}

/** Mirrors `DeviceSlotSummary`. `limit` is null for staff accounts (not device-limited). */
export interface DeviceSlotSummary {
  devices: DeviceView[];
  limit: number | null;
  limitSource: DeviceLimitSource | null;
  occupiedSlots: number;
  nextSlotFreeAt: string | null;
  studentOverride: number | null;
  studentOverrideReason: string | null;
  selfServiceEnabled: boolean;
  resetCooldownHours: number;
}

/** Mirrors `DevicePolicyView`. `maxDevices` null = plan default. */
export interface DevicePolicyView {
  maxDevices: number | null;
  effectiveMaxDevices: number;
  planDefaultLimit: number;
  resetCooldownHours: number;
  studentSelfServiceEnabled: boolean;
  updatedAt: string | null;
}

export interface DevicePolicyInput {
  maxDevices: number | null;
  resetCooldownHours: number;
  studentSelfServiceEnabled: boolean;
}

export const deviceKeys = {
  all: ["devices"] as const,
  my: () => [...deviceKeys.all, "my"] as const,
  student: (studentId: string) => [...deviceKeys.all, "student", studentId] as const,
  policy: () => [...deviceKeys.all, "policy"] as const,
};

// ---------------------------------------------------------------------------
// The signed-in user (My Devices)
// ---------------------------------------------------------------------------

export function useMyDevices() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: deviceKeys.my(),
    queryFn: () => authorizedFetch<DeviceSlotSummary>("tenant", "/v1/devices/my"),
  });
}

export function useRevokeMyDevice() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (deviceId: string) =>
      authorizedFetch<DeviceSlotSummary>("tenant", `/v1/devices/my/${deviceId}/revoke`, { method: "POST" }),
    onSuccess: (summary) => {
      queryClient.setQueryData(deviceKeys.my(), summary);
    },
  });
}

// ---------------------------------------------------------------------------
// Staff - Student detail Devices tab (studentId = student_profile id)
// ---------------------------------------------------------------------------

export function useStudentDevices(studentId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: deviceKeys.student(studentId),
    queryFn: () => authorizedFetch<DeviceSlotSummary>("tenant", `/v1/students/${studentId}/devices`),
    enabled: studentId.length > 0,
  });
}

function useStudentDeviceMutation<TInput, TResult>(
  studentId: string,
  request: (input: TInput) => { path: string; init: RequestInit }
) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TInput) => {
      const { path, init } = request(input);
      return authorizedFetch<TResult>("tenant", path, init);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: deviceKeys.student(studentId) });
      // VIDEO Phase 8: resetting a device also removes the AOC Player paired on it.
      void queryClient.invalidateQueries({ queryKey: ["player", "student", studentId] });
      // Resets and limit changes are audit-logged on the student's Activity tab.
      void queryClient.invalidateQueries({ queryKey: ["students", studentId, "activity"] });
    },
  });
}

export function useResetStudentDevice(studentId: string) {
  return useStudentDeviceMutation<{ deviceId: string; reason: string }, DeviceView>(studentId, (input) => ({
    path: `/v1/students/${studentId}/devices/${input.deviceId}/reset`,
    init: { method: "POST", body: JSON.stringify({ reason: input.reason }) },
  }));
}

export function useResetAllStudentDevices(studentId: string) {
  return useStudentDeviceMutation<{ reason: string }, { revokedCount: number }>(studentId, (input) => ({
    path: `/v1/students/${studentId}/devices/reset`,
    init: { method: "POST", body: JSON.stringify({ reason: input.reason }) },
  }));
}

/** `maxDevices` null clears the student exception. */
export function useSetStudentDeviceLimit(studentId: string) {
  return useStudentDeviceMutation<{ maxDevices: number | null; reason: string }, DeviceSlotSummary>(
    studentId,
    (input) => ({
      path: `/v1/students/${studentId}/device-limit`,
      init: { method: "PUT", body: JSON.stringify(input) },
    })
  );
}

// ---------------------------------------------------------------------------
// Tenant device policy
// ---------------------------------------------------------------------------

export function useDevicePolicy() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: deviceKeys.policy(),
    queryFn: () => authorizedFetch<DevicePolicyView>("tenant", "/v1/device-policy"),
  });
}

export function useUpdateDevicePolicy() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: DevicePolicyInput) =>
      authorizedFetch<DevicePolicyView>("tenant", "/v1/device-policy", {
        method: "PUT",
        body: JSON.stringify(input),
      }),
    onSuccess: (policy) => {
      queryClient.setQueryData(deviceKeys.policy(), policy);
      void queryClient.invalidateQueries({ queryKey: [...deviceKeys.all, "student"] });
    },
  });
}
