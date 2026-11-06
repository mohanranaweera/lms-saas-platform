"use client";

import { useState } from "react";
import Link from "next/link";
import { CalendarDays, Pencil } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { EditClassScheduleDialog, ScheduleSummaryList } from "./edit-class-schedule-dialog";
import { useClassSchedule } from "@/lib/api/class-schedule";

/**
 * Course workspace "Schedule" tab (CAL-01): weekly class-time summary plus
 * the shared "Edit Class Schedule" editor. `canEdit` comes from the server
 * and only decides whether the Edit button is offered.
 */
export function ClassSchedulePanel({
  courseId,
  courseName,
  calendarHref,
  dashboardHref,
}: {
  courseId: string;
  courseName: string;
  calendarHref: string;
  dashboardHref: string;
}) {
  const query = useClassSchedule(courseId);
  const [open, setOpen] = useState(false);
  const toast = useAdminToast();

  return (
    <Card data-testid="class-schedule-panel">
      <CardHeader>
        <CardTitle>
          <h2 className="flex items-center gap-2 text-base font-semibold">
            <CalendarDays className="size-5 text-primary" aria-hidden="true" />
            Class schedule
          </h2>
        </CardTitle>
        <CardDescription>
          Weekly class times shown on the student and public pages and in the calendar.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading class schedule…"
          loginPath="/login"
          permissionDenied={{ dashboardHref }}
        >
          {(schedule) => (
            <>
              <ScheduleSummaryList schedule={schedule} />
              <div className="flex flex-wrap gap-2">
                {schedule.canEdit ? (
                  <Button type="button" onClick={() => setOpen(true)} data-testid="class-schedule-edit">
                    <Pencil aria-hidden="true" />
                    Edit Class Schedule
                  </Button>
                ) : null}
                <Link href={`${calendarHref}?course=${encodeURIComponent(courseId)}`} className={buttonVariants({ variant: "outline" })}>
                  Open in calendar
                </Link>
              </div>
            </>
          )}
        </QueryStateBoundary>
      </CardContent>
      <EditClassScheduleDialog
        courseId={courseId}
        courseName={courseName}
        open={open}
        onOpenChange={setOpen}
        onSaved={() => toast.show({ kind: "success", text: `Class schedule saved for “${courseName}”.` })}
      />
      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </Card>
  );
}
