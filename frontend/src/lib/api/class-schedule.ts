"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { calendarKeys, publicTodayKeys } from "./calendar";

/**
 * CAL-01 weekly class schedule of one course (docs/specs/class-calendar.md
 * §6.1/§6.2). The server is authoritative for who may edit (`canEdit` is a UX
 * hint only — the PUT re-checks course access), for validation, and for
 * optimistic concurrency via `expectedRevisionId` (409 `SCHEDULE_CONFLICT`).
 * Saving appends a revision effective from the server's "now": past and
 * in-progress classes keep the rule that was in force when they started.
 */

export const WEEKDAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"] as const;
export type Weekday = (typeof WEEKDAYS)[number];

export const WEEKDAY_LABELS: Record<Weekday, string> = {
  MONDAY: "Monday",
  TUESDAY: "Tuesday",
  WEDNESDAY: "Wednesday",
  THURSDAY: "Thursday",
  FRIDAY: "Friday",
  SATURDAY: "Saturday",
  SUNDAY: "Sunday",
};

export interface ClassScheduleSlot {
  id: string;
  dayOfWeek: Weekday;
  startTime: string;
  endTime: string;
}

export interface ClassScheduleResponse {
  courseId: string;
  courseName: string;
  timezone: string;
  revisionId: string | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  updatedAt: string | null;
  updatedByName: string | null;
  canEdit: boolean;
  slots: ClassScheduleSlot[];
}

export interface ClassScheduleUpdateRequest {
  expectedRevisionId: string | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  slots: { dayOfWeek: Weekday; startTime: string; endTime: string }[];
}

export const classScheduleKeys = {
  detail: (courseId: string) => ["class-schedule", courseId] as const,
};

/** `HH:mm` (drops seconds if the server ever sends `HH:mm:ss`). */
export function normalizeTime(value: string | null | undefined): string {
  return (value ?? "").slice(0, 5);
}

export function useClassSchedule(courseId: string, enabled = true) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: classScheduleKeys.detail(courseId),
    queryFn: async ({ signal }) => {
      const data = await authorizedFetch<ClassScheduleResponse>(
        "tenant",
        `/v1/courses/${encodeURIComponent(courseId)}/class-schedule`,
        { signal }
      );
      return {
        ...data,
        slots: (data.slots ?? []).map((slot) => ({
          ...slot,
          startTime: normalizeTime(slot.startTime),
          endTime: normalizeTime(slot.endTime),
        })),
      };
    },
    enabled: enabled && Boolean(courseId),
    retry: false,
  });
}

export function useSaveClassSchedule(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ClassScheduleUpdateRequest) =>
      authorizedFetch<ClassScheduleResponse>("tenant", `/v1/courses/${encodeURIComponent(courseId)}/class-schedule`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: classScheduleKeys.detail(courseId) }),
        queryClient.invalidateQueries({ queryKey: calendarKeys.all }),
        queryClient.invalidateQueries({ queryKey: publicTodayKeys.all }),
      ]);
    },
  });
}
