"use client";

import { useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Controller, useForm, useWatch, type FieldErrors, type Path, type Resolver } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import {
  AlertCircle,
  BookMarked,
  CalendarClock,
  CalendarDays,
  Clock,
  FileCheck,
  FileQuestion,
  Layers,
  ListChecks,
  Lock,
  PenTool,
  RefreshCw,
  School,
  Scissors,
  TextCursorInput,
} from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { MultiSelect } from "@/components/ui/multi-select";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { Tabs } from "@/components/ui/tabs";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { FormField, describedBy, fieldIds } from "@/components/courses/admin/form-field";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { ErrorState } from "@/components/states/error-state";
import { PermissionDeniedState } from "@/components/states/permission-denied-state";
import {
  CheckboxField,
  DocumentAttachField,
  EXAM_FORM_PREFIX,
  IconInputField,
  IconSelectField,
  ValueListPicker,
} from "@/components/exams/config/exam-form-controls";
import { useCourses } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import { marksNumber, useModuleQuestionCounts, type ModuleQuestionCount } from "@/lib/api/question-bank";
import {
  useCreateExam,
  useExamCourseConfig,
  useExamDetail,
  useRedrawExamPaper,
  useUpdateExam,
  type ExamDetail,
  type ExamType,
  type PaymentCriteriaKind,
} from "@/lib/api/exam-config";
import {
  buildExamFormSchema,
  computeMarksRange,
  emptyExamFormValues,
  examDetailToFormValues,
  formatMarkValue,
  mapExamSaveError,
  marksRangeText,
  toExamUpsertRequest,
  validateExamForm,
  type ExamFormContext,
  type ExamFormMode,
  type ExamFormValues,
  type MarksRange,
} from "@/lib/validation/exam-config";

const FORM_ID = "exam-form";

const TYPE_TABS: { value: ExamType; label: string; icon: typeof ListChecks }[] = [
  { value: "MCQ", label: "MCQ", icon: ListChecks },
  { value: "WRITTEN", label: "Written", icon: PenTool },
  { value: "PHYSICAL", label: "Physical", icon: School },
];

const PRICING_LABELS: Record<string, string> = {
  FREE: "free",
  CUSTOM: "custom-priced",
  ONE_TIME: "one-time fee",
  MONTHLY: "monthly",
  SESSION: "per-session",
  DAILY: "daily",
  MODULE: "per-module",
  INSTALLMENT: "installment",
};

const CRITERIA_TEXT: Record<PaymentCriteriaKind, string> = {
  MONTHS: "Students whose class-fee payment covers any of these months are enrolled automatically until the exam ends.",
  MODULES: "Students who have paid for any of these modules are enrolled automatically until the exam ends.",
  DATES: "Students who have paid for any of these class dates are enrolled automatically until the exam ends.",
  INSTALLMENT: "Students whose confirmed installment payments reach this amount are enrolled automatically until the exam ends.",
  FULL_FEE: "Students who have paid the full course fee are enrolled automatically until the exam ends.",
};

const LOCKED_NOTE =
  "Students have already started this exam. You can still change the name, extend the end, the cut-off mark, enrollment and result settings; everything else is locked.";

function formatMonth(value: string): string {
  const [year, month] = value.split("-").map(Number);
  if (!year || !month) return value;
  return new Intl.DateTimeFormat("en-GB", { month: "long", year: "numeric" }).format(new Date(year, month - 1, 1));
}

function formatDay(value: string): string {
  const [year, month, day] = value.split("-").map(Number);
  if (!year || !month || !day) return value;
  return new Intl.DateTimeFormat("en-GB", { day: "2-digit", month: "short", year: "numeric" }).format(
    new Date(year, month - 1, day)
  );
}

function errorText(errors: FieldErrors<ExamFormValues>, path: string): string | null {
  let node: unknown = errors;
  for (const part of path.split(".")) {
    if (node === null || typeof node !== "object") return null;
    node = (node as Record<string, unknown>)[part];
  }
  if (!node || typeof node !== "object") return null;
  const entry = node as { message?: unknown; root?: { message?: unknown } };
  if (typeof entry.message === "string" && entry.message) return entry.message;
  if (typeof entry.root?.message === "string" && entry.root.message) return entry.root.message;
  return null;
}

/**
 * Shared "Add New Exam" / "Edit Exam" dialog (Klass tabs MCQ | WRITTEN |
 * PHYSICAL). On edit the exam is loaded from `GET /exams/{id}`, the tab is
 * fixed to its type and the password field stays blank (D6). Mount with a
 * fresh `key` per open.
 */
export function ExamFormDialog({
  open,
  examId,
  onOpenChange,
  onSaved,
}: {
  open: boolean;
  examId: string | null;
  onOpenChange: (open: boolean) => void;
  onSaved: (exam: ExamDetail, mode: ExamFormMode) => void;
}) {
  const detailQuery = useExamDetail(examId ?? "", { enabled: open && examId !== null });

  if (examId === null) {
    return <ExamFormBody open={open} onOpenChange={onOpenChange} onSaved={onSaved} detail={null} onReload={null} />;
  }
  if (detailQuery.status !== "success") {
    const deniedError =
      isApiClientError(detailQuery.error) && detailQuery.error.status === 403 ? detailQuery.error : null;
    return (
      <AdminFormDialog
        open={open}
        onOpenChange={onOpenChange}
        title="Edit Exam"
        testId="exam-form-dialog"
        footer={
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
        }
      >
        {detailQuery.status === "pending" ? (
          <div role="status" aria-busy="true" className="flex flex-col gap-4" data-testid="exam-form-loading">
            <span className="sr-only">Loading exam…</span>
            <Skeleton className="h-8 w-56" />
            <Skeleton className="h-9 w-full" />
            <div className="grid gap-4 sm:grid-cols-2">
              <Skeleton className="h-9" />
              <Skeleton className="h-9" />
            </div>
            <Skeleton className="h-9 w-full" />
          </div>
        ) : deniedError ? (
          <PermissionDeniedState error={deniedError} />
        ) : (
          <ErrorState
            message={mutationErrorMessage(detailQuery.error)}
            onRetry={() => void detailQuery.refetch()}
          />
        )}
      </AdminFormDialog>
    );
  }
  return (
    <ExamFormBody
      open={open}
      onOpenChange={onOpenChange}
      onSaved={onSaved}
      detail={detailQuery.data}
      onReload={async () => {
        const result = await detailQuery.refetch();
        return result.data ?? null;
      }}
    />
  );
}

function ExamFormBody({
  open,
  onOpenChange,
  onSaved,
  detail,
  onReload,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSaved: (exam: ExamDetail, mode: ExamFormMode) => void;
  detail: ExamDetail | null;
  onReload: (() => Promise<ExamDetail | null>) | null;
}) {
  const mode: ExamFormMode = detail ? "edit" : "create";
  const [current, setCurrent] = useState<ExamDetail | null>(detail);
  const [serverLocked, setServerLocked] = useState(false);
  const locked = Boolean(current?.locked) || serverLocked;
  const [formError, setFormError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(0);
  const [redrawMessage, setRedrawMessage] = useState<string | null>(null);

  const defaultValues = useMemo(() => (detail ? examDetailToFormValues(detail) : emptyExamFormValues("MCQ")), [detail]);
  const ctxRef = useRef<ExamFormContext | null>(null);
  const resolver: Resolver<ExamFormValues> = (values, context, options) =>
    zodResolver(buildExamFormSchema(ctxRef.current as ExamFormContext))(values, context, options) as ReturnType<
      Resolver<ExamFormValues>
    >;

  const {
    register,
    control,
    handleSubmit,
    setValue,
    setError,
    clearErrors,
    formState: { errors, isSubmitting },
  } = useForm<ExamFormValues>({ defaultValues, resolver, reValidateMode: "onChange" });
  const values = useWatch({ control }) as ExamFormValues;
  const type = values.type;
  const courseId = values.courseId;

  const coursesQuery = useCourses(undefined, { enabled: open });
  const countsQuery = useModuleQuestionCounts(courseId, { enabled: open && type === "MCQ" });
  const configQuery = useExamCourseConfig(courseId, { enabled: open && type === "MCQ" });
  const createExam = useCreateExam();
  const updateExam = useUpdateExam(current?.id ?? "");
  const redraw = useRedrawExamPaper(current?.id ?? "");

  const moduleCounts = useMemo(
    () => new Map<string, ModuleQuestionCount>((countsQuery.data ?? []).map((entry) => [entry.moduleId, entry])),
    [countsQuery.data]
  );
  const criteriaKind: PaymentCriteriaKind | null | undefined =
    configQuery.status === "success" ? configQuery.data.paymentCriteriaKind : undefined;

  // Paper already drawn and quotas unchanged → the frozen total is exact (D3).
  const savedQuotasUnchanged =
    current !== null &&
    current.type === "MCQ" &&
    current.totalMarks !== null &&
    String(current.questionCount ?? "") === values.questionCount.trim() &&
    (current.moduleQuotas ?? []).length === values.modules.length &&
    (current.moduleQuotas ?? []).every((quota, index) => {
      const entry = values.modules[index];
      return entry && entry.moduleId === quota.moduleId && Number(entry.count) === quota.questionCount;
    });
  const marksRange: MarksRange | null = savedQuotasUnchanged
    ? { min: marksNumber(current.totalMarks ?? 0), max: marksNumber(current.totalMarks ?? 0), minKnown: true }
    : computeMarksRange(values.modules, moduleCounts);

  const ctx: ExamFormContext = {
    mode,
    hasPassword: Boolean(current?.hasPassword),
    locked,
    originalEndAt: current?.endAt ?? null,
    moduleCounts,
    criteriaKind,
    marksRange,
  };
  // The resolver runs in event handlers (after commit), so syncing here is enough.
  useLayoutEffect(() => {
    ctxRef.current = ctx;
  });

  // Live hints for cross-field rules (quota sum, availability, cut-off) — shown as you type.
  const liveIssues = useMemo(() => {
    const map = new Map<string, string>();
    if (type !== "MCQ") return map;
    for (const issue of validateExamForm(values, ctx)) {
      const key = issue.path.join(".");
      if (key === "modules" || /^modules\.\d+\.count$/.test(key)) {
        const index = Number(issue.path[1]);
        if (key !== "modules" && !values.modules[index]?.count.trim()) continue;
        if (!map.has(key)) map.set(key, issue.message);
      }
      if (key === "cutoffMarks" && values.cutoffMarks.trim() && !map.has(key)) map.set(key, issue.message);
    }
    return map;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [values, marksRange, moduleCounts, type]);

  const fieldError = (path: string) => errorText(errors, path) ?? liveIssues.get(path) ?? null;

  const courses = coursesQuery.data?.content ?? [];
  const courseOptions =
    current && !courses.some((course) => course.id === current.courseId)
      ? [{ id: current.courseId, name: current.courseName }, ...courses]
      : courses;

  const pending = isSubmitting || createExam.isPending || updateExam.isPending;
  const busy = pending || uploading > 0;

  function switchType(next: ExamType) {
    if (mode === "edit" || next === type) return;
    clearErrors();
    setFormError(null);
    setValue("type", next);
  }

  function changeCourse(next: string) {
    setValue("courseId", next, { shouldValidate: Boolean(errors.courseId) });
    setValue("modules", []);
    setValue("criteriaModuleIds", []);
    setValue("autoEnrollOnPayment", false);
  }

  function changeModules(ids: string[]) {
    const existing = new Map(values.modules.map((entry) => [entry.moduleId, entry.count]));
    setValue(
      "modules",
      ids.map((id) => ({ moduleId: id, count: existing.get(id) ?? "" })),
      { shouldValidate: Boolean(errors.modules) }
    );
  }

  async function revertLockedFields() {
    const fresh = onReload ? await onReload() : null;
    const source = fresh ?? current;
    if (!source) return;
    setCurrent({ ...source, locked: true });
    const original = examDetailToFormValues(source);
    const keys: (keyof ExamFormValues)[] = [
      "courseId",
      "durationMinutes",
      "fromDate",
      "fromTime",
      "questionCount",
      "modules",
      "shuffleQuestions",
      "shuffleAnswers",
      "totalMarks",
      "questionPaper",
      "markingScheme",
    ];
    for (const key of keys) setValue(key, original[key] as never);
    setValue("password", "");
  }

  const onSubmit = handleSubmit(async (formValues) => {
    setFormError(null);
    setRedrawMessage(null);
    const body = toExamUpsertRequest(formValues, ctx, current);
    try {
      const saved = mode === "create" ? await createExam.mutateAsync(body) : await updateExam.mutateAsync(body);
      onSaved(saved, mode);
    } catch (error) {
      const mapped = mapExamSaveError(error, criteriaKind);
      for (const entry of mapped.fields) {
        setError(entry.field as Path<ExamFormValues>, { type: "server", message: entry.message });
      }
      setFormError(mapped.formMessage);
      if (mapped.locked) {
        setServerLocked(true);
        await revertLockedFields();
      }
    }
  });

  async function handleRedraw() {
    setRedrawMessage(null);
    setFormError(null);
    try {
      const exam = await redraw.mutateAsync();
      setCurrent(exam);
      const total = exam.totalMarks === null ? null : formatMarkValue(marksNumber(exam.totalMarks));
      const count = exam.paperQuestionCount ?? exam.questionCount ?? 0;
      setRedrawMessage(
        `A new set of ${count} ${count === 1 ? "question" : "questions"}${total !== null ? ` worth ${total} marks` : ""} was drawn.`
      );
    } catch (error) {
      const mapped = mapExamSaveError(error, criteriaKind);
      setFormError(mapped.formMessage);
      if (mapped.locked) {
        setServerLocked(true);
        await revertLockedFields();
      }
    }
  }

  const physical = type === "PHYSICAL";
  const written = type === "WRITTEN";
  const mcq = type === "MCQ";

  // ---- shared field blocks -------------------------------------------------------------
  const nameField = (
    <IconInputField
      name="name"
      label={physical ? "Exam Name" : "Name"}
      icon={TextCursorInput}
      required
      error={fieldError("name")}
      inputProps={{ ...register("name"), autoComplete: "off", maxLength: 255 }}
    />
  );

  const courseField = (
    <IconSelectField
      name="courseId"
      label="Course"
      icon={BookMarked}
      required
      error={fieldError("courseId")}
      helper={
        coursesQuery.status === "error"
          ? "Courses could not be loaded."
          : coursesQuery.status === "success" && courseOptions.length === 0
            ? "You don't have any courses yet."
            : locked
              ? "Locked after the first attempt."
              : null
      }
      selectProps={{
        value: courseId,
        onChange: (event) => changeCourse(event.target.value),
        disabled: locked || coursesQuery.status === "pending",
      }}
    >
      <option value="">{coursesQuery.status === "pending" ? "Loading courses…" : "Select a course"}</option>
      {courseOptions.map((course) => (
        <option key={course.id} value={course.id}>
          {course.name}
        </option>
      ))}
    </IconSelectField>
  );

  const passwordAndDuration = (
    <div className="grid gap-4 sm:grid-cols-2">
      <IconInputField
        name="password"
        label="Exam Password"
        icon={Lock}
        required={mode === "create" || !current?.hasPassword}
        error={fieldError("password")}
        helper={
          locked
            ? "Locked after the first attempt."
            : mode === "edit" && current?.hasPassword
              ? "Leave blank to keep the current password"
              : "Students need this password to start the exam (4–64 characters)."
        }
        inputProps={{
          ...register("password"),
          type: "password",
          autoComplete: "new-password",
          disabled: locked,
          placeholder: mode === "edit" && current?.hasPassword ? "••••••••" : undefined,
        }}
      />
      <IconInputField
        name="durationMinutes"
        label="Duration"
        icon={Clock}
        required
        error={fieldError("durationMinutes")}
        helper="Minutes"
        inputProps={{ ...register("durationMinutes"), type: "number", min: 1, max: 1440, step: 1, inputMode: "numeric", disabled: locked }}
      />
    </div>
  );

  const schedule = (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        <IconInputField
          name="fromDate"
          label={physical ? "Scheduled Date" : "From Date"}
          icon={CalendarDays}
          required
          error={fieldError("fromDate")}
          inputProps={{ ...register("fromDate"), type: "date", disabled: locked }}
        />
        {physical ? (
          <IconInputField
            name="fromTime"
            label="Scheduled Time"
            icon={CalendarClock}
            required
            error={fieldError("fromTime")}
            inputProps={{ ...register("fromTime"), type: "time", disabled: locked }}
          />
        ) : (
          <IconInputField
            name="toDate"
            label="To Date"
            icon={CalendarDays}
            required
            error={fieldError("toDate")}
            inputProps={{ ...register("toDate"), type: "date" }}
          />
        )}
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        {physical ? (
          <IconInputField
            name="toDate"
            label="Scheduled End Date"
            icon={CalendarDays}
            required
            error={fieldError("toDate")}
            inputProps={{ ...register("toDate"), type: "date" }}
          />
        ) : (
          <IconInputField
            name="fromTime"
            label="From Time"
            icon={CalendarClock}
            required
            error={fieldError("fromTime")}
            inputProps={{ ...register("fromTime"), type: "time", disabled: locked }}
          />
        )}
        <IconInputField
          name="toTime"
          label={physical ? "Scheduled End Time" : "To Time"}
          icon={CalendarClock}
          required
          error={fieldError("toTime")}
          helper={locked ? "The end can only be extended." : null}
          inputProps={{ ...register("toTime"), type: "time" }}
        />
      </div>
    </>
  );

  const enrollAllField = (
    <CheckboxField
      name="enrollAllStudents"
      label="Enroll all students in the course"
      description="Everyone enrolled in the course can sit the exam, including students who join the course later."
      inputProps={{
        ...register("enrollAllStudents", {
          onChange: (event: { target: { checked: boolean } }) => {
            if (event.target.checked) setValue("autoEnrollOnPayment", false);
          },
        }),
      }}
    />
  );

  const showAnswersField = (
    <CheckboxField
      name="showAnswersWithResults"
      label="Show correct answers with the exam results"
      description="Shown only after the exam has ended."
      inputProps={{ ...register("showAnswersWithResults") }}
    />
  );

  // ---- MCQ ---------------------------------------------------------------------------
  const countsList = countsQuery.data ?? [];
  const moduleOptions = countsList.map((entry) => ({
    value: entry.moduleId,
    label: `${entry.moduleName} (${entry.questionCount} ${entry.questionCount === 1 ? "question" : "questions"})`,
  }));
  const moduleIdsSelected = values.modules.map((entry) => entry.moduleId);
  const quotaSum = values.modules.reduce((sum, entry) => sum + (Number(entry.count) || 0), 0);
  const modulesIds = fieldIds(EXAM_FORM_PREFIX, "modules");
  const modulesError = fieldError("modules");
  let modulesHelper: string | null = null;
  if (!courseId) modulesHelper = "Select a course first.";
  else if (countsQuery.status === "pending") modulesHelper = "Loading modules…";
  else if (countsQuery.status === "error") modulesHelper = "Modules could not be loaded.";
  else if (countsList.length === 0) modulesHelper = "This course has no modules yet.";
  else if (locked) modulesHelper = "Locked after the first attempt.";

  const pricingLabel = configQuery.data ? (PRICING_LABELS[configQuery.data.pricingModel] ?? configQuery.data.pricingModel.toLowerCase()) : "";

  const mcqFields = (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        <IconInputField
          name="questionCount"
          label="Number of Questions"
          icon={FileQuestion}
          required
          error={fieldError("questionCount")}
          inputProps={{ ...register("questionCount"), type: "number", min: 1, step: 1, inputMode: "numeric", disabled: locked }}
        />
        <IconInputField
          name="cutoffMarks"
          label="Cutoff Mark"
          icon={Scissors}
          required
          error={fieldError("cutoffMarks")}
          helper={<span data-testid="exam-form-marks-helper">{marksRangeText(marksRange)}</span>}
          inputProps={{ ...register("cutoffMarks"), type: "number", min: 0, step: "0.01", inputMode: "decimal" }}
        />
      </div>
      {courseField}
      <FormField ids={modulesIds} label="Modules" required error={modulesError} helper={modulesHelper}>
        <MultiSelect
          id={modulesIds.inputId}
          options={moduleOptions}
          value={moduleIdsSelected}
          onChange={changeModules}
          placeholder="Select modules"
          searchPlaceholder="Filter modules…"
          emptyText="This course has no modules yet."
          invalid={Boolean(modulesError)}
          disabled={locked || !courseId || countsQuery.status !== "success"}
          testId="exam-form-modules"
          aria-describedby={describedBy(modulesIds, Boolean(modulesHelper), Boolean(modulesError))}
        />
      </FormField>
      {values.modules.length > 0 ? (
        <div className="flex flex-col gap-3 rounded-lg border border-border p-3" data-testid="exam-form-quotas">
          {values.modules.map((entry, index) => {
            const info = moduleCounts.get(entry.moduleId);
            const name = info?.moduleName ?? current?.moduleQuotas?.find((quota) => quota.moduleId === entry.moduleId)?.moduleName ?? "Module";
            const legendId = `${EXAM_FORM_PREFIX}-quota-${index}-module`;
            const ids = fieldIds(EXAM_FORM_PREFIX, `quota-${index}`);
            const quotaError = fieldError(`modules.${index}.count`);
            return (
              <div key={entry.moduleId} className="grid gap-1.5 sm:grid-cols-[1fr_12rem] sm:items-start sm:gap-3" data-testid="exam-form-quota">
                <div className="flex min-w-0 flex-col pt-1">
                  <span id={legendId} className="text-sm font-medium break-words text-foreground">
                    {name}
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {info ? `${info.questionCount} ${info.questionCount === 1 ? "question" : "questions"} available` : "—"}
                  </span>
                </div>
                <div className="flex flex-col gap-1">
                  <Label id={`${ids.inputId}-label`} htmlFor={ids.inputId} className="text-xs">
                    Number of questions from this module
                  </Label>
                  <Controller
                    control={control}
                    name={`modules.${index}.count`}
                    render={({ field }) => (
                      <Input
                        id={ids.inputId}
                        type="number"
                        min={0}
                        max={info?.questionCount}
                        step={1}
                        inputMode="numeric"
                        value={field.value}
                        onChange={(event) => field.onChange(event.target.value)}
                        onBlur={field.onBlur}
                        ref={field.ref}
                        disabled={locked}
                        aria-labelledby={`${legendId} ${ids.inputId}-label`}
                        aria-invalid={Boolean(quotaError)}
                        aria-describedby={quotaError ? ids.errorId : undefined}
                        className="h-9"
                        data-testid={`exam-form-quota-${index}`}
                      />
                    )}
                  />
                  {quotaError ? (
                    <p id={ids.errorId} role="alert" className="text-xs text-destructive">
                      {quotaError}
                    </p>
                  ) : null}
                </div>
              </div>
            );
          })}
          <p className="text-xs text-muted-foreground" role="status" data-testid="exam-form-quota-total">
            {quotaSum} of {values.questionCount.trim() || "?"} questions chosen
          </p>
        </div>
      ) : null}

      {current && !locked ? (
        <div className="flex flex-col gap-1.5 rounded-lg border border-dashed border-border p-3">
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant="outline"
              onClick={() => void handleRedraw()}
              disabled={busy || redraw.isPending}
              aria-busy={redraw.isPending}
              data-testid="exam-form-redraw"
            >
              <RefreshCw aria-hidden="true" className={redraw.isPending ? "animate-spin" : undefined} />
              {redraw.isPending ? "Redrawing…" : "Redraw questions"}
            </Button>
            <span className="text-xs text-muted-foreground">
              Draws a new random set from the saved module quotas. Allowed until a student starts.
            </span>
          </div>
          {redrawMessage ? (
            <p role="status" className="text-xs text-foreground" data-testid="exam-form-redraw-status">
              {redrawMessage}
            </p>
          ) : null}
        </div>
      ) : null}

      <fieldset className="m-0 flex flex-col gap-3 border-0 p-0" disabled={locked}>
        <legend className="sr-only">Shuffle options</legend>
        <CheckboxField name="shuffleQuestions" label="Shuffle questions" inputProps={{ ...register("shuffleQuestions") }} />
        <CheckboxField name="shuffleAnswers" label="Shuffle answers" inputProps={{ ...register("shuffleAnswers") }} />
      </fieldset>
      {enrollAllField}
      {courseId ? (
        <AutoEnrollSection
          checked={values.autoEnrollOnPayment}
          onCheckedChange={(next) => setValue("autoEnrollOnPayment", next, { shouldValidate: Boolean(errors.autoEnrollOnPayment) })}
          enrollAll={values.enrollAllStudents}
          configStatus={configQuery.status}
          criteriaKind={criteriaKind}
          pricingLabel={pricingLabel}
          error={fieldError("autoEnrollOnPayment")}
        >
          {values.autoEnrollOnPayment && criteriaKind ? (
            <div className="flex flex-col gap-3 rounded-lg bg-muted/40 p-3" data-testid="exam-form-criteria" data-kind={criteriaKind}>
              {criteriaKind === "MONTHS" ? (
                <Controller
                  control={control}
                  name="criteriaMonths"
                  render={({ field }) => (
                    <ValueListPicker
                      name="criteriaMonths"
                      label="Paid months"
                      inputType="month"
                      values={field.value}
                      onChange={field.onChange}
                      formatValue={formatMonth}
                      error={fieldError("criteriaMonths")}
                    />
                  )}
                />
              ) : null}
              {criteriaKind === "DATES" ? (
                <Controller
                  control={control}
                  name="criteriaDates"
                  render={({ field }) => (
                    <ValueListPicker
                      name="criteriaDates"
                      label="Paid class dates"
                      inputType="date"
                      values={field.value}
                      onChange={field.onChange}
                      formatValue={formatDay}
                      error={fieldError("criteriaDates")}
                    />
                  )}
                />
              ) : null}
              {criteriaKind === "MODULES" ? (
                <Controller
                  control={control}
                  name="criteriaModuleIds"
                  render={({ field }) => {
                    const ids = fieldIds(EXAM_FORM_PREFIX, "criteriaModuleIds");
                    const error = fieldError("criteriaModuleIds");
                    return (
                      <FormField ids={ids} label="Paid modules" required error={error}>
                        <MultiSelect
                          id={ids.inputId}
                          options={countsList.map((entry) => ({ value: entry.moduleId, label: entry.moduleName }))}
                          value={field.value}
                          onChange={field.onChange}
                          placeholder="Select modules"
                          invalid={Boolean(error)}
                          testId="exam-form-criteria-modules"
                          aria-describedby={describedBy(ids, false, Boolean(error))}
                        />
                      </FormField>
                    );
                  }}
                />
              ) : null}
              {criteriaKind === "INSTALLMENT" ? (
                <IconInputField
                  name="criteriaMinimumAmount"
                  label="Minimum amount paid"
                  icon={Layers}
                  required
                  error={fieldError("criteriaMinimumAmount")}
                  inputProps={{ ...register("criteriaMinimumAmount"), type: "number", min: 0, step: "0.01", inputMode: "decimal" }}
                />
              ) : null}
              <p className="text-xs text-muted-foreground" data-testid="exam-form-criteria-text">
                {CRITERIA_TEXT[criteriaKind]}
              </p>
            </div>
          ) : null}
        </AutoEnrollSection>
      ) : null}
      {showAnswersField}
    </>
  );

  // ---- WRITTEN -----------------------------------------------------------------------
  const writtenFields = (
    <>
      {courseField}
      <IconInputField
        name="totalMarks"
        label="Total Marks"
        icon={Scissors}
        required
        error={fieldError("totalMarks")}
        helper={locked ? "Locked after the first attempt." : null}
        inputProps={{ ...register("totalMarks"), type: "number", min: 1, max: 10000, step: "0.01", inputMode: "decimal", disabled: locked }}
      />
      <Controller
        control={control}
        name="questionPaper"
        render={({ field }) => (
          <DocumentAttachField
            name="questionPaper"
            label="Question Paper"
            icon={FileQuestion}
            purpose="QUESTION_PAPER"
            required
            value={field.value}
            onChange={(next) => {
              field.onChange(next);
              if (next) clearErrors("questionPaper");
            }}
            error={fieldError("questionPaper")}
            disabled={locked}
            onUploadingChange={(active) => setUploading((count) => Math.max(0, count + (active ? 1 : -1)))}
          />
        )}
      />
      <Controller
        control={control}
        name="markingScheme"
        render={({ field }) => (
          <DocumentAttachField
            name="markingScheme"
            label="Marking Scheme"
            icon={FileCheck}
            purpose="MARKING_SCHEME"
            value={field.value}
            onChange={field.onChange}
            error={fieldError("markingScheme")}
            disabled={locked}
            onUploadingChange={(active) => setUploading((count) => Math.max(0, count + (active ? 1 : -1)))}
          />
        )}
      />
      {enrollAllField}
      {showAnswersField}
    </>
  );

  const physicalDuration = (
    <div className="grid gap-4 sm:grid-cols-2">
      <IconInputField
        name="durationMinutes"
        label="Duration (minutes)"
        icon={Clock}
        error={fieldError("durationMinutes")}
        inputProps={{ ...register("durationMinutes"), type: "number", min: 1, max: 1440, step: 1, inputMode: "numeric", disabled: locked }}
      />
    </div>
  );

  const form = (
    <form id={FORM_ID} noValidate onSubmit={onSubmit} className="flex flex-col gap-4" aria-describedby={formError ? "exam-form-error" : undefined}>
      <fieldset disabled={pending} className="m-0 flex min-w-0 flex-col gap-4 border-0 p-0">
        <legend className="sr-only">{mode === "create" ? "New exam details" : "Exam details"}</legend>
        {physical ? (
          <>
            {courseField}
            {nameField}
            {schedule}
            {physicalDuration}
          </>
        ) : (
          <>
            {nameField}
            {passwordAndDuration}
            {schedule}
            {mcq ? mcqFields : null}
            {written ? writtenFields : null}
          </>
        )}
      </fieldset>
    </form>
  );

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && busy) return;
        onOpenChange(next);
      }}
      title={mode === "create" ? "Add New Exam" : "Edit Exam"}
      testId="exam-form-dialog"
      className="sm:max-w-2xl"
      footer={
        <>
          <Button
            type="button"
            variant="secondary"
            className="uppercase tracking-wide"
            onClick={() => onOpenChange(false)}
            disabled={busy}
            data-testid="exam-form-cancel"
          >
            Cancel
          </Button>
          <Button
            type="submit"
            form={FORM_ID}
            className="uppercase tracking-wide"
            disabled={busy}
            aria-busy={pending}
            data-testid="exam-form-submit"
          >
            {pending ? (mode === "create" ? "Creating…" : "Saving…") : mode === "create" ? "Create exam" : "Save changes"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {formError ? (
          <Alert variant="destructive" id="exam-form-error" data-testid="exam-form-error">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        ) : null}
        {locked ? (
          <Alert data-testid="exam-form-locked-note">
            <Lock aria-hidden="true" />
            <AlertDescription>{LOCKED_NOTE}</AlertDescription>
          </Alert>
        ) : null}
        <Tabs
          aria-label="Exam type"
          idPrefix="exam-form-"
          value={type}
          onValueChange={(next) => switchType(next as ExamType)}
          listClassName="[&>[role=tab]]:uppercase [&>[role=tab]]:tracking-wide"
          items={TYPE_TABS.map((entry) => {
            const Icon = entry.icon;
            return {
              value: entry.value,
              label: entry.label,
              icon: <Icon className="size-4" aria-hidden="true" />,
              disabled: mode === "edit" && entry.value !== type,
              testId: `exam-form-type-${entry.value}`,
              content: form,
            };
          })}
        />
      </div>
    </AdminFormDialog>
  );
}

function AutoEnrollSection({
  checked,
  onCheckedChange,
  enrollAll,
  configStatus,
  criteriaKind,
  pricingLabel,
  error,
  children,
}: {
  checked: boolean;
  onCheckedChange: (next: boolean) => void;
  enrollAll: boolean;
  configStatus: "pending" | "error" | "success";
  criteriaKind: PaymentCriteriaKind | null | undefined;
  pricingLabel: string;
  error: string | null;
  children: ReactNode;
}) {
  const id = `${EXAM_FORM_PREFIX}-autoEnrollOnPayment`;
  const unsupported = configStatus === "success" && criteriaKind === null;
  const disabled = enrollAll || configStatus !== "success" || unsupported;
  let note: string | null = null;
  if (enrollAll) note = 'Turn off "Enroll all students" to enroll by payment instead.';
  else if (configStatus === "pending") note = "Checking the course's payment type…";
  else if (configStatus === "error") note = "The course's payment type could not be loaded, so auto-enroll is unavailable right now.";
  else if (unsupported)
    note = `Auto-enroll on payment isn't available for ${pricingLabel ? `${pricingLabel} ` : ""}courses — there are no class-fee payments to match. Enroll students yourself or use "Enroll all students".`;

  return (
    <div className="flex flex-col gap-3" data-testid="exam-form-auto-enroll">
      <div className="flex items-start gap-3">
        <Switch
          id={id}
          checked={checked && !enrollAll}
          onCheckedChange={onCheckedChange}
          disabled={disabled}
          aria-describedby={`${id}-description${note ? ` ${id}-note` : ""}${error ? ` ${id}-error` : ""}`}
          className="mt-0.5"
          data-testid={id}
        />
        <div className="flex min-w-0 flex-col gap-0.5">
          <Label htmlFor={id} className="font-medium">
            Auto-enroll students on payment
          </Label>
          <p id={`${id}-description`} className="text-xs text-muted-foreground">
            Enroll students into this exam as their class-fee payment comes in, instead of one by one.
          </p>
          {note ? (
            <p id={`${id}-note`} className="text-xs text-foreground" data-testid="exam-form-auto-enroll-note">
              {note}
            </p>
          ) : null}
          {error ? (
            <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
              {error}
            </p>
          ) : null}
        </div>
      </div>
      {children}
    </div>
  );
}
