import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * VIDEO Phase 8 - the AOC Player (ADR-025). Contract: `docs/api/aoc-player.md`.
 *
 * The browser never talks to the player and never sees its key: it only asks
 * the backend for one-time `aocplayer://` links (pairing, launch) and reads
 * back what the backend says happened. Every check (student, device, access,
 * code expiry, signatures) is the backend's.
 */

export type PlayerInstallationStatus = "ACTIVE" | "REVOKED";
export type PlayerRevocationSource = "STUDENT" | "ADMIN" | "DEVICE_REVOKED" | "REPAIRED";

/** Mirrors `PlayerPairingService.InstallationView`. */
export interface PlayerInstallation {
  id: string;
  deviceId: string;
  platform: string;
  playerVersion: string;
  status: PlayerInstallationStatus;
  pairedAt: string;
  lastSeenAt: string;
  revokedAt: string | null;
  revocationSource: PlayerRevocationSource | null;
}

export interface PairingStart {
  pairingUrl: string;
  expiresAt: string;
}

export type LaunchState = "PENDING_LAUNCH" | "ACTIVE" | "ENDED" | "REVOKED" | "EXPIRED";

export interface LaunchStatus {
  status: LaunchState;
  exchanged: boolean;
}

export const playerKeys = {
  all: ["player"] as const,
  mine: () => [...playerKeys.all, "mine"] as const,
  student: (studentId: string) => [...playerKeys.all, "student", studentId] as const,
  launch: (sessionId: string) => [...playerKeys.all, "launch", sessionId] as const,
};

// ---------------------------------------------------------------------------
// Student - own player
// ---------------------------------------------------------------------------

export function useMyPlayerInstallations() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: playerKeys.mine(),
    queryFn: () => authorizedFetch<PlayerInstallation[]>("tenant", "/v1/player/installations"),
  });
}

export function useStartPairing() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: () => authorizedFetch<PairingStart>("tenant", "/v1/player/pairings", { method: "POST" }),
  });
}

export function useRevokeMyInstallation() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (installationId: string) =>
      authorizedFetch<PlayerInstallation>("tenant", `/v1/player/installations/${installationId}/revoke`, {
        method: "POST",
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: playerKeys.mine() });
    },
  });
}

/**
 * Polls the Student's own NATIVE session while the browser waits for the
 * player to claim it. Stops once it is claimed or no longer pending.
 */
export function useLaunchStatus(sessionId: string | null) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: playerKeys.launch(sessionId ?? ""),
    queryFn: () => authorizedFetch<LaunchStatus>("tenant", `/v1/playback/sessions/${sessionId}/status`),
    enabled: sessionId !== null,
    refetchInterval: (query) => {
      const data = query.state.data;
      return data && (data.exchanged || data.status !== "PENDING_LAUNCH") ? false : 2000;
    },
  });
}

// ---------------------------------------------------------------------------
// Staff - Student detail Devices tab (studentId = student_profile id)
// ---------------------------------------------------------------------------

export function useStudentPlayerInstallations(studentId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: playerKeys.student(studentId),
    queryFn: () => authorizedFetch<PlayerInstallation[]>("tenant", `/v1/students/${studentId}/player-installations`),
    enabled: studentId.length > 0,
  });
}

export function useRevokeStudentInstallation(studentId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { installationId: string; reason: string }) =>
      authorizedFetch<PlayerInstallation>(
        "tenant",
        `/v1/students/${studentId}/player-installations/${input.installationId}/revoke`,
        { method: "POST", body: JSON.stringify({ reason: input.reason }) }
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: playerKeys.student(studentId) });
      void queryClient.invalidateQueries({ queryKey: ["students", studentId, "activity"] });
    },
  });
}
