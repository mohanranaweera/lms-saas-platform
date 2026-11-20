/**
 * Portal-specific links for the shared Tute screens. Tenant Admin (and the
 * staff roles that share its portal) and Teacher use the same components;
 * Teacher has no admin student-profile page, so its index numbers are not links.
 */
export interface TuteRoutes {
  dashboard: string;
  courses: string;
  course: (courseId: string) => string;
  tutes: (courseId: string) => string;
  tute: (courseId: string, tuteId: string) => string;
  studentProfile: ((studentProfileId: string) => string) | null;
}

export const TENANT_ADMIN_TUTE_ROUTES: TuteRoutes = {
  dashboard: "/tenant-admin/dashboard",
  courses: "/tenant-admin/courses",
  course: (courseId) => `/tenant-admin/courses/${encodeURIComponent(courseId)}`,
  tutes: (courseId) => `/tenant-admin/courses/${encodeURIComponent(courseId)}/tutes`,
  tute: (courseId, tuteId) =>
    `/tenant-admin/courses/${encodeURIComponent(courseId)}/tutes/${encodeURIComponent(tuteId)}`,
  studentProfile: (studentProfileId) => `/tenant-admin/students/${encodeURIComponent(studentProfileId)}`,
};

export const TEACHER_TUTE_ROUTES: TuteRoutes = {
  dashboard: "/teacher/dashboard",
  courses: "/teacher/courses",
  course: (courseId) => `/teacher/courses/${encodeURIComponent(courseId)}/edit`,
  tutes: (courseId) => `/teacher/courses/${encodeURIComponent(courseId)}/tutes`,
  tute: (courseId, tuteId) => `/teacher/courses/${encodeURIComponent(courseId)}/tutes/${encodeURIComponent(tuteId)}`,
  studentProfile: null,
};
