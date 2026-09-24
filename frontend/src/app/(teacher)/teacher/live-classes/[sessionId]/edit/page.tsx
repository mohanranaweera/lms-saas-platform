"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ClassSessionEditForm } from "@/components/live-classes/class-session-edit-form";
import { useClassSession } from "@/lib/api/class-sessions";

/** Teacher "Edit Live Class" route — reachable/functional only while `status === "SCHEDULED"` (see `ClassSessionEditForm`). */
export default function EditLiveClassPage() {
  const params = useParams<{ sessionId: string }>();
  const sessionId = params.sessionId;
  const query = useClassSession(sessionId);

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href={`/teacher/live-classes/${sessionId}`}
          className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          ← Back to live class
        </Link>
      </div>

      <div>
        <h1 className="text-xl font-semibold text-foreground">Edit live class</h1>
        <p className="text-sm text-muted-foreground">
          Update this session&apos;s title, description, or schedule window.
        </p>
      </div>

      <div className="w-full max-w-xl">
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading live class…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
          genericErrorMessage="This live class could not be found."
        >
          {(session) => <ClassSessionEditForm session={session} />}
        </QueryStateBoundary>
      </div>
    </div>
  );
}
