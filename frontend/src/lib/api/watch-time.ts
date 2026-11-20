import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * VIDEO Phase 12 - a Student's watch time per video and staff extra-time
 * grants. Contract: `docs/api/video-access-management.md` ("Watch-time
 * grants"). `ACCESS_EXPIRY` VIEW to read, CREATE_EDIT to grant; the backend
 * enforces both, audits every grant, and never reduces time watched.
 */

/** `NONE`: no limit applies; `UNKNOWN`: the limit needs the video's length, not known yet. */
export type WatchTimeLimit = "LIMITED" | "NONE" | "UNKNOWN";

/** Mirrors `WatchTimeGrantService.GrantView`. */
export interface WatchTimeGrant {
  id: string;
  extraSeconds: number;
  reason: string;
  grantedBy: string;
  grantedAt: string;
}

/** Mirrors `WatchTimeGrantService.VideoWatchTimeView`. */
export interface VideoWatchTime {
  videoAssetId: string;
  title: string | null;
  limit: WatchTimeLimit;
  usedSeconds: number;
  baseSeconds: number | null;
  grantedSeconds: number;
  allowedSeconds: number | null;
  remainingSeconds: number | null;
  exhausted: boolean;
  lastWatchedAt: string | null;
  grants: WatchTimeGrant[];
}

export interface GrantWatchTimeInput {
  videoAssetId: string;
  extraMinutes: number;
  reason: string;
}

export const watchTimeKeys = {
  student: (studentId: string) => ["watch-time", "student", studentId] as const,
};

export function useStudentWatchTime(studentId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: watchTimeKeys.student(studentId),
    queryFn: () => authorizedFetch<VideoWatchTime[]>("tenant", `/v1/students/${studentId}/watch-time`),
  });
}

export function useGrantWatchTime(studentId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ videoAssetId, extraMinutes, reason }: GrantWatchTimeInput) =>
      authorizedFetch<VideoWatchTime>("tenant", `/v1/students/${studentId}/watch-time/${videoAssetId}/grants`, {
        method: "POST",
        body: JSON.stringify({ extraMinutes, reason }),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: watchTimeKeys.student(studentId) }),
  });
}

/** 754 -> "12 min 34 s"; 3600 -> "1 h 0 min". */
export function formatWatchSeconds(seconds: number): string {
  const whole = Math.max(0, Math.round(seconds));
  const hours = Math.floor(whole / 3600);
  const minutes = Math.floor((whole % 3600) / 60);
  const rest = whole % 60;
  if (hours > 0) return `${hours} h ${minutes} min`;
  if (minutes > 0) return rest > 0 ? `${minutes} min ${rest} s` : `${minutes} min`;
  return `${rest} s`;
}
