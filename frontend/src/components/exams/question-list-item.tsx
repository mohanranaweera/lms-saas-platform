"use client";

import { useState } from "react";
import { Trash2 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { QuestionEditForm } from "@/components/exams/question-edit-form";
import { useAuth } from "@/lib/auth/auth-context";
import { canAuthorExams } from "@/lib/auth/permissions";
import { useDeleteQuestion, type ExamQuestionResponse } from "@/lib/api/exams";
import { isApiClientError } from "@/lib/api/error";

/**
 * One question-bank row (screen #4): view, edit-in-place, delete-with-confirm
 * (409 `CONFLICT` surfaced, not silently retried). Edit/Delete are hidden for
 * a caller who cannot author exams (`canAuthorExams`) — UX convenience only,
 * `QuestionBankService`'s own authoring-access check remains the real
 * enforcement for both the update and delete endpoints regardless.
 */
export function QuestionListItem({ courseId, question }: { courseId: string; question: ExamQuestionResponse }) {
  const { session } = useAuth();
  const canAuthor = canAuthorExams(session?.role ?? null);
  const [isEditing, setIsEditing] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const deleteMutation = useDeleteQuestion(courseId);

  const deleteErrorMessage = deleteMutation.isError
    ? isApiClientError(deleteMutation.error)
      ? deleteMutation.error.message
      : "An unexpected error occurred. Please try again."
    : null;

  const handleDelete = async () => {
    try {
      await deleteMutation.mutateAsync(question.id);
      setDeleteOpen(false);
    } catch {
      // Surfaced via deleteErrorMessage below; keep the dialog open so the teacher can see the failure.
    }
  };

  if (isEditing) {
    return (
      <li className="rounded-lg border border-border p-4">
        <QuestionEditForm
          courseId={courseId}
          question={question}
          onSaved={() => setIsEditing(false)}
          onCancel={() => setIsEditing(false)}
        />
      </li>
    );
  }

  return (
    <li className="flex flex-col gap-2 rounded-lg border border-border p-4">
      <div className="flex flex-col gap-1 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex-1">
          <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {question.questionType === "MCQ" ? "Multiple choice" : "Structured"}
          </span>
          <p className="text-sm text-foreground">{question.body}</p>
        </div>
        {canAuthor ? (
        <div className="flex shrink-0 gap-2">
          <Button type="button" variant="outline" size="sm" onClick={() => setIsEditing(true)}>
            Edit
          </Button>
          <AlertDialog open={deleteOpen} onOpenChange={setDeleteOpen}>
            <AlertDialogTrigger
              render={
                <Button type="button" variant="destructive" size="sm" aria-label="Delete question" />
              }
            >
              <Trash2 className="size-3.5" aria-hidden="true" />
              Delete
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete this question?</AlertDialogTitle>
                <AlertDialogDescription>
                  This permanently removes the question from the bank. If it&apos;s already linked to an exam
                  or has been answered, this will be rejected instead of deleted.
                </AlertDialogDescription>
              </AlertDialogHeader>
              {deleteErrorMessage ? (
                <Alert variant="destructive">
                  <AlertDescription>{deleteErrorMessage}</AlertDescription>
                </Alert>
              ) : null}
              <AlertDialogFooter>
                <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
                <Button
                  type="button"
                  variant="destructive"
                  onClick={handleDelete}
                  disabled={deleteMutation.isPending}
                  aria-busy={deleteMutation.isPending}
                >
                  {deleteMutation.isPending ? "Deleting…" : "Delete question"}
                </Button>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </div>
        ) : null}
      </div>
      {question.options.length > 0 ? (
        <ul className="flex flex-col gap-1 pl-4 text-xs text-muted-foreground">
          {question.options.map((option, index) => (
            <li key={option.id}>
              {index + 1}. {option.optionText}
            </li>
          ))}
        </ul>
      ) : null}
    </li>
  );
}
