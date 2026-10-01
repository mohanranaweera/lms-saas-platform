"use client";

import { useEffect, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, Pencil, X } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import {
  canManageAccessPolicy,
  canManageDevices,
  canViewAccessPolicy,
  canViewDevices,
} from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import { useDevicePolicy, useUpdateDevicePolicy, type DevicePolicyView } from "@/lib/api/devices";
import {
  useCourseAccessPolicies,
  useSaveCourseAccessPolicy,
  useTenantAccessPolicy,
  useUpdateTenantAccessPolicy,
  type CourseAccessPolicy,
  type TenantAccessPolicy,
} from "@/lib/api/access-policy";
import { useCourses } from "@/lib/api/courses";
import {
  courseAccessPolicySchema,
  devicePolicySchema,
  gracePeriodSchema,
  toOptionalNumber,
  type CourseAccessPolicyFormValues,
  type DevicePolicyFormValues,
  type GracePeriodFormValues,
} from "@/lib/validation/devices-access";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

/**
 * Tenant Admin → Devices & access (Wave 10). Three tenant-wide policies, each
 * owned and enforced by the backend:
 * - device policy (`DEVICES`): device limit, reset cooldown, student self-service;
 * - grace period after access expiry (`ACCESS_EXPIRY`);
 * - per-course overrides: grace period and a course device limit.
 * Read-only roles see the values without the controls; every save is still
 * re-checked server-side.
 */
export default function DevicesAccessSettingsPage() {
  const { session } = useAuth();
  const role = session?.role ?? null;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Devices &amp; access</h1>
        <p className="text-sm text-muted-foreground">
          How many devices students may use, and how long access continues after it expires.
        </p>
      </div>
      {canViewDevices(role) ? <DevicePolicySection canManage={canManageDevices(role)} /> : null}
      {canViewAccessPolicy(role) ? (
        <>
          <GracePeriodSection canManage={canManageAccessPolicy(role)} />
          <CourseOverridesSection
            canManage={canManageAccessPolicy(role)}
            canManageDevices={canManageDevices(role)}
          />
        </>
      ) : null}
    </div>
  );
}

function useNotice() {
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [notice]);
  return [notice, setNotice] as const;
}

function errorText(error: unknown): string {
  return isApiClientError(error)
    ? (error.fieldErrors[0]?.message ?? error.message)
    : "Something went wrong. Please try again.";
}

// ---------------------------------------------------------------------------
// Device policy
// ---------------------------------------------------------------------------

function DevicePolicySection({ canManage }: { canManage: boolean }) {
  const query = useDevicePolicy();
  return (
    <Card>
      <CardHeader>
        <CardTitle>Student devices</CardTitle>
        <CardDescription>
          Signing in on a new device beyond the limit is refused. A removed device keeps its slot for the
          cooldown, so devices can&apos;t be swapped to share an account.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <QueryStateBoundary query={query} loadingLabel="Loading device policy…" loginPath="/login">
          {(policy) => <DevicePolicyForm policy={policy} canManage={canManage} />}
        </QueryStateBoundary>
      </CardContent>
    </Card>
  );
}

function DevicePolicyForm({ policy, canManage }: { policy: DevicePolicyView; canManage: boolean }) {
  const mutation = useUpdateDevicePolicy();
  const [notice, setNotice] = useNotice();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<DevicePolicyFormValues>({
    resolver: zodResolver(devicePolicySchema),
    defaultValues: {
      maxDevices: policy.maxDevices?.toString() ?? "",
      resetCooldownHours: policy.resetCooldownHours.toString(),
      studentSelfServiceEnabled: policy.studentSelfServiceEnabled,
    },
  });

  const onSubmit = handleSubmit((values) =>
    mutation.mutate(
      {
        maxDevices: toOptionalNumber(values.maxDevices),
        resetCooldownHours: Number(values.resetCooldownHours),
        studentSelfServiceEnabled: values.studentSelfServiceEnabled,
      },
      { onSuccess: () => setNotice("Device policy saved.") }
    )
  );

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">
        Current limit: {policy.effectiveMaxDevices} {policy.effectiveMaxDevices === 1 ? "device" : "devices"} per
        student{policy.maxDevices === null ? " (plan default)" : ""}. Staff accounts are not limited.
        {policy.updatedAt ? ` Last changed ${formatDateTime(policy.updatedAt)}.` : ""}
      </p>
      {mutation.isError ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{errorText(mutation.error)}</AlertDescription>
        </Alert>
      ) : null}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="policy-max-devices">Devices per student</Label>
          <Input
            id="policy-max-devices"
            type="number"
            inputMode="numeric"
            min={1}
            max={20}
            placeholder={`Plan default (${policy.planDefaultLimit})`}
            disabled={!canManage}
            aria-invalid={errors.maxDevices ? true : undefined}
            aria-describedby={errors.maxDevices ? "policy-max-devices-error" : "policy-max-devices-hint"}
            {...register("maxDevices")}
          />
          <p id="policy-max-devices-hint" className="text-xs text-muted-foreground">
            Leave empty to use the plan default.
          </p>
          {errors.maxDevices ? (
            <p id="policy-max-devices-error" role="alert" className="text-xs text-destructive">
              {errors.maxDevices.message}
            </p>
          ) : null}
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="policy-cooldown">Reset cooldown (hours)</Label>
          <Input
            id="policy-cooldown"
            type="number"
            inputMode="numeric"
            min={0}
            max={720}
            disabled={!canManage}
            aria-invalid={errors.resetCooldownHours ? true : undefined}
            aria-describedby={errors.resetCooldownHours ? "policy-cooldown-error" : undefined}
            {...register("resetCooldownHours")}
          />
          {errors.resetCooldownHours ? (
            <p id="policy-cooldown-error" role="alert" className="text-xs text-destructive">
              {errors.resetCooldownHours.message}
            </p>
          ) : null}
        </div>
      </div>
      <label className="flex items-start gap-2 text-sm text-foreground">
        <input
          type="checkbox"
          className="mt-0.5 size-4"
          disabled={!canManage}
          {...register("studentSelfServiceEnabled")}
        />
        <span>
          Let students remove their own devices
          <span className="block text-xs text-muted-foreground">
            Otherwise only your institute can reset a student&apos;s devices.
          </span>
        </span>
      </label>
      <div role="status" aria-live="polite">
        {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      </div>
      {canManage ? (
        <Button type="submit" className="w-full sm:w-fit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
          {mutation.isPending ? "Saving…" : "Save device policy"}
        </Button>
      ) : null}
    </form>
  );
}

// ---------------------------------------------------------------------------
// Grace period
// ---------------------------------------------------------------------------

function GracePeriodSection({ canManage }: { canManage: boolean }) {
  const query = useTenantAccessPolicy();
  return (
    <Card>
      <CardHeader>
        <CardTitle>Grace period after expiry</CardTitle>
        <CardDescription>
          Students keep access for this many days after their access ends. A course can set its own value.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <QueryStateBoundary query={query} loadingLabel="Loading grace period…" loginPath="/login">
          {(policy) => <GracePeriodForm policy={policy} canManage={canManage} />}
        </QueryStateBoundary>
      </CardContent>
    </Card>
  );
}

function GracePeriodForm({ policy, canManage }: { policy: TenantAccessPolicy; canManage: boolean }) {
  const mutation = useUpdateTenantAccessPolicy();
  const [notice, setNotice] = useNotice();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<GracePeriodFormValues>({
    resolver: zodResolver(gracePeriodSchema),
    defaultValues: { gracePeriodDays: policy.gracePeriodDays.toString() },
  });

  const onSubmit = handleSubmit((values) =>
    mutation.mutate(
      { gracePeriodDays: Number(values.gracePeriodDays) },
      { onSuccess: () => setNotice("Grace period saved.") }
    )
  );

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
      {mutation.isError ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{errorText(mutation.error)}</AlertDescription>
        </Alert>
      ) : null}
      <div className="flex flex-col gap-1.5 sm:max-w-xs">
        <Label htmlFor="grace-days">Grace period (days)</Label>
        <Input
          id="grace-days"
          type="number"
          inputMode="numeric"
          min={0}
          max={90}
          disabled={!canManage}
          aria-invalid={errors.gracePeriodDays ? true : undefined}
          aria-describedby={errors.gracePeriodDays ? "grace-days-error" : "grace-days-hint"}
          {...register("gracePeriodDays")}
        />
        <p id="grace-days-hint" className="text-xs text-muted-foreground">
          0 means access ends exactly when it expires.
        </p>
        {errors.gracePeriodDays ? (
          <p id="grace-days-error" role="alert" className="text-xs text-destructive">
            {errors.gracePeriodDays.message}
          </p>
        ) : null}
      </div>
      <div role="status" aria-live="polite">
        {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      </div>
      {canManage ? (
        <Button type="submit" className="w-full sm:w-fit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
          {mutation.isPending ? "Saving…" : "Save grace period"}
        </Button>
      ) : null}
    </form>
  );
}

// ---------------------------------------------------------------------------
// Per-course overrides
// ---------------------------------------------------------------------------

function CourseOverridesSection({ canManage, canManageDevices }: { canManage: boolean; canManageDevices: boolean }) {
  const query = useCourseAccessPolicies();
  const [notice, setNotice] = useNotice();
  const [editing, setEditing] = useState<CourseAccessPolicy | null>(null);

  const columns: DataTableColumn<CourseAccessPolicy>[] = [
    { key: "course", header: "Course", cell: (row) => row.courseName ?? row.courseId, hideOnCard: true },
    {
      key: "grace",
      header: "Grace period",
      cell: (row) => (row.gracePeriodDays === null ? "Institute default" : `${row.gracePeriodDays} days`),
    },
    {
      key: "devices",
      header: "Device limit",
      cell: (row) => (row.maxDevices === null ? "Institute default" : `${row.maxDevices}`),
    },
    ...(canManage
      ? [
          {
            key: "actions",
            header: "Actions",
            hideOnCard: true,
            cell: (row: CourseAccessPolicy) => (
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setEditing(row)}
                aria-label={`Edit override for ${row.courseName ?? row.courseId}`}
              >
                <Pencil aria-hidden="true" />
                Edit
              </Button>
            ),
          },
        ]
      : []),
  ];

  return (
    <Card>
      <CardHeader>
        <CardTitle>Course overrides</CardTitle>
        <CardDescription>
          A course can have its own grace period and its own device limit (for example, one device only for a
          premium course). A student&apos;s personal device exception still takes priority.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div role="status" aria-live="polite">
          {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
        </div>
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading course overrides…"
          loginPath="/login"
          isEmpty={(data) => data.length === 0}
          emptyState={{
            title: "No course overrides",
            description: canManage
              ? "Every course follows the institute settings. Add an override below to change one course."
              : "Every course follows the institute settings.",
          }}
        >
          {(policies) => (
            <DataTable
              columns={columns}
              rows={policies}
              rowKey={(row) => row.courseId}
              caption="Course access overrides"
              cardHeading={(row) => row.courseName ?? row.courseId}
              cardFooter={
                canManage
                  ? (row) => (
                      <Button type="button" variant="outline" size="sm" onClick={() => setEditing(row)}>
                        <Pencil aria-hidden="true" />
                        Edit
                      </Button>
                    )
                  : undefined
              }
            />
          )}
        </QueryStateBoundary>
        {canManage ? (
          <CourseOverrideForm
            key={editing?.courseId ?? "new"}
            editing={editing}
            canManageDevices={canManageDevices}
            onDone={(message) => {
              setEditing(null);
              setNotice(message);
            }}
            onCancel={() => setEditing(null)}
          />
        ) : null}
      </CardContent>
    </Card>
  );
}

function CourseOverrideForm({
  editing,
  canManageDevices,
  onDone,
  onCancel,
}: {
  editing: CourseAccessPolicy | null;
  canManageDevices: boolean;
  onDone: (message: string) => void;
  onCancel: () => void;
}) {
  const courses = useCourses({ includeArchived: false, size: 100 });
  const mutation = useSaveCourseAccessPolicy();
  const {
    register,
    handleSubmit,
    setValue,
    control,
    formState: { errors },
  } = useForm<CourseAccessPolicyFormValues>({
    resolver: zodResolver(courseAccessPolicySchema),
    defaultValues: {
      courseId: editing?.courseId ?? "",
      gracePeriodDays: editing?.gracePeriodDays?.toString() ?? "",
      maxDevices: editing?.maxDevices?.toString() ?? "",
    },
  });
  const courseId = useWatch({ control, name: "courseId" });

  const onSubmit = handleSubmit((values) => {
    const gracePeriodDays = toOptionalNumber(values.gracePeriodDays);
    const maxDevices = toOptionalNumber(values.maxDevices);
    mutation.mutate(
      { courseId: values.courseId, gracePeriodDays, maxDevices },
      {
        onSuccess: () =>
          onDone(
            gracePeriodDays === null && maxDevices === null
              ? "The course override was removed."
              : "The course override was saved."
          ),
      }
    );
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-foreground">
          {editing ? `Edit override: ${editing.courseName ?? editing.courseId}` : "Add a course override"}
        </h3>
        {editing ? (
          <Button type="button" variant="ghost" size="sm" onClick={onCancel} aria-label="Cancel editing">
            <X aria-hidden="true" />
          </Button>
        ) : null}
      </div>
      <p className="text-xs text-muted-foreground">Leave both values empty to remove the override.</p>
      {mutation.isError ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{errorText(mutation.error)}</AlertDescription>
        </Alert>
      ) : null}
      <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="override-course">Course</Label>
          {editing ? (
            <Input id="override-course" value={editing.courseName ?? editing.courseId} disabled readOnly />
          ) : (
            <Select
              value={courseId}
              onValueChange={(value) => value && setValue("courseId", value, { shouldValidate: true })}
            >
              <SelectTrigger
                id="override-course"
                className="w-full"
                aria-invalid={errors.courseId ? true : undefined}
                aria-describedby={errors.courseId ? "override-course-error" : undefined}
              >
                <SelectValue placeholder={courses.isPending ? "Loading courses…" : "Select a course"} />
              </SelectTrigger>
              <SelectContent>
                {(courses.data?.content ?? []).map((course) => (
                  <SelectItem key={course.id} value={course.id}>
                    {course.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
          {courses.isError && !editing ? (
            <p role="alert" className="text-xs text-destructive">
              Courses could not be loaded.
            </p>
          ) : null}
          {errors.courseId ? (
            <p id="override-course-error" role="alert" className="text-xs text-destructive">
              {errors.courseId.message}
            </p>
          ) : null}
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="override-grace">Grace period (days)</Label>
          <Input
            id="override-grace"
            type="number"
            inputMode="numeric"
            min={0}
            max={90}
            placeholder="Institute default"
            aria-invalid={errors.gracePeriodDays ? true : undefined}
            aria-describedby={errors.gracePeriodDays ? "override-grace-error" : undefined}
            {...register("gracePeriodDays")}
          />
          {errors.gracePeriodDays ? (
            <p id="override-grace-error" role="alert" className="text-xs text-destructive">
              {errors.gracePeriodDays.message}
            </p>
          ) : null}
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="override-devices">Device limit</Label>
          <Input
            id="override-devices"
            type="number"
            inputMode="numeric"
            min={1}
            max={20}
            placeholder="Institute default"
            disabled={!canManageDevices}
            aria-invalid={errors.maxDevices ? true : undefined}
            aria-describedby={errors.maxDevices ? "override-devices-error" : undefined}
            {...register("maxDevices")}
          />
          {errors.maxDevices ? (
            <p id="override-devices-error" role="alert" className="text-xs text-destructive">
              {errors.maxDevices.message}
            </p>
          ) : null}
        </div>
      </div>
      <Button type="submit" className="w-full sm:w-fit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
        {mutation.isPending ? "Saving…" : "Save override"}
      </Button>
    </form>
  );
}
