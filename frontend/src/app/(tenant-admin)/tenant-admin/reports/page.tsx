"use client";

import Link from "next/link";
import { FileBarChart } from "lucide-react";
import { PageHeader } from "@/components/ui/page-header";
import { Card, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { buttonVariants } from "@/components/ui/button";

/**
 * Report catalog (RPT-01, RPT-02; `docs/specs/reporting-catalog.md`). Lists only
 * reports that actually exist - no "coming soon" placeholders. The catalog
 * itself loads no data; each report page renders its own backend-verified
 * loading / error / permission-denied states (`/api/v1/reports/**` enforces
 * `FINANCE_EXPENSES`/`VIEW`; the nav entry is UX only).
 */
const REPORTS = [
  {
    href: "/tenant-admin/reports/teacher-income",
    title: "Teacher Income Summary Report",
    description: "View earnings of teachers for a selected period or specific months.",
  },
  {
    href: "/tenant-admin/reports/student-payments",
    title: "Student Payment Report",
    description: "Find student payments made within a selected time frame or specific months.",
  },
  {
    href: "/tenant-admin/reports/attendance",
    title: "Attendance Report",
    description: "Generate a student attendance report for a selected date range.",
  },
  {
    href: "/tenant-admin/reports/course-summary",
    title: "Course Summary Report",
    description: "Generate detailed course insights for a selected teacher, course, and time period.",
  },
  {
    href: "/tenant-admin/reports/unpaid-students",
    title: "Unpaid Students Report",
    description: "Generate a list of unpaid students for a selected period or specific months.",
  },
  {
    href: "/tenant-admin/reports/registrations",
    title: "Registration Report",
    description: "Generate a list of registrations for a selected date range.",
  },
  {
    href: "/tenant-admin/reports/student-behavior",
    title: "Student Behavior Report",
    description: "Generate a report of new, dropped, and continuing students for a selected course and month.",
  },
  {
    href: "/tenant-admin/reports/undo-payments",
    title: "Undo Payments Report",
    description: "Inspect and export records of undone or reversed payments.",
  },
];

export default function ReportsCatalogPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Reports" description="Generate institute reports on screen and export them as PDF." />
      <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3" aria-label="Available reports">
        {REPORTS.map((report) => (
          <li key={report.href}>
            <Card className="h-full">
              <CardHeader>
                <FileBarChart className="size-6 text-muted-foreground" aria-hidden="true" />
                <CardTitle>
                  <h2>{report.title}</h2>
                </CardTitle>
                <CardDescription>{report.description}</CardDescription>
              </CardHeader>
              <CardFooter className="mt-auto border-t py-3">
                <Link
                  href={report.href}
                  className={buttonVariants({ size: "sm" })}
                >
                  Open report<span className="sr-only">: {report.title}</span>
                </Link>
              </CardFooter>
            </Card>
          </li>
        ))}
      </ul>
    </div>
  );
}
