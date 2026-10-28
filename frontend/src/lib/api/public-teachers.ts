import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { PageResponse } from "./courses";

export interface PublicTeacherCourse {
  id: string;
  name: string;
  slug: string;
  category: string;
  coverImageUrl: string | null;
}

export interface PublicTeacher {
  id: string;
  name: string;
  qualifications: string | null;
  description: string | null;
  profileImageUrl: string | null;
  courses: PublicTeacherCourse[];
}

export const publicTeacherKeys = {
  all: ["public-teachers"] as const,
  list: (page: number, size: number) => ["public-teachers", "list", page, size] as const,
  detail: (id: string) => ["public-teachers", "detail", id] as const,
};

export const PUBLIC_TEACHERS_PAGE_SIZE = 12;

export function listPublicTeachers(page = 0, size = PUBLIC_TEACHERS_PAGE_SIZE) {
  return apiFetch<PageResponse<PublicTeacher>>(`/v1/public/teachers?page=${page}&size=${size}`);
}

export function getPublicTeacher(id: string) {
  return apiFetch<PublicTeacher>(`/v1/public/teachers/${encodeURIComponent(id)}`);
}

export function usePublicTeachers(page = 0, size = PUBLIC_TEACHERS_PAGE_SIZE) {
  return useQuery({
    queryKey: publicTeacherKeys.list(page, size),
    queryFn: () => listPublicTeachers(page, size),
  });
}

export function usePublicTeacher(id: string) {
  return useQuery({
    queryKey: publicTeacherKeys.detail(id),
    queryFn: () => getPublicTeacher(id),
    enabled: Boolean(id),
    retry: false,
  });
}
