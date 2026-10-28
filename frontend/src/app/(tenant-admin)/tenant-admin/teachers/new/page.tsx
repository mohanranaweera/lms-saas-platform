"use client";

import { useState, type ReactNode } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Eye, EyeOff, ImagePlus, UserRound } from "lucide-react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { teacherCreateSchema, type TeacherCreateFormValues } from "@/lib/validation/teacher";
import { useCreateTeacher } from "@/lib/api/teachers";
import { useUploadMediaFile } from "@/lib/api/media-files";
import { isApiClientError, type ApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { MediaDialog } from "@/components/media/media-dialog";
import { ErrorState } from "@/components/states/error-state";
import { PermissionDeniedState } from "@/components/states/permission-denied-state";

function Field({ label, error, children, full = false }: { label: string; error?: string; children: ReactNode; full?: boolean }) {
  return <div className={full ? "sm:col-span-2" : ""}><Label className="mb-1.5 block">{label}</Label>{children}{error ? <p role="alert" className="mt-1 text-xs text-destructive">{error}</p> : null}</div>;
}

export default function NewTeacherPage() {
  const router = useRouter();
  const create = useCreateTeacher();
  const upload = useUploadMediaFile();
  const [open, setOpen] = useState(true);
  const [showPassword, setShowPassword] = useState(false);
  const [preview, setPreview] = useState<string | null>(null);
  const [mediaFileId, setMediaFileId] = useState<string | undefined>();
  const [pageError, setPageError] = useState<{ message: string; code?: string; fieldErrors?: FieldError[] } | null>(null);
  const [permissionError, setPermissionError] = useState<ApiClientError | null>(null);
  const { register, handleSubmit, setError, formState: { errors } } = useForm<TeacherCreateFormValues>({
    resolver: zodResolver(teacherCreateSchema),
    defaultValues: { name: "", firstName: "", lastName: "", email: "", password: "", contactNumber: "", instituteCommissionPercent: 0, qualifications: "", description: "", publicProfileDisabled: false },
  });

  const submit = handleSubmit(async (values) => {
    setPageError(null); setPermissionError(null);
    try {
      const result = await create.mutateAsync({ ...values, name: `${values.firstName} ${values.lastName}`.trim(), profileMediaFileId: mediaFileId });
      router.push(`/tenant-admin/teachers/${result.id}?created=true`);
    } catch (error) {
      if (!isApiClientError(error)) { setPageError({ message: "Could not create teacher. Try again." }); return; }
      if (error.status === 403) { setPermissionError(error); return; }
      if (error.code === "CONFLICT") { setError("email", { type: "server", message: error.message }); return; }
      if (error.fieldErrors.length) {
        for (const field of error.fieldErrors) if (field.field in values) setError(field.field as keyof TeacherCreateFormValues, { type: "server", message: field.message });
      } else setPageError({ message: error.message, code: error.code });
    }
  });

  if (permissionError) return <PermissionDeniedState error={permissionError} dashboardHref="/tenant-admin/dashboard" />;
  return <div className="flex flex-col gap-4"><Link href="/tenant-admin/teachers" className="text-sm text-muted-foreground hover:underline">← Back to teachers</Link>
    <MediaDialog open={open} onOpenChange={(next) => { setOpen(next); if (!next) router.push("/tenant-admin/teachers"); }} title="Add New Teacher" description="Create a teacher account. It starts pending until approved." className="sm:max-w-2xl" footer={<><Button type="button" variant="outline" disabled={create.isPending || upload.isPending} onClick={() => setOpen(false)}>Cancel</Button><Button type="submit" form="teacher-create-form" disabled={create.isPending || upload.isPending}>{create.isPending ? "Creating…" : "Create Teacher"}</Button></>}>
      {pageError ? <ErrorState message={pageError.message} code={pageError.code} onRetry={() => setPageError(null)} /> : null}
      <form id="teacher-create-form" onSubmit={submit} noValidate aria-busy={create.isPending} className="flex flex-col gap-5">
        <div className="flex flex-col items-center gap-2"><div className="flex size-40 items-center justify-center overflow-hidden rounded-full bg-muted">{preview ? <img src={preview} alt="Profile preview" className="size-full object-cover" /> : <UserRound className="size-16 text-muted-foreground" aria-hidden="true" />}</div><label htmlFor="teacher-profile-image" className="inline-flex cursor-pointer items-center gap-1 rounded-full bg-primary px-4 py-2 text-sm font-medium text-primary-foreground"><ImagePlus className="size-4" aria-hidden="true" /> Change</label><Input id="teacher-profile-image" type="file" accept="image/png,image/jpeg,image/gif" className="sr-only" onChange={async (event) => { const file = event.target.files?.[0]; if (!file) return; setPreview(URL.createObjectURL(file)); try { const saved = await upload.mutateAsync({ file, title: "Teacher profile image" }); setMediaFileId(saved.id); } catch { setPreview(null); setMediaFileId(undefined); setPageError({ message: "Profile image upload failed. Try another image." }); } }} /></div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="First Name *" error={errors.firstName?.message}><Input {...register("firstName")} aria-invalid={Boolean(errors.firstName)} /></Field>
          <Field label="Last Name *" error={errors.lastName?.message}><Input {...register("lastName")} aria-invalid={Boolean(errors.lastName)} /></Field>
          <Field label="Email *" error={errors.email?.message}><Input type="email" {...register("email")} aria-invalid={Boolean(errors.email)} /></Field>
          <Field label="Password *" error={errors.password?.message}><div className="relative"><Input type={showPassword ? "text" : "password"} {...register("password")} aria-invalid={Boolean(errors.password)} className="pr-10" /><Button type="button" variant="ghost" size="icon-sm" className="absolute right-1 top-1" aria-label={showPassword ? "Hide password" : "Show password"} onClick={() => setShowPassword((value) => !value)}>{showPassword ? <EyeOff /> : <Eye />}</Button></div></Field>
          <Field label="Contact Number *" error={errors.contactNumber?.message}><Input {...register("contactNumber")} aria-invalid={Boolean(errors.contactNumber)} /></Field>
          <Field label="Institute Commission (%)" error={errors.instituteCommissionPercent?.message}><Input type="number" min="0" max="100" step="0.01" {...register("instituteCommissionPercent", { valueAsNumber: true })} aria-invalid={Boolean(errors.instituteCommissionPercent)} /></Field>
          <Field label="Qualifications" error={errors.qualifications?.message} full><Input {...register("qualifications")} aria-invalid={Boolean(errors.qualifications)} /></Field>
          <Field label="Description" error={errors.description?.message} full><textarea className="min-h-28 w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm" {...register("description")} aria-invalid={Boolean(errors.description)} /></Field>
          <label className="flex items-start gap-2 sm:col-span-2">
            <input type="checkbox" className="mt-1 size-4 rounded border-input" {...register("publicProfileDisabled")} />
            <span><span className="text-sm font-medium text-foreground">Disable Public Profile</span><span className="block text-xs text-muted-foreground">Hide this teacher from public teacher profile pages.</span></span>
          </label>
        </div>
      </form>
    </MediaDialog>
  </div>;
}
