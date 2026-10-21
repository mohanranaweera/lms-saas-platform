"use client";

import { useMemo, useRef, useState, type ReactNode } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useIsMutating } from "@tanstack/react-query";
import { AlertCircle, CheckCircle2, IdCard } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { Textarea } from "@/components/ui/textarea";
import { isApiClientError } from "@/lib/api/error";
import { formatCurrency } from "@/lib/api/klass-student";
import {
  ISSUE_FREE_CARD_KEY,
  freeCardErrorMessage,
  formatYearMonth,
  monthsBetween,
  useIssueFreeCard,
  type FreeCard,
  type IssueFreeCardRequest,
} from "@/lib/api/free-cards";
import { todayIn, type PaymentContext } from "@/lib/api/staff-payments";
import { randomUuid } from "@/lib/uuid";
import { FieldError, NATIVE_SELECT_CLASS, StaffDialog } from "./staff-dialog";
import { FreeCardStatusBadge } from "./staff-status-badges";
import { issueFreeCardSchema, type IssueFreeCardValues } from "./schemas";

const DATES_HINT = "Required. Free cards always have an end date.";

const SERVER_FIELD: Record<string, keyof IssueFreeCardValues> = {
  courseId: "courseId",
  validFrom: "validFrom",
  validTo: "validTo",
  cardType: "cardType",
  reason: "reason",
};

/**
 * "Issue Free Card" (Klass S3): complimentary MONTHLY access for the
 * student over a required date range. Not a payment - no order, ledger or
 * settlement row. FULL only (HALF shown disabled); no Temporary Credit
 * (its meaning isn't visible anywhere, spec F2). The covered months shown
 * before confirming are a client-side preview; the server decides coverage.
 */
export function IssueFreeCardDialog({
  open,
  onOpenChange,
  context,
  defaultCourseId,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  context: PaymentContext;
  defaultCourseId: string | null;
}) {
  const busy = useIsMutating({ mutationKey: ISSUE_FREE_CARD_KEY }) > 0;
  return (
    <StaffDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Issue Free Card"
      icon={<IdCard className="size-5" aria-hidden="true" />}
      busy={busy}
      testId="issue-free-card-dialog"
    >
      <IssueFreeCardBody context={context} defaultCourseId={defaultCourseId} onClose={() => onOpenChange(false)} />
    </StaffDialog>
  );
}

function IssueFreeCardBody({
  context,
  defaultCourseId,
  onClose,
}: {
  context: PaymentContext;
  defaultCourseId: string | null;
  onClose: () => void;
}) {
  const monthlyCourses = useMemo(
    () => context.courses.filter((course) => course.pricingModel === "MONTHLY"),
    [context.courses]
  );
  const today = useMemo(() => todayIn(context.timezone), [context.timezone]);
  const minFrom = `${today.slice(0, 7)}-01`;
  const schema = useMemo(() => issueFreeCardSchema({ minFrom, today }), [minFrom, today]);
  const {
    register,
    control,
    setError,
    handleSubmit,
    getValues,
    formState: { errors },
  } = useForm<IssueFreeCardValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      courseId:
        defaultCourseId && monthlyCourses.some((course) => course.courseId === defaultCourseId) ? defaultCourseId : "",
      validFrom: "",
      validTo: "",
      cardType: "FULL",
      reason: "",
    },
  });
  const validFrom = useWatch({ control, name: "validFrom" });

  const [phase, setPhase] = useState<"form" | "confirm" | "done">("form");
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [issued, setIssued] = useState<FreeCard | null>(null);
  const idempotency = useRef<{ key: string; usedFor: string | null }>({ key: randomUuid(), usedFor: null });
  const submitting = useRef(false);
  const issue = useIssueFreeCard();

  const review = handleSubmit(() => {
    setSubmitError(null);
    setPhase("confirm");
  });

  const confirm = async () => {
    if (submitting.current) return;
    submitting.current = true;
    setSubmitError(null);
    const values = getValues();
    const payload: Omit<IssueFreeCardRequest, "idempotencyKey"> = {
      studentId: context.student.studentId,
      courseId: values.courseId,
      cardType: "FULL",
      validFrom: values.validFrom,
      validTo: values.validTo,
      reason: values.reason.trim(),
    };
    // Same key for an unchanged retry; a new one once anything was edited after a failure.
    const signature = JSON.stringify(payload);
    const idem = idempotency.current;
    if (idem.usedFor !== null && idem.usedFor !== signature) idem.key = randomUuid();
    idem.usedFor = signature;
    try {
      const card = await issue.mutateAsync({ ...payload, idempotencyKey: idem.key });
      setIssued(card);
      setPhase("done");
    } catch (error) {
      const message = freeCardErrorMessage(error);
      setSubmitError(message);
      let mapped = false;
      if (isApiClientError(error)) {
        if (error.code === "VALIDITY_INVALID") {
          setError("validFrom", { type: "server", message });
          mapped = true;
        }
        for (const fieldError of error.fieldErrors) {
          const field = SERVER_FIELD[fieldError.field];
          if (field) {
            setError(field, { type: "server", message: fieldError.message });
            mapped = true;
          }
        }
      }
      if (mapped) setPhase("form");
    } finally {
      submitting.current = false;
    }
  };

  if (phase === "done" && issued) {
    return (
      <div className="flex flex-col gap-4 p-4" data-testid="issue-free-card-success">
        <LiveRegion message="Free card issued." />
        <Alert>
          <CheckCircle2 aria-hidden="true" className="text-green-700" />
          <AlertDescription>
            {issued.replay ? "This free card was already issued - showing the saved card." : "Free card issued."}
          </AlertDescription>
        </Alert>
        <dl className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <Item label="Course" value={issued.courseTitle ?? "—"} />
          <Item label="Status" value={<FreeCardStatusBadge status={issued.status} />} />
          <Item label="Validity" value={`${issued.validFrom} – ${issued.validTo}`} />
          <Item label="Months covered" value={issued.coveredMonths.map(formatYearMonth).join(", ")} />
        </dl>
        {issued.enrollmentCreated ? (
          <p className="text-sm text-foreground" data-testid="free-card-enrolled">
            Student was enrolled in this course.
          </p>
        ) : null}
        <div className="flex justify-end">
          <Button type="button" onClick={onClose} data-testid="issue-free-card-done">
            Done
          </Button>
        </div>
      </div>
    );
  }

  if (phase === "confirm") {
    const values = getValues();
    const course = monthlyCourses.find((entry) => entry.courseId === values.courseId);
    const months = monthsBetween(values.validFrom, values.validTo);
    return (
      <div className="flex flex-col gap-4 p-4" data-testid="issue-free-card-confirm">
        <p className="font-medium text-foreground">Confirm this free card</p>
        <dl className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <Item label="Student" value={context.student.name} />
          <Item label="Course" value={course?.title ?? "—"} />
          <Item label="Type" value="Full" />
          <Item label="Validity" value={`${values.validFrom} – ${values.validTo}`} />
          <Item label="Months covered" value={months.map(formatYearMonth).join(", ")} testId="free-card-confirm-months" />
          <Item label="Reason" value={values.reason.trim()} />
        </dl>
        <p className="text-xs text-muted-foreground">
          The student gets the class-fee lessons of these months without paying. No payment or revenue is recorded.
        </p>
        {submitError ? (
          <Alert variant="destructive" data-testid="issue-free-card-error">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{submitError}</AlertDescription>
          </Alert>
        ) : null}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button
            type="button"
            variant="outline"
            disabled={issue.isPending}
            onClick={() => {
              setPhase("form");
              setSubmitError(null);
            }}
          >
            Back
          </Button>
          <Button
            type="button"
            onClick={() => void confirm()}
            disabled={issue.isPending}
            aria-busy={issue.isPending}
            data-testid="issue-free-card-confirm-submit"
          >
            {issue.isPending ? "Issuing…" : "Confirm & issue free card"}
          </Button>
        </div>
      </div>
    );
  }

  return (
    <form
      className="flex flex-col gap-4 p-4"
      noValidate
      onSubmit={(event) => void review(event)}
      data-testid="issue-free-card-form"
    >
      <p className="text-sm text-muted-foreground">
        Student: <strong className="text-foreground">{context.student.name}</strong>
      </p>
      {monthlyCourses.length === 0 ? (
        <Alert>
          <AlertCircle aria-hidden="true" />
          <AlertDescription>Free cards can only be issued for monthly-priced courses, and this student has none.</AlertDescription>
        </Alert>
      ) : null}

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="free-card-course">Course *</Label>
        <select
          id="free-card-course"
          className={NATIVE_SELECT_CLASS}
          aria-invalid={!!errors.courseId || undefined}
          aria-describedby="free-card-course-error"
          data-testid="free-card-course"
          {...register("courseId")}
        >
          <option value="">Select a course…</option>
          {monthlyCourses.map((course) => (
            <option key={course.courseId} value={course.courseId}>
              {course.title}
              {course.monthlyAmount !== null ? ` - ${formatCurrency(course.monthlyAmount, course.currency)}` : ""}
            </option>
          ))}
        </select>
        <FieldError id="free-card-course-error" message={errors.courseId?.message} />
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="free-card-from">From *</Label>
          <Input
            id="free-card-from"
            type="date"
            min={minFrom}
            aria-invalid={!!errors.validFrom || undefined}
            aria-describedby="free-card-from-hint free-card-from-error"
            data-testid="free-card-from"
            {...register("validFrom")}
          />
          <p id="free-card-from-hint" className="text-xs text-muted-foreground">
            {DATES_HINT}
          </p>
          <FieldError id="free-card-from-error" message={errors.validFrom?.message} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="free-card-to">To *</Label>
          <Input
            id="free-card-to"
            type="date"
            min={validFrom && validFrom > today ? validFrom : today}
            aria-invalid={!!errors.validTo || undefined}
            aria-describedby="free-card-to-hint free-card-to-error"
            data-testid="free-card-to"
            {...register("validTo")}
          />
          <p id="free-card-to-hint" className="text-xs text-muted-foreground">
            {DATES_HINT}
          </p>
          <FieldError id="free-card-to-error" message={errors.validTo?.message} />
        </div>
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="free-card-type">Type *</Label>
        <select
          id="free-card-type"
          className={NATIVE_SELECT_CLASS}
          aria-invalid={!!errors.cardType || undefined}
          aria-describedby="free-card-type-error"
          data-testid="free-card-type"
          {...register("cardType")}
        >
          <option value="FULL">Full</option>
          <option value="HALF" disabled>
            Half (not supported yet)
          </option>
        </select>
        <FieldError id="free-card-type-error" message={errors.cardType?.message} />
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="free-card-reason">Reason *</Label>
        <Textarea
          id="free-card-reason"
          maxLength={1000}
          aria-invalid={!!errors.reason || undefined}
          aria-describedby="free-card-reason-error"
          data-testid="free-card-reason"
          {...register("reason")}
        />
        <FieldError id="free-card-reason-error" message={errors.reason?.message} />
      </div>

      {submitError ? (
        <Alert variant="destructive" data-testid="issue-free-card-error">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{submitError}</AlertDescription>
        </Alert>
      ) : null}

      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button type="button" variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button type="submit" disabled={monthlyCourses.length === 0} data-testid="issue-free-card-review">
          Issue Free Card
        </Button>
      </div>
    </form>
  );
}

function Item({ label, value, testId }: { label: string; value: ReactNode; testId?: string }) {
  return (
    <div className="flex flex-col">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="break-words text-foreground" data-testid={testId}>
        {value}
      </dd>
    </div>
  );
}
