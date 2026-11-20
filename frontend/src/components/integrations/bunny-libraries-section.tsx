"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { CheckCircle2, CircleSlash, Loader2, PlugZap, TriangleAlert } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import {
  useBunnyLibraries,
  useCreateBunnyLibrary,
  type BunnyLibraryStatus,
  type TeacherLibraryRow,
} from "@/lib/api/integrations";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";

import { useT, type TranslateFn } from "@/i18n/i18n-provider";
/** `INTERRUPTED`: a setup stuck in PROVISIONING that the backend now offers for retry. */
type LibraryDisplayState = BunnyLibraryStatus | "NONE" | "INTERRUPTED";

const LIBRARY_STATES = (t: TranslateFn): Record<
  LibraryDisplayState,
  { label: string; variant: "default" | "warning" | "outline" | "secondary" | "destructive"; Icon: typeof CheckCircle2 }
> => ({
  ACTIVE: { label: t("teacher.bunny.ready"), variant: "default", Icon: CheckCircle2 },
  PROVISIONING: { label: t("teacher.bunny.beingSetUp"), variant: "secondary", Icon: Loader2 },
  INTERRUPTED: { label: t("teacher.bunny.setupInterrupted"), variant: "warning", Icon: TriangleAlert },
  FAILED: { label: t("teacher.bunny.setupFailed"), variant: "destructive", Icon: TriangleAlert },
  UNAVAILABLE: { label: t("teacher.bunny.unavailable"), variant: "warning", Icon: CircleSlash },
  NONE: { label: t("teacher.bunny.notSetUp"), variant: "outline", Icon: PlugZap },
});

/** Library status chip: text + icon, never color alone (`.claude/rules/ui-ux.md` §4). */
export function LibraryStatusBadge({ status }: { status: LibraryDisplayState | null }) {
  const t = useT();
  const { label, variant, Icon } = LIBRARY_STATES(t)[status ?? "NONE"];
  return (
    <Badge variant={variant}>
      <Icon aria-hidden="true" />
      {label}
    </Badge>
  );
}

/**
 * What the admin table shows for a row. A PROVISIONING row the backend says
 * can be created again was interrupted (UI/UX review UX-1) - shown as such,
 * with a Retry action, instead of "Being set up" forever.
 */
function rowDisplayState(row: TeacherLibraryRow): LibraryDisplayState {
  const status = row.library?.status ?? null;
  if (status === "PROVISIONING" && row.canCreateLibrary) return "INTERRUPTED";
  return status ?? "NONE";
}

/**
 * Tenant Admin → Integrations → Teacher video libraries (MEDIA-INT-01A).
 *
 * Each teacher gets their own library inside the institute's Bunny.net
 * account. Libraries are created automatically on a teacher's first upload;
 * a Tenant Admin can also create one now, or retry a failed attempt. The
 * backend re-checks `INTEGRATIONS` and that the teacher belongs to this
 * institute on every call - this section only reflects what it returns.
 */
export function BunnyLibrariesSection({ canManage, bunnyConnected }: { canManage: boolean; bunnyConnected: boolean }) {
  const t = useT();
  const router = useRouter();
  const query = useBunnyLibraries();
  const mutation = useCreateBunnyLibrary();
  const [pendingTeacherId, setPendingTeacherId] = useState<string | null>(null);
  // Always-mounted live regions: only their text changes, so screen readers announce it (UX-3).
  const [success, setSuccess] = useState("");
  const [failure, setFailure] = useState("");
  const successRef = useRef<HTMLParagraphElement>(null);

  async function create(row: TeacherLibraryRow) {
    setSuccess("");
    setFailure("");
    setPendingTeacherId(row.teacherUserId);
    try {
      await mutation.mutateAsync(row.teacherUserId);
      setSuccess(`${row.teacherName || "The teacher"}'s video library is ready.`);
      // The clicked button disappears once the row is Ready - keep keyboard focus nearby (UX-2).
      requestAnimationFrame(() => successRef.current?.focus());
    } catch (error) {
      setFailure(isApiClientError(error) ? error.message : t("teacher.bunny.theLibraryCouldNot"));
    } finally {
      setPendingTeacherId(null);
    }
  }

  function action(row: TeacherLibraryRow) {
    if (!canManage || !row.canCreateLibrary) return null;
    const retry = row.library !== null;
    const busy = pendingTeacherId === row.teacherUserId;
    const name = row.teacherName || "this teacher";
    return (
      <Button
        type="button"
        size="sm"
        variant="outline"
        onClick={() => create(row)}
        disabled={pendingTeacherId !== null}
        aria-busy={busy}
        aria-label={retry ? `Retry creating ${name}'s library` : `Create ${name}'s library now`}
      >
        {busy ? t("teacher.bunny.creating") : retry ? t("teacher.bunny.retry") : t("teacher.bunny.createLibraryNow")}
      </Button>
    );
  }

  const columns: DataTableColumn<TeacherLibraryRow>[] = [
    { key: "teacher", header: "Teacher", cell: (row) => row.teacherName || "Unknown teacher", hideOnCard: true },
    {
      key: "library",
      header: "Library",
      cell: (row) => <span className="break-all">{row.library?.libraryName ?? "—"}</span>,
    },
    {
      key: "status",
      header: "Status",
      cell: (row) => <LibraryStatusBadge status={rowDisplayState(row)} />,
      hideOnCard: true,
    },
    {
      key: "created",
      header: "Created",
      cell: (row) => (row.library ? formatDateTime(row.library.createdAt) : "—"),
    },
    {
      key: "note",
      header: "Details",
      cell: (row) => {
        const detail =
          rowDisplayState(row) === "INTERRUPTED" ? t("teacher.bunny.theSetupWasInterrupted") : row.library?.lastError;
        return detail ? <span className="text-sm text-muted-foreground">{detail}</span> : "—";
      },
    },
    { key: "actions", header: "Actions", hideOnCard: true, cell: (row) => action(row) },
  ];

  return (
    <section aria-labelledby="bunny-libraries-heading" className="flex flex-col gap-3">
      <div>
        <h2 id="bunny-libraries-heading" className="text-base font-medium text-foreground">
          {t("teacher.bunny.teacherVideoLibraries")}
        </h2>
        <p className="text-sm text-muted-foreground">
          Every teacher gets their own Bunny.net library in your institute&apos;s account. It is created automatically
          when the teacher first uploads a video.
        </p>
      </div>
      {!bunnyConnected ? (
        <p className="text-sm text-muted-foreground">{t("teacher.bunny.connectABunnyNet")}</p>
      ) : null}
      <p
        ref={successRef}
        tabIndex={-1}
        role="status"
        aria-live="polite"
        className="text-sm text-muted-foreground empty:hidden focus:outline-none"
      >
        {success}
      </p>
      <p role="alert" className="text-sm text-destructive empty:hidden">
        {failure}
      </p>
      <QueryStateBoundary
        query={query}
        loadingLabel={t("teacher.bunny.loadingTeacherLibraries")}
        genericErrorMessage={t("teacher.bunny.teacherLibrariesCouldNot")}
        isEmpty={(rows) => rows.length === 0}
        emptyState={{
          title: t("teacher.bunny.noApprovedTeachersYet"),
          description: t("teacher.bunny.onceATeacherIs"),
          action: { label: t("teacher.bunny.manageTeachers"), onClick: () => router.push("/tenant-admin/teachers") },
        }}
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(rows) => (
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(row) => row.teacherUserId}
            caption={t("teacher.bunny.teacherVideoLibraries")}
            cardHeading={(row) => row.teacherName || "Unknown teacher"}
            cardHeadingAdornment={(row) => <LibraryStatusBadge status={rowDisplayState(row)} />}
            cardFooter={(row) => action(row)}
          />
        )}
      </QueryStateBoundary>
    </section>
  );
}

/**
 * Teacher → Settings → Integrations: the teacher's own Bunny library
 * (MEDIA-INT-01A). Read-only - the institute's account and the library are
 * managed by the institute; no ids or keys are shown.
 */
export function TeacherVideoLibraryCard({
  bunnyConnected,
  library,
}: {
  bunnyConnected: boolean;
  library: { status: BunnyLibraryStatus; libraryName: string } | null;
}) {
  const t = useT();
  let description: string;
  if (!bunnyConnected && !library) {
    description = "Your institute hasn't connected Bunny.net yet.";
  } else if (!library) {
    description = "Your video library will be created automatically when you first upload a video.";
  } else if (library.status === "ACTIVE") {
    description = "Your uploaded videos are stored in your own library.";
  } else if (library.status === "PROVISIONING") {
    description = "Your video library is being set up.";
  } else if (library.status === "FAILED") {
    description = "Your video library couldn't be set up yet. Your institute can retry it.";
  } else {
    description = "Your video library is unavailable right now. Contact your institute.";
  }

  return (
    <section aria-labelledby="teacher-video-library-heading" className="flex flex-col gap-2 rounded-lg border border-border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="teacher-video-library-heading" className="text-base font-medium text-foreground">
          {t("teacher.bunny.yourVideoLibrary")}
        </h2>
        <LibraryStatusBadge status={library?.status ?? null} />
      </div>
      <p className="text-sm text-muted-foreground">{description}</p>
      {library ? <p className="break-all text-sm text-foreground">{library.libraryName}</p> : null}
      <p className="text-xs text-muted-foreground">{t("teacher.bunny.hostedOnBunnyNet")}</p>
    </section>
  );
}
