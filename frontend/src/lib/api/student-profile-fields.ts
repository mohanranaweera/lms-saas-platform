"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Typed client + React Query hooks for tenant-configurable student profile
 * fields (STU-PROF-01):
 *   - `GET/PUT /v1/tenant-config/student-profile-fields` — Tenant Admin
 *     settings (`BRANDING_SETTINGS` VIEW/CREATE_EDIT, server-enforced).
 *   - `GET/PUT /v1/students/me/profile` — the student's own "Additional
 *     details" (no id anywhere; the backend resolves the caller).
 *   - `GET/PUT /v1/students/{id}/profile-fields` — staff view/correction
 *     (`STUDENTS` VIEW/CREATE_EDIT, server-enforced).
 *
 * Which fields exist, which are required, and which values are valid is
 * decided by the backend for the caller's tenant; the frontend only renders
 * what it is given and re-surfaces the backend's field errors.
 */

/** Mirrors `StudentProfileFieldType`. */
export type StudentProfileFieldType = "TEXT" | "PHONE" | "DATE" | "YEAR" | "BOOLEAN" | "COUNTRY" | "GUARDIAN";

/** Mirrors `StudentProfileFieldGroup`. */
export type StudentProfileFieldGroup =
  | "ACCOUNT"
  | "PERSONAL"
  | "CONTACT"
  | "ADDRESS"
  | "IDENTITY"
  | "EDUCATION"
  | "FAMILY"
  | "OTHER";

/** Display metadata shared by every field shape the backend returns. */
export interface ProfileFieldDescriptor {
  key: string;
  label: string;
  group: StudentProfileFieldGroup;
  type: StudentProfileFieldType;
  maxLength: number;
  required: boolean;
}

/** Mirrors `StudentProfileFieldSettingResponse`. `locked` rows (Email, Full name) are display-only. */
export interface StudentProfileFieldSetting {
  key: string;
  label: string;
  group: StudentProfileFieldGroup;
  type: StudentProfileFieldType;
  maxLength: number;
  sensitive: boolean;
  locked: boolean;
  enabled: boolean;
  required: boolean;
  collectAtRegistration: boolean;
}

export interface StudentProfileFieldSettings {
  fields: StudentProfileFieldSetting[];
}

/** One changed row in a settings save; rows not sent keep their current settings server-side. */
export interface StudentProfileFieldSettingChange {
  key: string;
  enabled: boolean;
  required: boolean;
  collectAtRegistration: boolean;
}

export interface GuardianValue {
  name: string | null;
  phone: string | null;
}

/** A stored value: text/phone/ISO date/country code string, year number, boolean, guardian object, or `null`. */
export type StudentProfileFieldValue = string | number | boolean | GuardianValue | null;

/** Mirrors `StudentProfileFieldValueResponse`. */
export interface StudentProfileFieldEntry extends ProfileFieldDescriptor {
  sensitive: boolean;
  value: StudentProfileFieldValue;
}

/** Mirrors `StudentProfileFieldsResponse`. */
export interface StudentProfileFields {
  fields: StudentProfileFieldEntry[];
  missingRequiredFields: string[];
}

export const studentProfileFieldKeys = {
  settings: ["student-profile-field-settings"] as const,
  own: ["students", "me", "profile-fields"] as const,
  student: (studentId: string) => ["students", studentId, "profile-fields"] as const,
};

/** Shares columns with the profile form's ADDRESS_LINE_1/CITY — kept in sync on save. */
const ownBillingAddressKey = ["students", "me", "billing-address"] as const;

export function useStudentProfileFieldSettings() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentProfileFieldKeys.settings,
    queryFn: () =>
      authorizedFetch<StudentProfileFieldSettings>("tenant", "/v1/tenant-config/student-profile-fields"),
  });
}

export function useUpdateStudentProfileFieldSettings() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (fields: StudentProfileFieldSettingChange[]) =>
      authorizedFetch<StudentProfileFieldSettings>("tenant", "/v1/tenant-config/student-profile-fields", {
        method: "PUT",
        body: JSON.stringify({ fields }),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(studentProfileFieldKeys.settings, data);
    },
  });
}

export function useOwnProfileFields() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentProfileFieldKeys.own,
    queryFn: () => authorizedFetch<StudentProfileFields>("tenant", "/v1/students/me/profile"),
  });
}

export function useUpdateOwnProfileFields() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (values: Record<string, unknown>) =>
      authorizedFetch<StudentProfileFields>("tenant", "/v1/students/me/profile", {
        method: "PUT",
        body: JSON.stringify({ values }),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(studentProfileFieldKeys.own, data);
      void queryClient.invalidateQueries({ queryKey: ownBillingAddressKey, exact: true });
    },
  });
}

export function useStudentProfileFields(studentId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentProfileFieldKeys.student(studentId),
    queryFn: () =>
      authorizedFetch<StudentProfileFields>("tenant", `/v1/students/${studentId}/profile-fields`),
  });
}

export function useUpdateStudentProfileFields(studentId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (values: Record<string, unknown>) =>
      authorizedFetch<StudentProfileFields>("tenant", `/v1/students/${studentId}/profile-fields`, {
        method: "PUT",
        body: JSON.stringify({ values }),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(studentProfileFieldKeys.student(studentId), data);
    },
  });
}
