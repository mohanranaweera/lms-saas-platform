"use client";

import { useState } from "react";
import Link from "next/link";
import { BookOpen, CalendarDays, ChevronDown, ClipboardList, Pencil, Settings2, Truck, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLinkItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { EditClassScheduleDialog } from "@/components/calendar/edit-class-schedule-dialog";
import { TUTE_CAP, useTuteCapabilities } from "@/lib/tutes/use-tute-capabilities";
import { useT } from "@/i18n/i18n-provider";

/**
 * TP-02: the Teacher Portal's existing course management actions (edit,
 * modules & sessions, roster, tutes, class schedule), grouped behind one
 * clearly labeled "Manage course" action. Same deep links and the same
 * visibility rules as before; every action is still authorized server-side
 * (a co-teacher can open the course but the server refuses edits).
 */
export function TeacherCourseManageMenu({
  courseId,
  courseName,
  onScheduleSaved,
  size = "sm",
}: {
  courseId: string;
  courseName: string;
  onScheduleSaved?: () => void;
  size?: "sm" | "default";
}) {
  // TUTE-01: shown only when a Tenant Admin granted TUTE_VIEW_TUTES; the backend also limits it to own courses.
  const canViewTutes = useTuteCapabilities().can(TUTE_CAP.VIEW_TUTES);
  // CAL-01: the dialog loads `canEdit` from GET class-schedule and is read-only when false.
  const [scheduleOpen, setScheduleOpen] = useState(false);
  const t = useT();

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              type="button"
              variant="outline"
              size={size}
              aria-label={t("teacher.courses.manageCourseFor", { course: courseName })}
              data-testid="teacher-course-manage"
            />
          }
        >
          <Settings2 aria-hidden="true" />
          {t("teacher.courses.manageCourse")}
          <ChevronDown aria-hidden="true" />
        </DropdownMenuTrigger>
        <DropdownMenuContent>
          <DropdownMenuLinkItem render={<Link href={`/teacher/courses/${courseId}/edit`} />}>
            <Pencil aria-hidden="true" />
            {t("teacher.courses.menu.editDetails")}
          </DropdownMenuLinkItem>
          <DropdownMenuLinkItem render={<Link href={`/teacher/courses/${courseId}/modules`} />}>
            <BookOpen aria-hidden="true" />
            {t("teacher.courses.menu.modules")}
          </DropdownMenuLinkItem>
          <DropdownMenuLinkItem render={<Link href={`/teacher/courses/${courseId}/roster`} />}>
            <Users aria-hidden="true" />
            {t("teacher.courses.menu.roster")}
          </DropdownMenuLinkItem>
          {canViewTutes ? (
            <DropdownMenuLinkItem
              render={<Link href={`/teacher/courses/${courseId}/tutes`} />}
              data-testid="teacher-course-tutes"
            >
              <Truck aria-hidden="true" />
              {t("teacher.courses.menu.tutes")}
            </DropdownMenuLinkItem>
          ) : null}
          <DropdownMenuLinkItem render={<Link href={`/teacher/live-classes?courseId=${courseId}`} />}>
            <ClipboardList aria-hidden="true" />
            {t("teacher.courses.menu.liveClasses")}
          </DropdownMenuLinkItem>
          <DropdownMenuItem onClick={() => setScheduleOpen(true)} data-testid="teacher-course-class-schedule">
            <CalendarDays aria-hidden="true" />
            {t("teacher.courses.menu.classSchedule")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      {scheduleOpen ? (
        <EditClassScheduleDialog
          courseId={courseId}
          courseName={courseName}
          open={scheduleOpen}
          onOpenChange={setScheduleOpen}
          onSaved={onScheduleSaved}
        />
      ) : null}
    </>
  );
}
