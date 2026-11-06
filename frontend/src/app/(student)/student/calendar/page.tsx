"use client";

import { Suspense } from "react";
import { LoadingState } from "@/components/states/loading-state";
import { ClassCalendar } from "@/components/calendar/class-calendar";

/** CAL-01 Calendar & Timetable. Course scope is decided server-side from the session. */
export default function StudentCalendarPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading calendar…" />}>
      <ClassCalendar portal="student" />
    </Suspense>
  );
}
