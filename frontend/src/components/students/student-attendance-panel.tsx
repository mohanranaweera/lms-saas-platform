"use client";

import { useT } from "@/i18n/i18n-provider";
import { useLocaleFormat } from "@/i18n/use-locale-format";

import { CalendarCheck, CheckCircle2, Clock3, XCircle } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { useMyAttendance, useMyAttendanceSummary } from "@/lib/api/attendance";

export function StudentAttendancePanel() {
  const summary = useMyAttendanceSummary();
  const records = useMyAttendance({ page: 0, size: 50 });
  const t = useT();
  const { formatDateTime } = useLocaleFormat();
  return <div className="flex flex-col gap-6"><section className="rounded-xl border border-border bg-card p-5 shadow-sm"><h2 className="text-lg font-semibold text-foreground">{t("student.attendancePanel.summary")}</h2><QueryStateBoundary query={summary} loadingLabel={t("student.attendancePanel.summaryLoading")} isEmpty={(rows) => rows.length === 0} emptyState={{ title: t("student.attendanceSummary.empty.title"), description: t("student.attendancePanel.summaryEmpty") }}>{(rows) => <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">{rows.map((row) => <div key={row.courseId} className="rounded-lg border border-border p-4"><p className="font-medium text-foreground">{row.courseName ?? t("student.common.courseIdLabel")}</p><p className="mt-2 text-2xl font-semibold text-primary">{row.attendanceRate}%</p><p className="text-xs text-muted-foreground">{t("student.attendancePanel.counts", { present: row.present, late: row.late, absent: row.absent, total: row.total })}</p></div>)}</div>}</QueryStateBoundary></section><section className="rounded-xl border border-border bg-card p-5 shadow-sm"><h2 className="text-lg font-semibold text-foreground">{t("student.attendancePanel.records")}</h2><QueryStateBoundary query={records} loadingLabel={t("student.attendancePanel.recordsLoading")} isEmpty={(page) => page.content.length === 0} emptyState={{ title: t("student.attendance.empty.title"), description: t("student.attendancePanel.recordsEmpty") }}>{(page) => <ul className="mt-4 flex flex-col divide-y divide-border">{page.content.map((record) => <li key={record.id} className="flex flex-col gap-2 py-4 sm:flex-row sm:items-center sm:justify-between"><div><p className="font-medium text-foreground">{record.classSessionTitle ?? t("student.attendancePanel.session")}</p><p className="text-xs text-muted-foreground">{t("student.attendance.marked", { when: formatDateTime(record.markedAt) })}</p></div><span className={`inline-flex w-fit items-center gap-1 rounded-full px-3 py-1 text-xs font-semibold ${record.status === "PRESENT" ? "bg-green-100 text-green-800" : record.status === "LATE" ? "bg-amber-100 text-amber-800" : "bg-red-100 text-red-800"}`}>{record.status === "PRESENT" ? <CheckCircle2 className="size-3" aria-hidden="true" /> : record.status === "LATE" ? <Clock3 className="size-3" aria-hidden="true" /> : <XCircle className="size-3" aria-hidden="true" />}{record.status === "PRESENT" ? t("student.attendancePanel.present") : record.status === "LATE" ? t("student.attendancePanel.late") : t("student.attendancePanel.absent")}</span></li>)}</ul>}</QueryStateBoundary></section></div>;
}
