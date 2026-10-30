"use client";

import { useEffect, useId, useState, type ReactNode } from "react";
import { Search, UserPlus } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { ErrorState } from "@/components/states/error-state";
import { PermissionDeniedState } from "@/components/states/permission-denied-state";
import { isApiClientError } from "@/lib/api/error";
import {
  candidateName,
  useAddExamEnrollments,
  useEnrollmentCandidates,
  type AddEnrollmentsResponse,
  type EnrollmentCandidate,
} from "@/lib/api/exam-enrollment";
import { cn } from "@/lib/utils";

const SEARCH_DEBOUNCE_MS = 300;

/**
 * "Enroll students" (Slice C): search the exam course's participants who are
 * not yet enrolled, pick several, add them. The server re-checks that each id
 * is an active participant of the course in this tenant.
 */
export function EnrollStudentsDialog({
  examId,
  examName,
  open,
  onOpenChange,
  onEnrolled,
}: {
  examId: string;
  examName: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onEnrolled: (result: AddEnrollmentsResponse) => void;
}) {
  const searchId = useId();
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<Map<string, EnrollmentCandidate>>(new Map());
  const [error, setError] = useState<string | null>(null);
  const add = useAddExamEnrollments(examId);
  const query = useEnrollmentCandidates(examId, search, { enabled: open });

  useEffect(() => {
    const handle = setTimeout(() => setSearch(searchInput.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(handle);
  }, [searchInput]);

  function reset() {
    setSearchInput("");
    setSearch("");
    setSelected(new Map());
    setError(null);
  }

  function toggle(candidate: EnrollmentCandidate) {
    setSelected((prev) => {
      const next = new Map(prev);
      if (next.has(candidate.studentId)) next.delete(candidate.studentId);
      else next.set(candidate.studentId, candidate);
      return next;
    });
  }

  const shown = query.data ?? [];
  const allShownSelected = shown.length > 0 && shown.every((candidate) => selected.has(candidate.studentId));

  function toggleAllShown() {
    setSelected((prev) => {
      const next = new Map(prev);
      if (allShownSelected) shown.forEach((candidate) => next.delete(candidate.studentId));
      else shown.forEach((candidate) => next.set(candidate.studentId, candidate));
      return next;
    });
  }

  async function submit() {
    if (selected.size === 0) {
      setError("Select at least one student to enroll.");
      return;
    }
    setError(null);
    try {
      const result = await add.mutateAsync([...selected.keys()]);
      onEnrolled(result);
      reset();
      onOpenChange(false);
    } catch (failure) {
      setError(mutationErrorMessage(failure));
    }
  }

  let list: ReactNode;
  if (query.status === "pending") {
    list = (
      <div role="status" aria-busy="true" className="flex flex-col gap-2">
        <span className="sr-only">Loading students…</span>
        {Array.from({ length: 4 }, (_, index) => (
          <Skeleton key={index} className="h-11 w-full" />
        ))}
      </div>
    );
  } else if (query.status === "error") {
    list =
      isApiClientError(query.error) && query.error.status === 403 ? (
        <PermissionDeniedState error={query.error} />
      ) : (
        <ErrorState message="We couldn't load the course's students." onRetry={() => void query.refetch()} />
      );
  } else if (shown.length === 0) {
    list = (
      <p role="status" className="rounded-lg border border-dashed border-border px-4 py-8 text-center text-sm text-muted-foreground" data-testid="enroll-candidates-empty">
        {search
          ? `No students match “${search}”. Try another name or index number.`
          : "Every student in this course is already enrolled in this exam."}
      </p>
    );
  } else {
    list = (
      <fieldset className="flex flex-col gap-2" aria-busy={query.isFetching}>
        <legend className="sr-only">Students not yet enrolled</legend>
        <label className="flex items-center gap-3 px-3 py-1 text-sm font-medium text-foreground">
          <input type="checkbox" checked={allShownSelected} onChange={toggleAllShown} className="size-4 accent-primary" data-testid="enroll-select-all" />
          Select all shown ({shown.length})
        </label>
        <ul className="flex max-h-80 flex-col gap-1 overflow-y-auto" data-testid="enroll-candidates">
          {shown.map((candidate) => {
            const checked = selected.has(candidate.studentId);
            return (
              <li key={candidate.studentId}>
                <label
                  className={cn(
                    "flex min-w-0 cursor-pointer items-center gap-3 rounded-lg border px-3 py-2 text-sm has-[:focus-visible]:ring-3 has-[:focus-visible]:ring-ring/50",
                    checked ? "border-primary bg-primary/5" : "border-border hover:bg-muted/50"
                  )}
                  data-testid="enroll-candidate"
                >
                  <input type="checkbox" checked={checked} onChange={() => toggle(candidate)} className="size-4 shrink-0 accent-primary" />
                  <span className="w-24 shrink-0 font-mono text-xs text-muted-foreground">{candidate.indexNumber ?? "—"}</span>
                  <span className="min-w-0 flex-1 truncate text-foreground">{candidateName(candidate)}</span>
                </label>
              </li>
            );
          })}
        </ul>
      </fieldset>
    );
  }

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={(next) => {
        if (!next) reset();
        onOpenChange(next);
      }}
      title="Enroll students"
      description={`Add students of this course to “${examName}”. Only enrolled students can take the exam.`}
      testId="enroll-students-dialog"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={add.isPending}>
            Cancel
          </Button>
          <Button type="button" onClick={() => void submit()} disabled={add.isPending} aria-busy={add.isPending} data-testid="enroll-submit">
            <UserPlus aria-hidden="true" />
            {add.isPending
              ? "Enrolling…"
              : selected.size > 0
                ? `Enroll ${selected.size} ${selected.size === 1 ? "student" : "students"}`
                : "Enroll"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={searchId}>Search students</Label>
          <div className="relative">
            <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input
              id={searchId}
              type="search"
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              placeholder="Name or index number"
              className="pl-8"
              data-testid="enroll-search"
            />
          </div>
        </div>
        <p className="text-sm text-muted-foreground" aria-live="polite" data-testid="enroll-selected-count">
          {selected.size} selected
        </p>
        {list}
        {error ? (
          <Alert variant="destructive" data-testid="enroll-error">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        ) : null}
      </div>
    </AdminFormDialog>
  );
}
