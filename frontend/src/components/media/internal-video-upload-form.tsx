"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { isApiClientError } from "@/lib/api/error";
import { mediaKeys } from "@/lib/api/media";
import { useUploadVideo } from "@/lib/api/videos";
import { buildMediaUploadSchema } from "@/lib/validation/media";

// Existing /videos limit; the backend content-sniffs and enforces its own configured limit.
const schema = buildMediaUploadSchema(["video/mp4", "video/webm", "video/quicktime"], 25 * 1024 * 1024).pick({ file: true });

export function InternalVideoUploadForm({ file, onBusyChange, onReceived }: {
  file: File;
  onBusyChange: (busy: boolean) => void;
  onReceived: () => void;
}) {
  const upload = useUploadVideo();
  const queryClient = useQueryClient();
  const { handleSubmit, formState: { errors } } = useForm<{ file: File | null }>({
    resolver: zodResolver(schema),
    defaultValues: { file },
  });
  const submit = handleSubmit(async (values) => {
    if (!values.file || upload.isPending) return;
    onBusyChange(true);
    try {
      await upload.mutateAsync(values.file);
      void queryClient.invalidateQueries({ queryKey: mediaKeys.all });
      onReceived();
    } catch {
      // Keep the dialog open and render the mutation error below.
    } finally {
      onBusyChange(false);
    }
  });

  if (upload.isSuccess) {
    return <Alert role="status"><AlertDescription>Video uploaded to tenant internal storage.</AlertDescription></Alert>;
  }
  return (
    <form aria-label="Upload an internal video" onSubmit={submit} noValidate className="flex flex-col gap-3" aria-busy={upload.isPending}>
      <p>Destination: Tenant internal storage</p>
      <p className="text-sm text-muted-foreground">MP4, WebM or MOV, up to 25 MiB. The file name is used as the video title.</p>
      {errors.file ? <p role="alert" className="text-sm text-destructive">{errors.file.message}</p> : null}
      {upload.isError ? <Alert variant="destructive" role="alert"><AlertDescription>{isApiClientError(upload.error) ? upload.error.message : "Couldn't upload the video. Please try again."}</AlertDescription></Alert> : null}
      {upload.isPending ? <p role="status">Uploading video… Keep this dialog open.</p> : null}
      <Button type="submit" disabled={upload.isPending} aria-busy={upload.isPending}>{upload.isPending ? "Uploading…" : "Upload video"}</Button>
    </form>
  );
}
