"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  CheckCircle2,
  Download,
  Eye,
  FileText,
  KeyRound,
  LogIn,
  MessageSquare,
  MoreVertical,
  Pencil,
  Plus,
  QrCode,
  Trash2,
  Upload,
} from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { useCapabilities } from "@/lib/api/my-permissions";
import { CAP } from "@/lib/auth/capabilities";
import {
  canDeleteStudents,
  canImpersonateStudents,
  canManageStudents,
  canSendCommunications,
} from "@/lib/auth/permissions";
import {
  DIRECTORY_FIELD_OPTIONS,
  useApproveStudent,
  useExportDirectoryCsv,
  useStudentDirectory,
  studentErrorMessage,
  type DirectoryField,
  type DirectoryTab,
  type StudentDirectoryRow,
} from "@/lib/api/student-management";
import { useTenantTimeZone } from "@/lib/api/klass-admin";
import { isApiClientError } from "@/lib/api/error";
import { formatZonedDateTime } from "@/lib/datetime";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tooltip } from "@/components/ui/tooltip";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLinkItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { cn } from "@/lib/utils";
import { StudentFormSheet } from "./student-form-sheet";
import { StudentDetailsDrawer } from "./student-details-drawer";
import { BulkQrDialog } from "./bulk-qr-dialog";
import {
  DeleteStudentDialog,
  LogAsStudentDialog,
  type StudentRef,
} from "./student-action-dialogs";
import { ResetPasswordDialog } from "./[studentId]/reset-password-dialog";

const PAGE_SIZE = 20;
const DEBOUNCE_MS = 300;

type DialogState =
  | { kind: "delete"; student: StudentRef }
  | { kind: "impersonate"; student: StudentRef }
  | { kind: "reset"; student: StudentRef }
  | null;

function fullName(row: Pick<StudentDirectoryRow, "firstName" | "lastName" | "email">): string {
  const name = [row.firstName, row.lastName].filter(Boolean).join(" ").trim();
  return name || row.email;
}

function toRef(row: StudentDirectoryRow): StudentRef {
  return { id: row.id, name: fullName(row), indexNumber: row.indexNumber };
}

function useDebounced<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timeout = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timeout);
  }, [value, delay]);
  return debounced;
}

/**
 * Students directory (STU-MGMT-02, Klass parity): Active / Pending / Black
 * List (not available) tabs, server-side search by field with a 300 ms
 * debounce, server pagination, CSV/QR export, and per-row actions. Visibility
 * of actions is UX only — every endpoint re-checks permissions server-side.
 */
export default function StudentDirectoryPage() {
  const { session } = useAuth();
  const role = session?.role ?? null;
  // UX only. Static roles keep their role helpers; Operator / Manager follow
  // their effective capabilities (STAFF-MGMT-02). Log as Student is never
  // available to them (ADR-033, LOCKED).
  const { can, isCapabilityRole } = useCapabilities();
  const legacyManage = canManageStudents(role);
  const canManage = can(CAP.STUDENTS_EDIT, legacyManage);
  const canAdd = can(CAP.STUDENTS_ADD, legacyManage);
  const canApprove = can(CAP.STUDENTS_APPROVE_PENDING, legacyManage);
  const canResetPassword = can(CAP.STUDENTS_RESET_PASSWORD, legacyManage);
  const canExportCsv = can(CAP.STUDENTS_EXPORT_CSV, true);
  const canExportQr = can(CAP.STUDENTS_EXPORT_QR, true);
  const canDelete = can(CAP.STUDENTS_DELETE, canDeleteStudents(role));
  const canImpersonate = !isCapabilityRole && canImpersonateStudents(role);
  const canMessage = can(CAP.STUDENTS_SEND_NOTIFICATION, canSendCommunications(role));
  const { timeZone } = useTenantTimeZone();

  const [tab, setTab] = useState<DirectoryTab>("ACTIVE");
  const [field, setField] = useState<DirectoryField>("ALL");
  const [searchInput, setSearchInput] = useState("");
  const q = useDebounced(searchInput, DEBOUNCE_MS);
  const [page, setPage] = useState(0);
  // Any filter change goes back to the first page (React's "adjust state on prop change" pattern).
  const filterKey = `${tab}|${field}|${q.trim()}`;
  const [lastFilterKey, setLastFilterKey] = useState(filterKey);
  if (filterKey !== lastFilterKey) {
    setLastFilterKey(filterKey);
    setPage(0);
  }

  const params = useMemo(() => ({ tab, field, q: q.trim(), page, size: PAGE_SIZE }), [tab, field, q, page]);
  const directoryQuery = useStudentDirectory(params);
  const exportCsv = useExportDirectoryCsv();
  const approve = useApproveStudent();

  const [formState, setFormState] = useState<{ open: boolean; mode: { kind: "create" } | { kind: "edit"; studentId: string } }>({
    open: false,
    mode: { kind: "create" },
  });
  const [drawerId, setDrawerId] = useState<string | null>(null);
  const [qrOpen, setQrOpen] = useState(false);
  const [dialog, setDialog] = useState<DialogState>(null);
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 6000);
    return () => clearTimeout(timeout);
  }, [notice]);

  const openCreate = () => setFormState({ open: true, mode: { kind: "create" } });
  const openEdit = (id: string) => setFormState({ open: true, mode: { kind: "edit", studentId: id } });

  const approveRow = (row: StudentDirectoryRow) => {
    approve.mutate(row.id, {
      onSuccess: () => setNotice(`${fullName(row)} was approved.`),
      onError: (error) =>
        setNotice(studentErrorMessage(error, "The student couldn't be approved. Please try again.")),
    });
  };

  const renderActions = (row: StudentDirectoryRow) => (
    <RowActions
      row={row}
      tab={tab}
      canManage={canManage}
      canApprove={canApprove}
      canResetPassword={canResetPassword}
      canDelete={canDelete}
      canImpersonate={canImpersonate}
      canMessage={canMessage}
      approving={approve.isPending && approve.variables === row.id}
      onApprove={() => approveRow(row)}
      onView={() => setDrawerId(row.id)}
      onEdit={() => openEdit(row.id)}
      onDialog={(kind) => setDialog({ kind, student: toRef(row) })}
    />
  );

  const columns: DataTableColumn<StudentDirectoryRow>[] = [
    {
      key: "index",
      header: "Index Number",
      cell: (row) => <span className="font-mono tabular-nums">{row.indexNumber ?? "—"}</span>,
    },
    { key: "firstName", header: "First Name", cell: (row) => row.firstName ?? "—", hideOnCard: true },
    { key: "lastName", header: "Last Name", cell: (row) => row.lastName ?? "—", hideOnCard: true },
    { key: "email", header: "Email", cell: (row) => <span className="break-all">{row.email}</span> },
    { key: "mobile", header: "Mobile", cell: (row) => <span className="tabular-nums">{row.mobile ?? "—"}</span> },
    {
      key: "lastLogin",
      header: "Last Login",
      cell: (row) => <span className="whitespace-nowrap tabular-nums">{formatZonedDateTime(row.lastLoginAt, timeZone, "Never")}</span>,
    },
    { key: "actions", header: "Actions", cell: renderActions, hideOnCard: true },
  ];

  const trimmedQuery = q.trim();
  const clearSearch = () => {
    setSearchInput("");
    setField("ALL");
  };

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Students</h1>
          <p className="text-sm text-muted-foreground">Register, find and support this institute&apos;s students.</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {canExportCsv ? (
            <Button
              type="button"
              variant="outline"
              disabled={exportCsv.isPending}
              aria-busy={exportCsv.isPending}
              onClick={() => exportCsv.mutate({ tab, field, q: trimmedQuery })}
            >
              <Download aria-hidden="true" />
              {exportCsv.isPending ? "Exporting…" : "Export as CSV"}
            </Button>
          ) : null}
          {canExportQr ? (
            <Button type="button" variant="outline" aria-haspopup="dialog" onClick={() => setQrOpen(true)}>
              <QrCode aria-hidden="true" />
              Export QRs
            </Button>
          ) : null}
          {canAdd ? (
            <>
              <Link href="/tenant-admin/students/bulk-import" className={buttonVariants({ variant: "outline" })}>
                <Upload aria-hidden="true" />
                Bulk import
              </Link>
              <Button type="button" aria-haspopup="dialog" onClick={openCreate}>
                <Plus aria-hidden="true" />
                Add Student
              </Button>
            </>
          ) : null}
        </div>
      </div>

      {exportCsv.isError ? (
        <Alert variant="destructive">
          <AlertDescription role="alert">
            {isApiClientError(exportCsv.error) ? exportCsv.error.message : "The CSV export failed. Please try again."}
          </AlertDescription>
        </Alert>
      ) : null}

      <div role="status" aria-live="polite">
        {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      </div>

      <div role="tablist" aria-label="Student lists" className="flex gap-1 border-b border-border">
        {(
          [
            ["ACTIVE", "Active"],
            ["PENDING", "Pending"],
          ] as const
        ).map(([value, label]) => (
          <button
            key={value}
            type="button"
            role="tab"
            aria-selected={tab === value}
            data-testid={`students-tab-${value.toLowerCase()}`}
            onClick={() => setTab(value)}
            className={cn(
              "-mb-px border-b-2 px-4 py-2 text-sm font-medium uppercase tracking-wide",
              tab === value ? "border-primary text-primary" : "border-transparent text-muted-foreground hover:text-foreground"
            )}
          >
            {label}
          </button>
        ))}
        <Tooltip label="Not available">
          <button
            type="button"
            role="tab"
            aria-selected={false}
            aria-disabled="true"
            data-testid="students-tab-blacklist"
            onClick={(event) => event.preventDefault()}
            className="-mb-px cursor-not-allowed border-b-2 border-transparent px-4 py-2 text-sm font-medium uppercase tracking-wide text-muted-foreground/60"
          >
            Black List
          </button>
        </Tooltip>
      </div>

      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="flex flex-col gap-1.5 sm:w-52">
          <Label htmlFor="student-search-field">Search Field</Label>
          <select
            id="student-search-field"
            value={field}
            onChange={(event) => setField(event.target.value as DirectoryField)}
            className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
          >
            {DIRECTORY_FIELD_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
        <div className="flex flex-1 flex-col gap-1.5">
          <Label htmlFor="student-search">Search</Label>
          <Input
            id="student-search"
            type="search"
            placeholder="Search students"
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
          />
        </div>
      </div>

      <QueryStateBoundary
        query={directoryQuery}
        loadingLabel="Loading students…"
        isEmpty={(data) => data.content.length === 0}
        emptyState={
          trimmedQuery
            ? {
                title: "No students match your search",
                description: `No ${tab === "PENDING" ? "pending" : "active"} student matches “${trimmedQuery}”. Try another search field or term.`,
                action: { label: "Clear search", onClick: clearSearch },
              }
            : tab === "PENDING"
              ? {
                  title: "No pending registrations",
                  description: "Self-registrations waiting for approval appear here when approval is required.",
                }
              : {
                  title: "No students yet",
                  description: canAdd
                    ? "Add your first student, or bulk-import a CSV of existing students."
                    : "This institute doesn't have any students yet.",
                  action: canAdd ? { label: "Add Student", onClick: openCreate } : undefined,
                }
        }
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        loginPath="/login"
        genericErrorMessage="Students couldn't be loaded."
      >
        {(data) => (
          <div className="flex flex-col gap-3" aria-busy={directoryQuery.isFetching}>
            <DataTable
              columns={columns}
              rows={data.content}
              rowKey={(row) => row.id}
              caption={tab === "PENDING" ? "Pending students" : "Active students"}
              cardHeading={(row) => fullName(row)}
              cardFooter={renderActions}
            />
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="text-xs text-muted-foreground" role="status" aria-live="polite" data-testid="directory-summary">
                {data.totalElements} student{data.totalElements === 1 ? "" : "s"} · Page {data.page + 1} of{" "}
                {Math.max(data.totalPages, 1)}
                {directoryQuery.isFetching ? " · Updating…" : ""}
              </p>
              <div className="flex gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={page === 0 || directoryQuery.isFetching}
                  onClick={() => setPage((current) => Math.max(0, current - 1))}
                >
                  Previous
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={data.page + 1 >= data.totalPages || directoryQuery.isFetching}
                  onClick={() => setPage((current) => current + 1)}
                >
                  Next
                </Button>
              </div>
            </div>
          </div>
        )}
      </QueryStateBoundary>

      {canManage || canAdd ? (
        <StudentFormSheet
          open={formState.open}
          mode={formState.mode}
          onOpenChange={(open) => setFormState((current) => ({ ...current, open }))}
          onSaved={(message) => setNotice(message)}
        />
      ) : null}

      <StudentDetailsDrawer
        studentId={drawerId}
        onClose={() => setDrawerId(null)}
        canManage={canManage}
        onEdit={(id) => {
          setDrawerId(null);
          openEdit(id);
        }}
        onNotice={setNotice}
      />

      <BulkQrDialog open={qrOpen} onOpenChange={setQrOpen} />

      {dialog?.kind === "delete" ? (
        <DeleteStudentDialog
          student={dialog.student}
          open
          onOpenChange={(open) => !open && setDialog(null)}
          onDeleted={setNotice}
        />
      ) : null}
      {dialog?.kind === "impersonate" ? (
        <LogAsStudentDialog student={dialog.student} open onOpenChange={(open) => !open && setDialog(null)} />
      ) : null}
      {dialog?.kind === "reset" ? (
        <ResetPasswordDialog
          student={{ id: dialog.student.id, name: dialog.student.name }}
          open
          onOpenChange={(open) => !open && setDialog(null)}
        />
      ) : null}
    </div>
  );
}

function IconAction({
  label,
  name,
  onClick,
  children,
}: {
  label: string;
  name: string;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <Tooltip label={label}>
      <Button type="button" variant="ghost" size="icon-sm" aria-label={`${label}: ${name}`} onClick={onClick}>
        {children}
      </Button>
    </Tooltip>
  );
}

function RowActions({
  row,
  tab,
  canManage,
  canApprove,
  canResetPassword,
  canDelete,
  canImpersonate,
  canMessage,
  approving,
  onApprove,
  onView,
  onEdit,
  onDialog,
}: {
  row: StudentDirectoryRow;
  tab: DirectoryTab;
  canManage: boolean;
  canApprove: boolean;
  canResetPassword: boolean;
  canDelete: boolean;
  canImpersonate: boolean;
  canMessage: boolean;
  approving: boolean;
  onApprove: () => void;
  onView: () => void;
  onEdit: () => void;
  onDialog: (kind: "delete" | "impersonate" | "reset") => void;
}) {
  const name = fullName(row);
  return (
    <div className="flex items-center gap-0.5" data-testid={`student-row-actions-${row.id}`}>
      {tab === "PENDING" && canApprove ? (
        <Button type="button" size="sm" variant="outline" disabled={approving} onClick={onApprove} aria-label={`Approve: ${name}`}>
          <CheckCircle2 aria-hidden="true" />
          {approving ? "Approving…" : "Approve"}
        </Button>
      ) : null}
      {tab === "ACTIVE" && canImpersonate ? (
        <IconAction label="Login As Student" name={name} onClick={() => onDialog("impersonate")}>
          <LogIn aria-hidden="true" />
        </IconAction>
      ) : null}
      <IconAction label="View Student Details" name={name} onClick={onView}>
        <Eye aria-hidden="true" />
      </IconAction>
      {canManage ? (
        <IconAction label="Edit Student" name={name} onClick={onEdit}>
          <Pencil aria-hidden="true" />
        </IconAction>
      ) : null}
      <DropdownMenu>
        <Tooltip label="More actions">
          <DropdownMenuTrigger
            render={<Button type="button" variant="ghost" size="icon-sm" aria-label={`More actions: ${name}`} />}
          >
            <MoreVertical aria-hidden="true" />
          </DropdownMenuTrigger>
        </Tooltip>
        <DropdownMenuContent>
          <DropdownMenuLinkItem render={<Link href={`/tenant-admin/students/${row.id}`} />}>
            <FileText aria-hidden="true" />
            Comprehensive Report
          </DropdownMenuLinkItem>
          {canMessage ? (
            <DropdownMenuLinkItem
              render={<Link href={`/tenant-admin/communications/new?studentId=${encodeURIComponent(row.id)}`} />}
            >
              <MessageSquare aria-hidden="true" />
              Send Message
            </DropdownMenuLinkItem>
          ) : null}
          {canResetPassword ? (
            <DropdownMenuItem onClick={() => onDialog("reset")}>
              <KeyRound aria-hidden="true" />
              Reset Password
            </DropdownMenuItem>
          ) : null}
          {canDelete ? (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem variant="destructive" onClick={() => onDialog("delete")}>
                <Trash2 aria-hidden="true" />
                Delete Student
              </DropdownMenuItem>
            </>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}
