"use client";
import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { PageHeader } from "@/components/ui/page-header";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useUndoPaymentOptions } from "@/lib/api/undo-payment-report";
import { UndoPaymentWorkspace } from "./workspace";
export default function UndoPaymentsPage() { const query = useUndoPaymentOptions(); return <div className="flex flex-col gap-6"><Link href="/tenant-admin/reports" className="inline-flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground hover:underline"><ChevronLeft className="size-4" aria-hidden="true" /> All reports</Link><PageHeader title="Undo Payments Report" description="Inspect and export records of undone or reversed payments." /><QueryStateBoundary query={query} loadingLabel="Loading undo payments report options…" loginPath="/login" permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }} genericErrorMessage="The undo payments report options could not be loaded.">{(options) => <UndoPaymentWorkspace options={options} />}</QueryStateBoundary></div>; }
