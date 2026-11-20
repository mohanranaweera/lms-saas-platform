import { z } from "zod";
import { ApiClientError } from "@/lib/api/error";
import { isoToDatetimeLocal } from "@/lib/datetime";
import { marksNumber, type ModuleQuestionCount } from "@/lib/api/question-bank";
import type {
  ExamDetail,
  ExamType,
  ExamUpsertRequest,
  PaymentCriteria,
  PaymentCriteriaKind,
} from "@/lib/api/exam-config";

/**
 * Client-side mirror of the server's `ExamUpsertRequest` field rules per exam
 * type (`docs/api/exam-management-v2.md` Slice B). UX only — the backend
 * re-validates everything and is authoritative (incl. the drawn paper's real
 * total marks, which only the server knows once it draws the questions).
 */

export const NAME_MAX = 255;
export const PASSWORD_MIN = 4;
export const PASSWORD_MAX = 64;
export const DURATION_MAX = 1440;
export const TOTAL_MARKS_MAX = 10000;
export const DOCUMENT_MAX_BYTES = 20 * 1024 * 1024;
export const DOCUMENT_TYPES = ["application/pdf", "image/png", "image/jpeg"];
export const DOCUMENT_ACCEPT = ".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg";

export interface ModuleQuotaFormValue {
  moduleId: string;
  /** Number of questions drawn from this module (string while editing). */
  count: string;
}

export interface AttachedDocument {
  mediaId: string;
  fileName: string;
}

export interface ExamFormValues {
  type: ExamType;
  name: string;
  password: string;
  durationMinutes: string;
  fromDate: string;
  toDate: string;
  fromTime: string;
  toTime: string;
  questionCount: string;
  cutoffMarks: string;
  courseId: string;
  modules: ModuleQuotaFormValue[];
  shuffleQuestions: boolean;
  shuffleAnswers: boolean;
  enrollAllStudents: boolean;
  autoEnrollOnPayment: boolean;
  criteriaMonths: string[];
  criteriaModuleIds: string[];
  criteriaDates: string[];
  criteriaMinimumAmount: string;
  showAnswersWithResults: boolean;
  totalMarks: string;
  questionPaper: AttachedDocument | null;
  markingScheme: AttachedDocument | null;
}

export type ExamFormMode = "create" | "edit";

/** Everything outside the form values that the rules depend on. */
export interface ExamFormContext {
  mode: ExamFormMode;
  /** Edit: the exam already has a password, so a blank field keeps it. */
  hasPassword: boolean;
  /** Edit: structural fields are locked (first attempt exists). */
  locked: boolean;
  /** Edit + locked: the end may only be extended. */
  originalEndAt: string | null;
  /** question-counts for the selected course, by module id. */
  moduleCounts: Map<string, ModuleQuestionCount>;
  /** From `GET /exams/course-config/{courseId}`; `undefined` while unknown. */
  criteriaKind: PaymentCriteriaKind | null | undefined;
  /** What the chosen quotas are worth, or `null` when nothing is selected. */
  marksRange: MarksRange | null;
}

export interface MarksRange {
  min: number;
  max: number;
  /** `min` is a real lower bound (false when the server didn't send per-question marks). */
  minKnown: boolean;
}

export function emptyExamFormValues(type: ExamType = "MCQ"): ExamFormValues {
  return {
    type,
    name: "",
    password: "",
    durationMinutes: "",
    fromDate: "",
    toDate: "",
    fromTime: "",
    toTime: "",
    questionCount: "",
    cutoffMarks: "",
    courseId: "",
    modules: [],
    shuffleQuestions: false,
    shuffleAnswers: false,
    enrollAllStudents: false,
    autoEnrollOnPayment: false,
    criteriaMonths: [],
    criteriaModuleIds: [],
    criteriaDates: [],
    criteriaMinimumAmount: "",
    showAnswersWithResults: false,
    totalMarks: "",
    questionPaper: null,
    markingScheme: null,
  };
}

function splitIso(iso: string): { date: string; time: string } {
  const [date, time] = isoToDatetimeLocal(iso).split("T");
  return { date: date ?? "", time: time ?? "" };
}

/** Browser-local date + time → ISO instant (`src/lib/datetime.ts` convention). */
export function localDateTimeToIso(date: string, time: string): string | null {
  if (!date || !time) return null;
  const value = new Date(`${date}T${time}`);
  return Number.isNaN(value.getTime()) ? null : value.toISOString();
}

function numberText(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === "") return "";
  const parsed = Number(value);
  return Number.isFinite(parsed) ? String(parsed) : "";
}

/** Prefills the edit form. The password is never known here (D6) — it stays blank. */
export function examDetailToFormValues(detail: ExamDetail): ExamFormValues {
  const start = splitIso(detail.startAt);
  const end = splitIso(detail.endAt);
  const criteria = detail.paymentCriteria;
  return {
    ...emptyExamFormValues(detail.type),
    name: detail.name,
    durationMinutes: numberText(detail.durationMinutes),
    fromDate: start.date,
    fromTime: start.time,
    toDate: end.date,
    toTime: end.time,
    questionCount: numberText(detail.questionCount),
    cutoffMarks: numberText(detail.cutoffMarks),
    courseId: detail.courseId,
    modules: (detail.moduleQuotas ?? []).map((quota) => ({ moduleId: quota.moduleId, count: String(quota.questionCount) })),
    shuffleQuestions: detail.shuffleQuestions,
    shuffleAnswers: detail.shuffleAnswers,
    enrollAllStudents: detail.enrollAllStudents,
    autoEnrollOnPayment: detail.autoEnrollOnPayment,
    criteriaMonths: criteria?.months ?? [],
    criteriaModuleIds: criteria?.moduleIds ?? [],
    criteriaDates: criteria?.dates ?? [],
    criteriaMinimumAmount: numberText(criteria?.minimumAmount),
    showAnswersWithResults: detail.showAnswersWithResults,
    totalMarks: numberText(detail.totalMarks),
    questionPaper:
      detail.questionPaperMediaId && detail.questionPaper
        ? { mediaId: detail.questionPaperMediaId, fileName: detail.questionPaper.fileName }
        : null,
    markingScheme:
      detail.markingSchemeMediaId && detail.markingScheme
        ? { mediaId: detail.markingSchemeMediaId, fileName: detail.markingScheme.fileName }
        : null,
  };
}

const round2 = (value: number) => Math.round(value * 100) / 100;

export function formatMarkValue(value: number): string {
  const rounded = round2(value);
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(2).replace(/0$/, "");
}

function parseCount(value: string): number | null {
  const trimmed = value.trim();
  if (!/^\d+$/.test(trimmed)) return null;
  return Number(trimmed);
}

/**
 * What the chosen quotas are worth. A module whose whole bank is drawn
 * contributes its exact total; otherwise `quota × min/max question marks`.
 * Returns `null` when no question is selected yet — so the form never shows
 * "worth 0 marks" (the Klass demo defect).
 */
export function computeMarksRange(
  modules: ModuleQuotaFormValue[],
  counts: Map<string, ModuleQuestionCount>
): MarksRange | null {
  let min = 0;
  let max = 0;
  let minKnown = true;
  let any = false;
  for (const entry of modules) {
    const quota = parseCount(entry.count);
    if (!quota) continue;
    const info = counts.get(entry.moduleId);
    if (!info) return null;
    any = true;
    const total = marksNumber(info.totalMarks);
    if (quota >= info.questionCount) {
      min += total;
      max += total;
      continue;
    }
    const lo = info.minMarks === null || info.minMarks === undefined ? null : marksNumber(info.minMarks);
    const hi = info.maxMarks === null || info.maxMarks === undefined ? null : marksNumber(info.maxMarks);
    if (lo === null || hi === null) {
      // Older servers: no per-question marks — only the module total bounds it.
      minKnown = false;
      max += total;
      continue;
    }
    min += quota * lo;
    max += Math.min(quota * hi, total);
  }
  if (!any) return null;
  return { min: round2(min), max: round2(max), minKnown };
}

export function isExactRange(range: MarksRange): boolean {
  return range.minKnown && range.min === range.max;
}

/** Helper text under Cutoff Mark. */
export function marksRangeText(range: MarksRange | null): string {
  if (!range) return "Choose modules and how many questions to take from each to see what the paper is worth.";
  if (isExactRange(range)) return `The chosen questions are worth ${formatMarkValue(range.max)} marks.`;
  if (!range.minKnown) return `The chosen questions are worth up to ${formatMarkValue(range.max)} marks.`;
  return `The chosen questions are worth between ${formatMarkValue(range.min)} and ${formatMarkValue(range.max)} marks.`;
}

function cutoffTooHighMessage(range: MarksRange): string {
  return isExactRange(range)
    ? `The chosen questions are worth ${formatMarkValue(range.max)} marks; the cut-off can't be higher.`
    : `The chosen questions are worth at most ${formatMarkValue(range.max)} marks; the cut-off can't be higher.`;
}

const MARKS_PATTERN = /^\d+(\.\d{1,2})?$/;

type Issue = { path: (string | number)[]; message: string };

/** Pure rule check (unit-friendly); the Zod schema just forwards these issues. */
export function validateExamForm(values: ExamFormValues, ctx: ExamFormContext): Issue[] {
  const issues: Issue[] = [];
  const add = (path: (string | number)[], message: string) => issues.push({ path, message });
  const { type } = values;
  const physical = type === "PHYSICAL";

  const name = values.name.trim();
  if (!name) add(["name"], physical ? "Exam name is required." : "Name is required.");
  else if (name.length > NAME_MAX) add(["name"], `Name must be ${NAME_MAX} characters or fewer.`);

  if (!values.courseId) add(["courseId"], "Course is required.");

  if (!physical && !ctx.locked) {
    const password = values.password;
    const keep = ctx.mode === "edit" && ctx.hasPassword;
    if (!password && !keep) add(["password"], "Exam password is required.");
    else if (password && (password.length < PASSWORD_MIN || password.length > PASSWORD_MAX))
      add(["password"], `Password must be ${PASSWORD_MIN}–${PASSWORD_MAX} characters.`);
  }

  const duration = values.durationMinutes.trim();
  if (!duration) {
    if (!physical) add(["durationMinutes"], "Duration is required.");
  } else {
    const minutes = parseCount(duration);
    if (minutes === null || minutes < 1 || minutes > DURATION_MAX)
      add(["durationMinutes"], `Duration must be a whole number of minutes from 1 to ${DURATION_MAX}.`);
  }

  const fromLabel = physical ? "Scheduled date" : "From date";
  const toLabel = physical ? "Scheduled end date" : "To date";
  if (!values.fromDate) add(["fromDate"], `${fromLabel} is required.`);
  if (!values.toDate) add(["toDate"], `${toLabel} is required.`);
  if (!values.fromTime) add(["fromTime"], physical ? "Scheduled time is required." : "From time is required.");
  if (!values.toTime) add(["toTime"], physical ? "Scheduled end time is required." : "To time is required.");
  const startAt = localDateTimeToIso(values.fromDate, values.fromTime);
  const endAt = localDateTimeToIso(values.toDate, values.toTime);
  if (startAt && endAt && new Date(endAt) <= new Date(startAt)) add(["toTime"], "The exam must end after it starts.");
  if (ctx.locked && ctx.originalEndAt && endAt && new Date(endAt) < new Date(ctx.originalEndAt))
    add(["toTime"], "Students have already started, so the end can only be extended.");

  if (type === "MCQ") {
    const questionCount = parseCount(values.questionCount);
    if (!values.questionCount.trim()) add(["questionCount"], "Number of questions is required.");
    else if (questionCount === null || questionCount < 1)
      add(["questionCount"], "Number of questions must be a whole number of at least 1.");

    if (values.courseId && values.modules.length === 0) add(["modules"], "Select at least one module.");
    let sum = 0;
    let quotasValid = true;
    values.modules.forEach((entry, index) => {
      const quota = parseCount(entry.count);
      if (!entry.count.trim()) {
        quotasValid = false;
        add(["modules", index, "count"], "Enter how many questions to take from this module.");
        return;
      }
      if (quota === null) {
        quotasValid = false;
        add(["modules", index, "count"], "Use a whole number of 0 or more.");
        return;
      }
      const info = ctx.moduleCounts.get(entry.moduleId);
      if (info && quota > info.questionCount) {
        quotasValid = false;
        add(
          ["modules", index, "count"],
          info.questionCount === 1
            ? "Only 1 question is available in this module."
            : `Only ${info.questionCount} questions are available in this module.`
        );
      }
      sum += quota;
    });
    if (quotasValid && values.modules.length > 0 && questionCount !== null && questionCount >= 1 && sum !== questionCount) {
      add(
        ["modules"],
        `The module quotas add up to ${sum} ${sum === 1 ? "question" : "questions"}; they must equal the Number of Questions (${questionCount}).`
      );
    }

    const cutoff = values.cutoffMarks.trim();
    if (!cutoff) add(["cutoffMarks"], "Cutoff mark is required.");
    else if (!MARKS_PATTERN.test(cutoff)) add(["cutoffMarks"], "Cutoff mark must be 0 or more, with at most 2 decimals.");
    else if (ctx.marksRange && Number(cutoff) > ctx.marksRange.max) add(["cutoffMarks"], cutoffTooHighMessage(ctx.marksRange));

    if (values.enrollAllStudents && values.autoEnrollOnPayment)
      add(["autoEnrollOnPayment"], 'Turn off "Enroll all students" to enroll by payment instead.');
    if (values.autoEnrollOnPayment) {
      const kind = ctx.criteriaKind;
      if (kind === null) add(["autoEnrollOnPayment"], "Auto-enroll on payment isn't available for this course.");
      if (kind === "MONTHS" && values.criteriaMonths.length === 0) add(["criteriaMonths"], "Pick at least one month.");
      if (kind === "MODULES" && values.criteriaModuleIds.length === 0) add(["criteriaModuleIds"], "Pick at least one module.");
      if (kind === "DATES" && values.criteriaDates.length === 0) add(["criteriaDates"], "Pick at least one date.");
      if (kind === "INSTALLMENT") {
        const amount = values.criteriaMinimumAmount.trim();
        if (!amount) add(["criteriaMinimumAmount"], "Minimum amount is required.");
        else if (!MARKS_PATTERN.test(amount)) add(["criteriaMinimumAmount"], "Enter an amount of 0 or more, with at most 2 decimals.");
      }
    }
  }

  if (type === "WRITTEN") {
    const total = values.totalMarks.trim();
    if (!total) add(["totalMarks"], "Total marks are required.");
    else if (!MARKS_PATTERN.test(total) || Number(total) < 1 || Number(total) > TOTAL_MARKS_MAX)
      add(["totalMarks"], `Total marks must be between 1 and ${TOTAL_MARKS_MAX}.`);
    if (!values.questionPaper) add(["questionPaper"], "Attach the question paper.");
    const cutoff = values.cutoffMarks.trim();
    if (cutoff && MARKS_PATTERN.test(total) && MARKS_PATTERN.test(cutoff) && Number(cutoff) > Number(total))
      add(["totalMarks"], "Total marks can't be lower than the exam's cut-off mark.");
  }

  return issues;
}

/** Zod schema bound to the current context (rebuild when the context changes). */
export function buildExamFormSchema(ctx: ExamFormContext) {
  return z.custom<ExamFormValues>().superRefine((values, refinement) => {
    for (const issue of validateExamForm(values, ctx)) {
      refinement.addIssue({ code: "custom", path: issue.path, message: issue.message });
    }
  });
}

/** Client checks for an attached question paper / marking scheme (server re-sniffs). */
export function documentFileError(file: File): string | null {
  const lower = file.name.toLowerCase();
  const typeOk = DOCUMENT_TYPES.includes(file.type) || /\.(pdf|png|jpe?g)$/.test(lower);
  if (!typeOk) return "Attach a PDF, PNG or JPEG file.";
  if (file.size > DOCUMENT_MAX_BYTES) return "The file is larger than 20 MB.";
  return null;
}

function optionalNumber(value: string): number | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

function buildCriteria(values: ExamFormValues, kind: PaymentCriteriaKind | null | undefined): PaymentCriteria | null {
  if (!values.autoEnrollOnPayment || !kind) return null;
  switch (kind) {
    case "MONTHS":
      return { kind, months: [...values.criteriaMonths].sort() };
    case "MODULES":
      return { kind, moduleIds: values.criteriaModuleIds };
    case "DATES":
      return { kind, dates: [...values.criteriaDates].sort() };
    case "INSTALLMENT":
      return { kind, minimumAmount: optionalNumber(values.criteriaMinimumAmount) ?? 0 };
    case "FULL_FEE":
      return { kind };
  }
}

/**
 * Form values → `ExamUpsertRequest`. Fields that don't apply to the type are
 * `null`. When the exam is locked, locked fields are re-sent exactly as the
 * server returned them so only the editable ones can change.
 */
export function toExamUpsertRequest(
  values: ExamFormValues,
  ctx: Pick<ExamFormContext, "mode" | "locked" | "criteriaKind">,
  original?: ExamDetail | null
): ExamUpsertRequest {
  const type = values.type;
  const mcq = type === "MCQ";
  const written = type === "WRITTEN";
  const physical = type === "PHYSICAL";
  const lockedOriginal = ctx.locked && original ? original : null;

  const request: ExamUpsertRequest = {
    type,
    name: values.name.trim(),
    courseId: values.courseId,
    password: physical || !values.password ? null : values.password,
    durationMinutes: optionalNumber(values.durationMinutes),
    startAt: localDateTimeToIso(values.fromDate, values.fromTime) ?? "",
    endAt: localDateTimeToIso(values.toDate, values.toTime) ?? "",
    questionCount: mcq ? optionalNumber(values.questionCount) : null,
    cutoffMarks: mcq || written ? optionalNumber(values.cutoffMarks) : null,
    moduleQuotas: mcq
      ? values.modules.map((entry) => ({ moduleId: entry.moduleId, questionCount: Number(entry.count) || 0 }))
      : null,
    shuffleQuestions: mcq ? values.shuffleQuestions : false,
    shuffleAnswers: mcq ? values.shuffleAnswers : false,
    enrollAllStudents: physical && ctx.mode === "create" ? true : values.enrollAllStudents,
    autoEnrollOnPayment: mcq ? values.autoEnrollOnPayment && !values.enrollAllStudents : false,
    paymentCriteria: mcq && !values.enrollAllStudents ? buildCriteria(values, ctx.criteriaKind) : null,
    showAnswersWithResults: physical ? false : values.showAnswersWithResults,
    totalMarks: written ? optionalNumber(values.totalMarks) : null,
    questionPaperMediaId: written ? (values.questionPaper?.mediaId ?? null) : null,
    markingSchemeMediaId: written ? (values.markingScheme?.mediaId ?? null) : null,
  };

  if (lockedOriginal) {
    request.type = lockedOriginal.type;
    request.courseId = lockedOriginal.courseId;
    request.password = null;
    request.durationMinutes = lockedOriginal.durationMinutes;
    request.startAt = lockedOriginal.startAt;
    request.shuffleQuestions = lockedOriginal.shuffleQuestions;
    request.shuffleAnswers = lockedOriginal.shuffleAnswers;
    if (mcq) {
      request.questionCount = lockedOriginal.questionCount;
      request.moduleQuotas = (lockedOriginal.moduleQuotas ?? []).map((quota) => ({
        moduleId: quota.moduleId,
        questionCount: quota.questionCount,
      }));
    }
    if (written) {
      request.totalMarks = lockedOriginal.totalMarks === null ? null : Number(lockedOriginal.totalMarks);
      request.questionPaperMediaId = lockedOriginal.questionPaperMediaId;
      request.markingSchemeMediaId = lockedOriginal.markingSchemeMediaId;
    }
  }
  return request;
}

/** Form paths a server field error can land on. */
export type ExamFormField =
  | "name"
  | "password"
  | "durationMinutes"
  | "fromDate"
  | "toDate"
  | "fromTime"
  | "toTime"
  | "questionCount"
  | "cutoffMarks"
  | "courseId"
  | "modules"
  | `modules.${number}.count`
  | "autoEnrollOnPayment"
  | "criteriaMonths"
  | "criteriaModuleIds"
  | "criteriaDates"
  | "criteriaMinimumAmount"
  | "totalMarks"
  | "questionPaper"
  | "markingScheme";

const DIRECT_FIELDS: Record<string, ExamFormField> = {
  name: "name",
  password: "password",
  durationMinutes: "durationMinutes",
  startAt: "fromDate",
  endAt: "toDate",
  questionCount: "questionCount",
  cutoffMarks: "cutoffMarks",
  courseId: "courseId",
  moduleQuotas: "modules",
  enrollAllStudents: "autoEnrollOnPayment",
  autoEnrollOnPayment: "autoEnrollOnPayment",
  totalMarks: "totalMarks",
  questionPaperMediaId: "questionPaper",
  markingSchemeMediaId: "markingScheme",
};

/** Maps one server field name (`moduleQuotas[1].questionCount`, `paymentCriteria.months` …) to a form path. */
export function examServerFieldToFormField(field: string, criteriaKind?: PaymentCriteriaKind | null): ExamFormField | null {
  const quota = /^moduleQuotas\[(\d+)\]/.exec(field);
  if (quota) return `modules.${Number(quota[1])}.count`;
  if (field.startsWith("paymentCriteria")) {
    if (field.includes("months")) return "criteriaMonths";
    if (field.includes("moduleIds")) return "criteriaModuleIds";
    if (field.includes("dates")) return "criteriaDates";
    if (field.includes("minimumAmount")) return "criteriaMinimumAmount";
    if (criteriaKind === "MONTHS") return "criteriaMonths";
    if (criteriaKind === "MODULES") return "criteriaModuleIds";
    if (criteriaKind === "DATES") return "criteriaDates";
    if (criteriaKind === "INSTALLMENT") return "criteriaMinimumAmount";
    return "autoEnrollOnPayment";
  }
  return DIRECT_FIELDS[field.replace(/\[.*$/, "").split(".")[0] ?? ""] ?? null;
}

export const EXAM_LOCKED_MESSAGE =
  "Students have already started this exam, so its questions, schedule start, duration, password and paper can't be changed. Reload to see the current settings.";

/**
 * Splits a failed save into per-field errors and a form-level message.
 * 400 field errors land on their inputs; a 409 `EXAM_LOCKED` becomes a
 * form-level message (plus any field errors the server named).
 */
export function mapExamSaveError(
  error: unknown,
  criteriaKind?: PaymentCriteriaKind | null
): { fields: { field: ExamFormField; message: string }[]; formMessage: string | null; locked: boolean } {
  if (!(error instanceof ApiClientError)) {
    return { fields: [], formMessage: "Something went wrong. Please try again.", locked: false };
  }
  const fields: { field: ExamFormField; message: string }[] = [];
  const unmapped: string[] = [];
  for (const fieldError of error.fieldErrors) {
    const target = examServerFieldToFormField(fieldError.field, criteriaKind);
    if (target) fields.push({ field: target, message: fieldError.message });
    else unmapped.push(fieldError.message);
  }
  if (error.status === 409 && error.code === "EXAM_LOCKED") {
    return { fields, formMessage: EXAM_LOCKED_MESSAGE, locked: true };
  }
  if (error.status === 403) return { fields, formMessage: "You don't have permission to do this.", locked: false };
  if (error.status === 400 && fields.length > 0) {
    return {
      fields,
      formMessage: unmapped.length > 0 ? unmapped.join(" ") : "Please fix the highlighted fields.",
      locked: false,
    };
  }
  return { fields, formMessage: [error.message, ...unmapped].filter(Boolean).join(" "), locked: false };
}
