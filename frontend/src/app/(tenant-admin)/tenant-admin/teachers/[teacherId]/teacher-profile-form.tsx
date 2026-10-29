"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useUpdateTeacher, type Teacher } from "@/lib/api/teachers";
import { teacherUpdateSchema, type TeacherUpdateFormValues } from "@/lib/validation/teacher";
import { useUploadMediaFile } from "@/lib/api/media-files";
import { isApiClientError } from "@/lib/api/error";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

export function TeacherProfileForm({ teacher, onCancel, onSaved }: { teacher: Teacher; onCancel: () => void; onSaved: (teacher: Teacher) => void }) {
  const update = useUpdateTeacher(teacher.id);
  const upload = useUploadMediaFile();
  const [preview, setPreview] = useState<string | null>(teacher.profileImageUrl);
  const [imageFailed, setImageFailed] = useState(false);
  const [mediaFileId, setMediaFileId] = useState<string | undefined>(teacher.profileMediaFileId ?? undefined);
  const [serverError, setServerError] = useState<string | null>(null);
  const { register, handleSubmit, setError, formState: { errors } } = useForm<TeacherUpdateFormValues>({
    resolver: zodResolver(teacherUpdateSchema),
    defaultValues: {
      firstName: teacher.firstName ?? teacher.name,
      lastName: teacher.lastName ?? "",
      contactNumber: teacher.contactNumber ?? "",
      instituteCommissionPercent: teacher.instituteCommissionPercent ?? 0,
      qualifications: teacher.qualifications ?? "",
      description: teacher.description ?? "",
      publicProfileDisabled: teacher.publicProfileDisabled,
    },
  });

  async function onSubmit(values: TeacherUpdateFormValues) {
    setServerError(null);
    try {
      const saved = await update.mutateAsync({ ...values, profileMediaFileId: mediaFileId });
      onSaved(saved);
    } catch (error) {
      if (isApiClientError(error)) {
        for (const field of error.fieldErrors) {
          if (field.field in values) setError(field.field as keyof TeacherUpdateFormValues, { type: "server", message: field.message });
        }
        setServerError(error.fieldErrors.length ? null : error.message);
      } else {
        setServerError("Could not save teacher profile. Try again.");
      }
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-4" aria-busy={update.isPending}>
      <div className="flex items-center gap-4">
        {preview && !imageFailed ? <img src={preview} alt={`${teacher.name} profile preview`} className="size-20 rounded-full object-cover" onError={() => setImageFailed(true)} /> : <span className="flex size-20 items-center justify-center rounded-full bg-muted text-xs text-muted-foreground">No photo</span>}
        <div>
          <Label htmlFor="teacher-edit-image" className="cursor-pointer text-sm font-medium text-primary">Change image</Label>
          <Input id="teacher-edit-image" type="file" accept="image/png,image/jpeg,image/gif" className="sr-only" disabled={upload.isPending || update.isPending} onChange={async (event) => {
            const file = event.target.files?.[0]; if (!file) return;
            const local = URL.createObjectURL(file); setImageFailed(false); setPreview(local);
            try { const saved = await upload.mutateAsync({ file, title: "Teacher profile image", usage: "TEACHER_PROFILE" }); setMediaFileId(saved.id); }
            catch { setImageFailed(false); setPreview(teacher.profileImageUrl); setMediaFileId(teacher.profileMediaFileId ?? undefined); setServerError("Could not upload profile image."); }
          }} />
          {mediaFileId ? (
            <button type="button" className="mt-1 block text-sm font-medium text-destructive hover:underline disabled:opacity-50" disabled={upload.isPending || update.isPending} onClick={() => { setMediaFileId(undefined); setPreview(null); setImageFailed(false); }}>
              Remove photo
            </button>
          ) : null}
          {teacher.profileMediaFileId && !mediaFileId ? <p className="mt-1 text-xs text-muted-foreground">The photo is removed when you save.</p> : null}
        </div>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="First name" error={errors.firstName?.message}><Input {...register("firstName")} aria-invalid={Boolean(errors.firstName)} /></Field>
        <Field label="Last name" error={errors.lastName?.message}><Input {...register("lastName")} aria-invalid={Boolean(errors.lastName)} /></Field>
        <Field label="Contact number" error={errors.contactNumber?.message}><Input {...register("contactNumber")} aria-invalid={Boolean(errors.contactNumber)} /></Field>
        <Field label="Institute Commission (%)" error={errors.instituteCommissionPercent?.message}><Input type="number" step="0.01" min="0" max="100" {...register("instituteCommissionPercent", { valueAsNumber: true })} aria-invalid={Boolean(errors.instituteCommissionPercent)} /></Field>
        <Field label="Qualifications" error={errors.qualifications?.message}><Input {...register("qualifications")} aria-invalid={Boolean(errors.qualifications)} /></Field>
        <Field label="Description" error={errors.description?.message} full><textarea className="min-h-24 w-full rounded-lg border border-input bg-transparent px-3 py-2 text-sm" {...register("description")} aria-invalid={Boolean(errors.description)} /></Field>
        <label className="flex items-start gap-2 sm:col-span-2">
          <input type="checkbox" className="mt-1 size-4 rounded border-input" {...register("publicProfileDisabled")} />
          <span><span className="text-sm font-medium text-foreground">Disable Public Profile</span><span className="block text-xs text-muted-foreground">Hide this teacher from public teacher profile pages.</span></span>
        </label>
      </div>
      {serverError ? <p role="alert" className="text-sm text-destructive">{serverError}</p> : null}
      <div className="flex justify-end gap-2 border-t border-border pt-4">
        <Button type="button" variant="outline" onClick={onCancel} disabled={update.isPending || upload.isPending}>Cancel</Button>
        <Button type="submit" disabled={update.isPending || upload.isPending}>{update.isPending ? "Saving…" : "Save changes"}</Button>
      </div>
    </form>
  );
}

function Field({ label, error, full, children }: { label: string; error?: string; full?: boolean; children: React.ReactNode }) {
  return <div className={full ? "sm:col-span-2" : ""}><Label>{label}</Label>{children}{error ? <p role="alert" className="mt-1 text-xs text-destructive">{error}</p> : null}</div>;
}
