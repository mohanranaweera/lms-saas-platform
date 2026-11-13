"use client";

import { Suspense } from "react";
import { LoadingState } from "@/components/states/loading-state";
import { useT } from "@/i18n/i18n-provider";
import { ClassCalendar } from "@/components/calendar/class-calendar";

/** CAL-01 Calendar & Timetable. Course scope is decided server-side from the session. */
export default function StudentCalendarPage() {
  const t = useT();
  return (
    <Suspense fallback={<LoadingState label={t("student.calendar.loading")} />}>
      <ClassCalendar portal="student" />
    </Suspense>
  );
}
