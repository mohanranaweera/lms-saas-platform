"use client";

import { useEffect, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { AlertCircle, Info, Search } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { isApiClientError } from "@/lib/api/error";
import {
  useAssignFulfillments,
  useEligibleStudents,
  type AssignResultView,
  type FulfillmentMethod,
} from "@/lib/api/tutes";
import { detailNumber, displayIndex } from "@/lib/tutes/tute-form";

export const ASSIGN_NOTE = "Assigning a tute never charges the student or unlocks paid lessons.";
export const ASSIGN_MAX = 200;

const assignSchema = z.object({
  studentIds: z
    .array(z.string())
    .min(1, { message: "Select at least one student." })
    .max(ASSIGN_MAX, { message: `Select at most ${ASSIGN_MAX} students at a time.` }),
  method: z.enum(["POSTAL", "COLLECTION"]),
});

type AssignValues = z.infer<typeof assignSchema>;

/**
 * "Assign students" (TUTE_ENROLL): search the course's eligible students,
 * tick several, choose Postal (default) or Collection. Students already
 * holding a copy are shown disabled. Out-of-stock (409 `TUTE_OUT_OF_STOCK`)
 * writes nothing and reports how many copies are left. Mount with a fresh
 * `key` per open.
 */
export function AssignStudentsDialog({
  courseId,
  tuteId,
  tuteName,
  open,
  onOpenChange,
  onAssigned,
}: {
  courseId: string;
  tuteId: string;
  tuteName: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAssigned: (result: AssignResultView) => void;
}) {
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [names, setNames] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const eligible = useEligibleStudents(courseId, tuteId, search, open);
  const assign = useAssignFulfillments(courseId, tuteId);
  const {
    handleSubmit,
    setValue,
    control,
    register,
    formState: { errors },
  } = useForm<AssignValues>({
    resolver: zodResolver(assignSchema),
    defaultValues: { studentIds: [], method: "POSTAL" },
  });
  const studentIds = useWatch({ control, name: "studentIds" });

  useEffect(() => {
    const handle = setTimeout(() => setSearch(searchInput.trim()), 300);
    return () => clearTimeout(handle);
  }, [searchInput]);

  const toggle = (id: string, name: string) => {
    const next = studentIds.includes(id) ? studentIds.filter((value) => value !== id) : [...studentIds, id];
    setValue("studentIds", next, { shouldValidate: next.length > 0 });
    setNames((current) => ({ ...current, [id]: name }));
  };

  const onSubmit = handleSubmit((values) => {
    setFormError(null);
    assign.mutate(
      { studentIds: values.studentIds, method: values.method as FulfillmentMethod },
      {
        onSuccess: onAssigned,
        onError: (error) => {
          if (isApiClientError(error) && error.code === "TUTE_OUT_OF_STOCK") {
            const available = detailNumber(error.details, "available");
            const requested = detailNumber(error.details, "requested") ?? values.studentIds.length;
            setFormError(
              available !== null
                ? `Not enough copies: ${available} available, ${requested} requested. Nothing was assigned. Raise the quantity or select fewer students.`
                : error.message
            );
          } else if (isApiClientError(error) && error.code === "TUTE_ARCHIVED") {
            setFormError("This tute is archived and can't be assigned any more.");
          } else {
            setFormError(mutationErrorMessage(error));
          }
        },
      }
    );
  });

  const selectionError = errors.studentIds?.message ?? null;
  const count = studentIds.length;

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && assign.isPending) return;
        onOpenChange(next);
      }}
      title={`Assign students — ${tuteName}`}
      testId="assign-students-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={assign.isPending}>
            Cancel
          </Button>
          <Button type="submit" form="assign-students-form" disabled={assign.isPending} aria-busy={assign.isPending} data-testid="assign-students-submit">
            {assign.isPending ? "Assigning…" : count > 0 ? `Assign ${count} student${count === 1 ? "" : "s"}` : "Assign"}
          </Button>
        </>
      }
    >
      <form id="assign-students-form" noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <Alert role="note" data-testid="assign-students-note">
          <Info aria-hidden="true" />
          <AlertDescription>{ASSIGN_NOTE}</AlertDescription>
        </Alert>
        {formError ? (
          <Alert variant="destructive" data-testid="assign-students-error">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        ) : null}

        <div className="relative">
          <label htmlFor="assign-students-search" className="sr-only">
            Search students by name or index number
          </label>
          <Input
            id="assign-students-search"
            type="search"
            placeholder="Search by name or index number"
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            className="pr-8"
            data-testid="assign-students-search"
          />
          <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
        </div>

        <fieldset className="flex flex-col gap-1" aria-describedby={selectionError ? "assign-students-selection-error" : undefined}>
          <legend className="mb-1 text-sm font-medium">Students</legend>
          <div className="max-h-64 overflow-y-auto rounded-lg border border-border" aria-busy={eligible.isFetching || undefined}>
            {eligible.isPending ? (
              <p className="px-3 py-4 text-sm text-muted-foreground" role="status">
                Loading students…
              </p>
            ) : eligible.isError ? (
              <p className="px-3 py-4 text-sm text-destructive" role="alert">
                {mutationErrorMessage(eligible.error)}
              </p>
            ) : (eligible.data ?? []).length === 0 ? (
              <p className="px-3 py-4 text-sm text-muted-foreground" role="status">
                {search ? "No students in this course match your search." : "No students are enrolled in this course yet."}
              </p>
            ) : (
              <ul className="divide-y divide-border">
                {(eligible.data ?? []).map((student) => {
                  const id = `assign-student-${student.studentId}`;
                  const checked = student.alreadyAssigned || studentIds.includes(student.studentId);
                  return (
                    <li key={student.studentId} className="flex items-center gap-3 px-3 py-2">
                      <input
                        id={id}
                        type="checkbox"
                        className="size-4 accent-primary disabled:cursor-not-allowed"
                        checked={checked}
                        disabled={student.alreadyAssigned || assign.isPending}
                        onChange={() => toggle(student.studentId, student.name)}
                        data-testid="assign-student-checkbox"
                      />
                      <label htmlFor={id} className="flex flex-1 flex-wrap items-center gap-x-2 text-sm">
                        <span className="font-medium">{student.name}</span>
                        <span className="text-muted-foreground tabular-nums">({displayIndex(student.indexNumber)})</span>
                        {student.alreadyAssigned ? <span className="text-xs text-muted-foreground">Already assigned</span> : null}
                      </label>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
          {selectionError ? (
            <p id="assign-students-selection-error" role="alert" className="text-xs text-destructive">
              {selectionError}
            </p>
          ) : null}
          <p className="text-xs text-muted-foreground" aria-live="polite" data-testid="assign-students-count">
            {count === 0
              ? "No students selected."
              : `${count} selected${count <= 3 ? `: ${studentIds.map((id) => names[id]).filter(Boolean).join(", ")}` : ""}.`}
          </p>
        </fieldset>

        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium">Delivery method</legend>
          <label className="flex items-center gap-2 text-sm">
            <input type="radio" value="POSTAL" className="size-4 accent-primary" {...register("method")} data-testid="assign-method-postal" />
            Postal (send by post)
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input type="radio" value="COLLECTION" className="size-4 accent-primary" {...register("method")} data-testid="assign-method-collection" />
            Collection (hand over at class)
          </label>
        </fieldset>
      </form>
    </AdminFormDialog>
  );
}
