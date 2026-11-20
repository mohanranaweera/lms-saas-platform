"use client";

import { useEffect, useRef, useState } from "react";
import { ArrowDown, ArrowUp, Pencil, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { TitleInlineForm } from "@/components/courses/title-inline-form";
import { MaterialsSection } from "@/components/courses/materials-section";
import { SessionFormDialog } from "@/components/courses/admin/session-form-dialog";
import { ConfirmDeleteDialog, mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { formatZonedDateTime } from "@/components/courses/admin/tenant-time";
import { useDeleteCourseLesson } from "@/lib/api/courses";
import { useUpdateKlassLesson, type KlassLesson, type KlassLessonRequest, type TenantTimeZone } from "@/lib/api/klass-admin";

/** PATCH replaces all session metadata, including on an inline rename. */
function renameRequest(lesson: KlassLesson, title: string): KlassLessonRequest {
  return {
    title,
    sequence: lesson.sequence,
    startsAt: lesson.startsAt ?? null,
    endsAt: lesson.endsAt ?? null,
    resourceType: lesson.resourceType ?? null,
    paymentType: lesson.paymentType ?? "COVERED_BY_CLASS_FEES",
    price: lesson.price == null ? null : String(lesson.price),
    description: lesson.description ?? null,
    lessonLabel: lesson.lessonLabel ?? null,
    billingYear: lesson.billingYear ?? null,
    billingMonth: lesson.billingMonth ?? null,
  };
}

export function CourseLessonItem({
  courseId,
  moduleId,
  lesson,
  isFirst,
  isLast,
  reorderBusy,
  onMoveUp,
  onMoveDown,
  timeZone,
}: {
  courseId: string;
  moduleId: string;
  lesson: KlassLesson;
  isFirst: boolean;
  isLast: boolean;
  reorderBusy: boolean;
  onMoveUp: () => void;
  onMoveDown: () => void;
  timeZone: TenantTimeZone;
}) {
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [editKey, setEditKey] = useState(0);
  const containerRef = useRef<HTMLDivElement>(null);
  const rename = useUpdateKlassLesson(courseId, moduleId);
  const remove = useDeleteCourseLesson(courseId, moduleId);
  const disabled = reorderBusy || remove.isPending || rename.isPending;
  const anchorId = `session-${lesson.id}`;

  useEffect(() => {
    if (window.location.hash !== `#${anchorId}`) return;
    const frame = window.requestAnimationFrame(() => {
      containerRef.current?.scrollIntoView({ block: "start" });
      containerRef.current?.focus({ preventScroll: true });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [anchorId]);

  return (
    <div ref={containerRef} id={anchorId} tabIndex={-1} className="scroll-mt-4 flex flex-col gap-3 rounded-md border border-border/70 bg-muted/20 p-3 outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex-1">
          <TitleInlineForm
            idPrefix={`session-${lesson.id}`}
            label="Session title"
            initialTitle={lesson.title}
            submitLabel="Save"
            pendingLabel={`Saving session "${lesson.title}"…`}
            disabled={disabled}
            onSubmit={async (title) => {
              await rename.mutateAsync({ lessonId: lesson.id, body: renameRequest(lesson, title) });
            }}
          />
          <p className="mt-2 text-xs text-muted-foreground">
            Available from {formatZonedDateTime(lesson.startsAt, timeZone.timeZone) || "No start limit"}
            {" · "}To {formatZonedDateTime(lesson.endsAt, timeZone.timeZone) || "No end limit"}
          </p>
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-1">
          <Button type="button" variant="outline" size="icon-xs" aria-label={`Move "${lesson.title}" up`} disabled={isFirst || disabled} onClick={onMoveUp}><ArrowUp aria-hidden="true" /></Button>
          <Button type="button" variant="outline" size="icon-xs" aria-label={`Move "${lesson.title}" down`} disabled={isLast || disabled} onClick={onMoveDown}><ArrowDown aria-hidden="true" /></Button>
          <Button type="button" variant="outline" size="sm" disabled={disabled} aria-label={`Edit session ${lesson.title}`} onClick={() => { setEditKey((key) => key + 1); setEditOpen(true); }}><Pencil aria-hidden="true" /> Edit session</Button>
          <Button type="button" variant="destructive" size="icon-xs" aria-label={`Delete session "${lesson.title}"`} disabled={disabled} onClick={() => { remove.reset(); setDeleteOpen(true); }}><Trash2 aria-hidden="true" /></Button>
        </div>
      </div>

      {editOpen ? (
        <SessionFormDialog
          key={editKey}
          courseId={courseId}
          moduleId={moduleId}
          open
          onOpenChange={setEditOpen}
          lesson={lesson}
          nextSequence={lesson.sequence}
          timeZone={timeZone}
          onSaved={() => setEditOpen(false)}
        />
      ) : null}
      <ConfirmDeleteDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title={`Delete “${lesson.title}”?`}
        description="This deletes the session. Sessions with attached materials, orders or purchases cannot be deleted."
        confirmLabel="Delete session"
        pending={remove.isPending}
        error={remove.isError ? mutationErrorMessage(remove.error) : null}
        onConfirm={() => remove.mutate(lesson.id, { onSuccess: () => setDeleteOpen(false) })}
      />

      <MaterialsSection courseId={courseId} moduleId={moduleId} lessonId={lesson.id} lessonTitle={lesson.title} />
    </div>
  );
}
