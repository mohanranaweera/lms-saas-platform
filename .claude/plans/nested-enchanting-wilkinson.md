# Student Profile Attendance Tab

## Context

Student Profile tabs currently show placeholder text for Attendance, even though a real single-record attendance endpoint exists (`GET /api/v1/attendance/my`) and a server-computed summary endpoint exists (`GET /api/v1/attendance/my/summary`). Demo Student has an attendance record that should appear in the profile Attendance tab.

## Recommended approach

1. Add a shared attendance profile panel under `frontend/src/components/students/`:
   - Use `useMyAttendanceSummary()` for per-course totals/rates and `useMyAttendance()` for marked records.
   - Render summary cards/table with course name, present, late, absent, total, and server-provided attendance rate.
   - Render attendance record list with course/session/class-session title where available, status, marked date, and clear empty state when no records exist.
   - Use loading skeleton, error/retry, permission/session handling, responsive cards, and status text not color-only.
   - Never calculate attendance rate from raw rows; display backend `attendanceRate`.

2. Replace the profile page Attendance placeholder in `frontend/src/app/(student)/student/profile/page.tsx` with the shared panel.

3. Keep existing `/student/attendance` page and API contracts unchanged; no backend change expected. Ensure course/session labels use existing attendance formatting helpers where available and avoid exposing student IDs.

4. Add Playwright/component coverage for:
   - Demo student’s record visible in Attendance tab.
   - Summary rate/counts match backend response.
   - Empty, loading, and error states.
   - Keyboard tab navigation and responsive layout.

## Verification

- Run frontend build and targeted attendance/profile tests.
- Smoke-test `/student/profile?tab=attendance` with Demo Student.
- Confirm `git diff --check`; preserve existing working-tree changes.
