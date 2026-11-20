"use client";

import { Suspense } from "react";
import { LoadingState } from "@/components/states/loading-state";
import { ClassCalendar } from "@/components/calendar/class-calendar";

import { useT } from "@/i18n/i18n-provider";
/** CAL-01 Calendar & Timetable. Course scope is decided server-side from the session. */
export default function TeacherCalendarPage() {
  const t = useT();
  return (
    <Suspense fallback={<LoadingState label={t("teacher.page.calendar.loadingCalendar")} />}>
      <ClassCalendar portal="teacher" />
    </Suspense>
  );
}
