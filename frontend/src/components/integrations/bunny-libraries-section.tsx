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

/** `INTERRUPTED`: a setup stuck in PROVISIONING that the backend now offers for retry. */
type LibraryDisplayState = BunnyLibraryStatus | "NONE" | "INTERRUPTED";

const LIBRARY_STATES: Record<
  LibraryDisplayState,
  { label: string; variant: "default" | "warning" | "outline" | "secondary" | "destructive"; Icon: typeof CheckCircle2 }
> = {
  ACTIVE: { label: "Ready", variant: "default", Icon: CheckCircle2 },
  PROVISIONING: { label: "Being set up", variant: "secondary", Icon: Loader2 },
  INTERRUPTED: { label: "Setup interrupted", variant: "warning", Icon: TriangleAlert },
  FAILED: { label: "Setup failed", variant: "destructive", Icon: TriangleAlert },
  UNAVAILABLE: { label: "Unavailable", variant: "warning", Icon: CircleSlash },
  NONE: { label: "Not set up", variant: "outline", Icon: PlugZap },
};

/** Library status chip: text + icon, never color alone (`.claude/rules/ui-ux.md` §4). */
export function LibraryStatusBadge({ status }: { status: LibraryDisplayState | null }) {
  const { label, variant, Icon } = LIBRARY_STATES[status ?? "NONE"];
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
      setFailure(isApiClientError(error) ? error.message : "The library could not be created. Please try again.");
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
        {busy ? "Creating…" : retry ? "Retry" : "Create library now"}
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
          rowDisplayState(row) === "INTERRUPTED" ? "The setup was interrupted. Retry to finish it." : row.library?.lastError;
        return detail ? <span className="text-sm text-muted-foreground">{detail}</span> : "—";
      },
    },
    { key: "actions", header: "Actions", hideOnCard: true, cell: (row) => action(row) },
  ];

  return (
    <section aria-labelledby="bunny-libraries-heading" className="flex flex-col gap-3">
      <div>
        <h2 id="bunny-libraries-heading" className="text-base font-medium text-foreground">
          Teacher video libraries
        </h2>
        <p className="text-sm text-muted-foreground">
          Every teacher gets their own Bunny.net library in your institute&apos;s account. It is created automatically
          when the teacher first uploads a video.
        </p>
      </div>
      {!bunnyConnected ? (
        <p className="text-sm text-muted-foreground">Connect a Bunny.net account above to create teacher libraries.</p>
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
        loadingLabel="Loading teacher libraries…"
        genericErrorMessage="Teacher libraries could not be loaded."
        isEmpty={(rows) => rows.length === 0}
        emptyState={{
          title: "No approved teachers yet",
          description: "Once a teacher is approved, they will get their own video library here.",
          action: { label: "Manage teachers", onClick: () => router.push("/tenant-admin/teachers") },
        }}
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(rows) => (
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(row) => row.teacherUserId}
            caption="Teacher video libraries"
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
          Your video library
        </h2>
        <LibraryStatusBadge status={library?.status ?? null} />
      </div>
      <p className="text-sm text-muted-foreground">{description}</p>
      {library ? <p className="break-all text-sm text-foreground">{library.libraryName}</p> : null}
      <p className="text-xs text-muted-foreground">Hosted on Bunny.net by your institute.</p>
    </section>
  );
}
