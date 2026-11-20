"use client";

import { useEffect, useMemo, useState } from "react";
import { z } from "zod";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter, useSearchParams } from "next/navigation";
import { UserRound, ClipboardList, BookOpen, CalendarDays, CreditCard, QrCode } from "lucide-react";
import { useOwnStudentProfile, useUpdateOwnStudentProfile, type StudentResponse } from "@/lib/api/students";
import type { StudentUpdateFormValues } from "@/lib/validation/students";
import { useT, type TranslateFn } from "@/i18n/i18n-provider";
import { localizeApiError } from "@/i18n/errors";
import { isApiClientError } from "@/lib/api/error";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { StudentStatusBadge } from "@/components/students/student-status-badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ProfileCompletionBanner } from "@/components/students/profile-completion-banner";
import { useOwnProfileFields } from "@/lib/api/student-profile-fields";
import { BillingAddressForm } from "./billing-address-form";
import { AdditionalDetailsSection } from "./additional-details-section";
import { PaymentHistoryPanel } from "@/components/students/payment-history-panel";
import { StudentAttendancePanel } from "@/components/students/student-attendance-panel";
import { StudentCourseCatalog } from "@/components/courses/student/student-course-catalog";
import { StudentAvatar } from "@/components/students/student-avatar";
import { ProfilePhotoEditor } from "@/components/students/profile-photo-editor";
import { useStudentPhoto } from "@/lib/api/student-management";

const TABS = [
  ["general", (t: TranslateFn) => t("student.profile.tab.general"), UserRound],
  ["exams", (t: TranslateFn) => t("student.profile.tab.exams"), ClipboardList],
  ["courses", (t: TranslateFn) => t("student.profile.tab.courses"), BookOpen],
  ["attendance", (t: TranslateFn) => t("student.profile.tab.attendance"), CalendarDays],
  ["payments", (t: TranslateFn) => t("student.profile.tab.payments"), CreditCard],
  ["qr", (t: TranslateFn) => t("student.profile.tab.qr"), QrCode],
] as const;

/** Same rules as `lib/validation/students.ts#studentUpdateSchema`, localized messages (UX only). */
function nameSchema(t: TranslateFn) {
  return z.object({
    name: z
      .string()
      .min(1, t("student.profile.validation.nameRequired"))
      .max(255, t("student.profile.validation.nameTooLong", { max: "255" })),
  });
}
type Tab = (typeof TABS)[number][0];

function validTab(value: string | null): value is Tab {
  return TABS.some(([tab]) => tab === value);
}

export default function StudentProfilePage() {
  const profileQuery = useOwnStudentProfile();
  const searchParams = useSearchParams();
  const router = useRouter();
  const t = useT();
  const tab = validTab(searchParams.get("tab")) ? searchParams.get("tab") as Tab : "general";

  const setTab = (next: Tab) => {
    const params = new URLSearchParams(searchParams.toString());
    if (next === "general") params.delete("tab"); else params.set("tab", next);
    const query = params.toString();
    router.replace(query ? `/student/profile?${query}` : "/student/profile", { scroll: false });
  };

  return <QueryStateBoundary query={profileQuery} loadingLabel={t("student.profile.loading")} permissionDenied={{ dashboardHref: "/student/dashboard" }} loginPath="/login">{(profile) => <StudentProfileWorkspace profile={profile} tab={tab} onTabChange={setTab} />}</QueryStateBoundary>;
}

function StudentProfileWorkspace({ profile, tab, onTabChange }: { profile: StudentResponse; tab: Tab; onTabChange: (tab: Tab) => void }) {
  const t = useT();
  return <div className="flex flex-col gap-6"><ProfileHeader profile={profile} tab={tab} onTabChange={onTabChange} />{tab === "general" ? <GeneralTab profile={profile} /> : tab === "payments" ? <PaymentHistoryPanel /> : tab === "courses" ? <StudentCourseCatalog /> : tab === "exams" ? <Placeholder title={t("student.profile.tab.exams")} description={t("student.profile.examsPlaceholder")} icon={<ClipboardList />} /> : tab === "attendance" ? <StudentAttendancePanel /> : <Placeholder title={t("student.profile.tab.qr")} description={t("student.profile.qrPlaceholder")} icon={<QrCode />} />}</div>;
}

function ProfileHeader({ profile, tab, onTabChange }: { profile: StudentResponse; tab: Tab; onTabChange: (tab: Tab) => void }) {
  const t = useT();
  return <section className="overflow-hidden rounded-xl border border-border bg-card shadow-sm"><div className="h-24 bg-gradient-to-r from-primary/80 to-primary/30" /><div className="flex flex-col gap-4 px-5 pb-5 sm:flex-row sm:items-end sm:gap-6"><OwnAvatar name={profile.name} /><div className="min-w-0 pb-1"><h1 className="text-2xl font-bold break-words text-foreground">{profile.name}</h1><p className="mt-1 text-sm break-all text-muted-foreground">{profile.email}</p></div><StudentStatusBadge status={profile.status} /></div><div role="tablist" aria-label={t("student.profile.sections")} className="flex flex-nowrap overflow-x-auto border-t border-border px-2">{TABS.map(([value, label, Icon]) => <button key={value} type="button" role="tab" aria-selected={tab === value} onClick={() => onTabChange(value)} className={`inline-flex shrink-0 items-center gap-2 border-b-2 px-3 py-3 text-sm font-medium ${tab === value ? "border-primary text-primary" : "border-transparent text-muted-foreground hover:text-foreground"}`}><Icon className="size-4" aria-hidden="true" />{label(t)}</button>)}</div></section>;
}

/** STU-MGMT-02: the student's own photo in the header slot, initials otherwise. */
function OwnAvatar({ name }: { name: string }) {
  const photoUrl = useStudentPhoto({ kind: "self" }).data?.url ?? null;
  return <StudentAvatar name={name} photoUrl={photoUrl} size="xl" className="-mt-12 size-24 border-4 border-card shadow-md" />;
}

function GeneralTab({ profile }: { profile: StudentResponse }) {
  const showBillingCard = useShowBillingCard();
  const t = useT();
  return <div className="flex flex-col gap-6"><ProfileCompletionBanner showLink={false} /><section className="rounded-xl border border-border bg-card p-5 shadow-sm"><h2 className="mb-4 text-lg font-semibold text-foreground">{t("student.profile.photo")}</h2><ProfilePhotoEditor owner={{ kind: "self" }} name={profile.name} /></section><ProfileForm profile={profile} /><AdditionalDetailsSection />{showBillingCard ? <BillingAddressForm /> : null}</div>;
}

function useShowBillingCard(): boolean {
  const fieldsQuery = useOwnProfileFields();
  if (fieldsQuery.status === "pending") return false;
  if (fieldsQuery.status === "error") return true;
  const keys = new Set(fieldsQuery.data.fields.map((field) => field.key));
  return !(keys.has("ADDRESS_LINE_1") && keys.has("CITY"));
}

function ProfileForm({ profile }: { profile: StudentResponse }) {
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const [saved, setSaved] = useState(false);
  const updateMutation = useUpdateOwnStudentProfile();
  const t = useT();
  const schema = useMemo(() => nameSchema(t), [t]);
  const { register, handleSubmit, setError, reset, formState: { errors, isDirty } } = useForm<StudentUpdateFormValues>({ resolver: zodResolver(schema), defaultValues: { name: profile.name } });
  useEffect(() => { reset({ name: profile.name }); }, [profile.name, reset]);
  const onSubmit = handleSubmit(async (values) => { setPageError(null); setSaved(false); try { await updateMutation.mutateAsync(values); setSaved(true); } catch (error) { if (isApiClientError(error)) { for (const field of error.fieldErrors) if (field.field === "name") setError("name", { type: "server", message: field.message }); setPageError(error.fieldErrors.length ? null : { message: localizeApiError(t, error), code: error.code }); } else setPageError({ message: t("student.profile.unexpectedError") }); } });
  return <section className="rounded-xl border border-border bg-card p-5 shadow-sm"><div className="flex items-center justify-between gap-3 border-b border-border pb-4"><h2 className="text-lg font-semibold text-foreground">{t("student.profile.personalDetails")}</h2><Button type="submit" form="student-profile-form" disabled={updateMutation.isPending || !isDirty}>{updateMutation.isPending ? t("student.common.saving") : t("student.common.saveChanges")}</Button></div><dl className="mt-5 grid grid-cols-1 gap-5 text-sm sm:grid-cols-2 lg:grid-cols-4"><div><dt className="text-xs font-semibold uppercase text-muted-foreground">{t("student.profile.field.name")}</dt><dd className="mt-1 text-foreground">{profile.name}</dd></div><div><dt className="text-xs font-semibold uppercase text-muted-foreground">{t("student.profile.field.email")}</dt><dd className="mt-1 break-all text-foreground">{profile.email}</dd></div><div><dt className="text-xs font-semibold uppercase text-muted-foreground">{t("student.profile.field.role")}</dt><dd className="mt-1 text-foreground">{profile.roleCode}</dd></div><div><dt className="text-xs font-semibold uppercase text-muted-foreground">{t("student.profile.field.status")}</dt><dd className="mt-1 text-foreground">{profile.status}</dd></div></dl><form id="student-profile-form" className="mt-6 max-w-xl" onSubmit={onSubmit} noValidate aria-busy={updateMutation.isPending}><Label htmlFor="profile-name">{t("student.profile.displayName")}</Label><Input id="profile-name" className="mt-1.5" {...register("name")} aria-invalid={Boolean(errors.name)} aria-describedby={errors.name ? "profile-name-error" : undefined} />{errors.name ? <p id="profile-name-error" role="alert" className="mt-1 text-xs text-destructive">{errors.name.message}</p> : null}{pageError ? <p role="alert" className="mt-3 text-sm text-destructive">{pageError.message}</p> : null}{saved ? <p role="status" className="mt-3 text-sm text-muted-foreground">{t("student.profile.saved")}</p> : null}<span role="status" className="sr-only">{updateMutation.isPending ? t("student.common.saving") : null}</span></form></section>;
}

function Placeholder({ title, description, icon }: { title: string; description: string; icon: React.ReactNode }) {
  return <section className="flex min-h-48 flex-col items-center justify-center gap-3 rounded-xl border border-border bg-card p-6 text-center shadow-sm"><span className="text-primary">{icon}</span><h2 className="text-lg font-semibold text-foreground">{title}</h2><p className="text-sm text-muted-foreground">{description}</p></section>;
}
