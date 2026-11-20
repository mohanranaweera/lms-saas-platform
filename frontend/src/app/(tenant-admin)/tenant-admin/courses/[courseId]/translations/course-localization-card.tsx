"use client";

import { useEffect, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { AlertCircle, CheckCircle2, Eye, Globe } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { isApiClientError } from "@/lib/api/error";
import {
  usePublishCourseLocalization,
  useSaveCourseLocalization,
  type CourseLocalization,
} from "@/lib/api/translations";
import { useT, type TranslateFn } from "@/i18n/i18n-provider";
import { localizeApiError } from "@/i18n/errors";
import { localeAutonym } from "@/i18n/locales";

const TITLE_MAX = 255;
const DESCRIPTION_MAX = 20000;

function variantSchema(t: TranslateFn) {
  return z.object({
    title: z
      .string()
      .trim()
      .min(1, t("i18nAdmin.course.validation.titleRequired"))
      .max(TITLE_MAX, t("i18nAdmin.course.validation.titleTooLong", { max: TITLE_MAX })),
    description: z.string().max(DESCRIPTION_MAX, t("i18nAdmin.course.validation.descriptionTooLong", { max: DESCRIPTION_MAX })),
  });
}
type VariantValues = { title: string; description: string };

/** One locale's title/description variant: draft editor, preview, publish/unpublish. Plain text only. */
export function CourseLocalizationCard({
  courseId,
  locale,
  variant,
}: {
  courseId: string;
  locale: string;
  variant: CourseLocalization | null;
}) {
  const t = useT();
  const save = useSaveCourseLocalization();
  const publish = usePublishCourseLocalization();
  const schema = useMemo(() => variantSchema(t), [t]);
  const [preview, setPreview] = useState(false);
  const [message, setMessage] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const defaults = useMemo(
    () => ({ title: variant?.title ?? "", description: variant?.description ?? "" }),
    [variant?.title, variant?.description]
  );
  const form = useForm<VariantValues>({ resolver: zodResolver(schema), defaultValues: defaults });
  const { reset } = form;
  useEffect(() => reset(defaults), [reset, defaults]);
  const values = form.watch();
  const errors = form.formState.errors;
  const pending = save.isPending || publish.isPending;
  const idBase = `course-variant-${locale}`;
  const language = localeAutonym(locale);

  function onError(error: unknown) {
    const stale = isApiClientError(error) && error.status === 409;
    setMessage({ kind: "error", text: stale ? t("i18nAdmin.course.stale") : localizeApiError(t, error) });
  }

  const onSubmit = form.handleSubmit(async (next) => {
    setMessage(null);
    try {
      await save.mutateAsync({
        courseId,
        locale,
        title: next.title.trim(),
        description: next.description,
        expectedVersion: variant?.version ?? null,
      });
      setMessage({ kind: "success", text: t("i18nAdmin.course.saved") });
    } catch (error) {
      onError(error);
    }
  });

  async function togglePublish(publishNow: boolean) {
    setMessage(null);
    try {
      if (!variant) return;
      await publish.mutateAsync({ courseId, locale, publish: publishNow, expectedVersion: variant.version });
      setMessage({ kind: "success", text: publishNow ? t("i18nAdmin.course.published") : t("i18nAdmin.course.unpublished") });
    } catch (error) {
      onError(error);
    }
  }

  return (
    <section
      aria-labelledby={`${idBase}-heading`}
      className="flex min-w-0 flex-col gap-3 rounded-lg border border-border p-4"
      data-testid="course-variant"
      data-locale={locale}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id={`${idBase}-heading`} className="flex items-center gap-2 text-sm font-medium text-foreground">
          <Globe className="size-4" aria-hidden="true" />
          <span lang={locale}>{language}</span>
          <span className="text-muted-foreground">({locale})</span>
        </h3>
        {variant ? (
          <span className="flex flex-wrap gap-1.5">
            <Badge variant={variant.status === "PUBLISHED" ? "default" : "secondary"}>
              {variant.status === "PUBLISHED" ? t("i18nAdmin.course.statusPublished") : t("i18nAdmin.course.statusDraft")}
            </Badge>
            {variant.status === "PUBLISHED" && variant.hasUnpublishedChanges ? (
              <Badge variant="warning">{t("i18nAdmin.course.unpublishedChanges")}</Badge>
            ) : null}
          </span>
        ) : (
          <Badge variant="outline">{t("i18nAdmin.course.statusNone")}</Badge>
        )}
      </div>

      <form onSubmit={onSubmit} noValidate className="flex min-w-0 flex-col gap-3">
        <div className="flex min-w-0 flex-col gap-1.5">
          <Label htmlFor={`${idBase}-title`}>{t("i18nAdmin.course.title")}</Label>
          <Input
            id={`${idBase}-title`}
            lang={locale}
            disabled={pending}
            aria-invalid={errors.title ? true : undefined}
            aria-describedby={errors.title ? `${idBase}-title-error` : undefined}
            {...form.register("title")}
          />
          {errors.title ? (
            <p id={`${idBase}-title-error`} role="alert" className="text-sm text-destructive">
              {errors.title.message}
            </p>
          ) : null}
        </div>
        <div className="flex min-w-0 flex-col gap-1.5">
          <Label htmlFor={`${idBase}-description`}>{t("i18nAdmin.course.description")}</Label>
          <Textarea
            id={`${idBase}-description`}
            lang={locale}
            rows={5}
            disabled={pending}
            aria-invalid={errors.description ? true : undefined}
            aria-describedby={errors.description ? `${idBase}-description-error` : undefined}
            {...form.register("description")}
          />
          {errors.description ? (
            <p id={`${idBase}-description-error`} role="alert" className="text-sm text-destructive">
              {errors.description.message}
            </p>
          ) : null}
        </div>
        <div className="flex flex-wrap gap-2">
          <Button type="submit" size="sm" disabled={pending} aria-busy={save.isPending}>
            {save.isPending ? t("i18nAdmin.common.saving") : t("i18nAdmin.course.saveDraft")}
          </Button>
          <Button type="button" size="sm" variant="outline" aria-pressed={preview} onClick={() => setPreview((value) => !value)}>
            <Eye aria-hidden="true" />
            {t("i18nAdmin.course.preview")}
          </Button>
          {variant && (variant.status !== "PUBLISHED" || variant.hasUnpublishedChanges) ? (
            <Button type="button" size="sm" variant="outline" disabled={pending} onClick={() => void togglePublish(true)}>
              {t("i18nAdmin.course.publish")}
            </Button>
          ) : null}
          {variant?.status === "PUBLISHED" ? (
            <Button type="button" size="sm" variant="ghost" disabled={pending} onClick={() => void togglePublish(false)}>
              {t("i18nAdmin.course.unpublish")}
            </Button>
          ) : null}
        </div>
      </form>

      {preview ? (
        <div className="rounded-md border border-dashed border-border p-3" data-testid="course-variant-preview">
          <p className="text-xs font-medium text-muted-foreground">{t("i18nAdmin.course.previewHeading")}</p>
          <p className="mt-1 font-semibold break-words" lang={locale}>
            {values.title || t("i18nAdmin.course.previewEmpty")}
          </p>
          {values.description ? (
            <p className="mt-1 text-sm break-words whitespace-pre-wrap text-muted-foreground" lang={locale}>
              {values.description}
            </p>
          ) : null}
        </div>
      ) : null}

      {message?.kind === "success" ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>{message.text}</AlertDescription>
        </Alert>
      ) : null}
      {message?.kind === "error" ? (
        <Alert variant="destructive" role="alert">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{message.text}</AlertDescription>
        </Alert>
      ) : null}
    </section>
  );
}
