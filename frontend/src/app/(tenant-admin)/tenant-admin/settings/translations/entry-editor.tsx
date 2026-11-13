"use client";

import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { AlertCircle, CheckCircle2, RotateCcw } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { isApiClientError } from "@/lib/api/error";
import {
  useDiscardTranslationDraft,
  useResetTranslation,
  useSaveTranslationDraft,
  type TranslationEntry,
} from "@/lib/api/translations";
import { useT, type TranslateFn } from "@/i18n/i18n-provider";
import { localizeApiError } from "@/i18n/errors";
import { validateMessage, MAX_MESSAGE_LENGTH } from "@/i18n/icu";
import { localeAutonym } from "@/i18n/locales";
import { useLocaleFormat } from "@/i18n/use-locale-format";
import { EntryBadges } from "./entry-badges";
import { describeProblem, describeServerProblem, serverProblems } from "./problems";

function editorSchema(t: TranslateFn, locale: string, source: string) {
  return z.object({
    value: z
      .string()
      .max(MAX_MESSAGE_LENGTH, t("i18nAdmin.problem.tooLong", { max: MAX_MESSAGE_LENGTH }))
      .superRefine((value, ctx) => {
        if (!value.trim()) {
          ctx.addIssue({ code: "custom", message: t("i18nAdmin.problem.empty") });
          return;
        }
        // UX only: the server re-validates the same rules on save and publish.
        for (const problem of validateMessage(value, locale, source)) {
          ctx.addIssue({ code: "custom", message: describeProblem(t, problem) });
        }
      }),
  });
}

type EditorValues = { value: string };

interface Feedback {
  kind: "success" | "error" | "stale";
  text: string;
  problems?: string[];
}

/**
 * Source-vs-translation editor for one key. Values are rendered as plain
 * text only (React text nodes / textarea value) - never as HTML.
 */
export function EntryEditor({
  entry,
  locale,
  canEdit,
  onReload,
}: {
  entry: TranslationEntry;
  locale: string;
  canEdit: boolean;
  onReload: () => void;
}) {
  const t = useT();
  const { formatDateTime } = useLocaleFormat();
  const save = useSaveTranslationDraft();
  const reset = useResetTranslation();
  const discard = useDiscardTranslationDraft();
  const [feedback, setFeedback] = useState<Feedback | null>(null);
  const schema = useMemo(() => editorSchema(t, locale, entry.source), [t, locale, entry.source]);
  const initial =
    (entry.draft?.action === "SET" ? entry.draft.value : null) ?? entry.publishedOverride ?? entry.platformValue ?? "";

  const form = useForm<EditorValues>({
    resolver: zodResolver(schema),
    mode: "onChange",
    defaultValues: { value: initial },
  });
  const value = form.watch("value");
  // Live validation list (also shown before the first submit).
  const liveProblems = useMemo(
    () => (value.trim() ? validateMessage(value, locale, entry.source).map((problem) => describeProblem(t, problem)) : []),
    [value, locale, entry.source, t]
  );
  const pending = save.isPending || reset.isPending || discard.isPending;
  // Server contract: the draft's version, or null when no draft exists yet.
  const expectedVersion = entry.draft?.version ?? null;

  function handleError(error: unknown) {
    if (isApiClientError(error) && (error.code === "TRANSLATION_DRAFT_STALE" || error.status === 409)) {
      setFeedback({ kind: "stale", text: localizeApiError(t, error) });
      return;
    }
    if (isApiClientError(error) && error.status === 422) {
      setFeedback({
        kind: "error",
        text: localizeApiError(t, error),
        problems: serverProblems(error.details).map((problem) => describeServerProblem(t, problem)),
      });
      return;
    }
    setFeedback({ kind: "error", text: localizeApiError(t, error) });
  }

  const onSubmit = form.handleSubmit(async ({ value: next }) => {
    setFeedback(null);
    try {
      await save.mutateAsync({ locale, key: entry.key, value: next, expectedVersion });
      setFeedback({ kind: "success", text: t("i18nAdmin.editor.draftSaved") });
    } catch (error) {
      handleError(error);
    }
  });

  async function onReset() {
    setFeedback(null);
    try {
      await reset.mutateAsync({ locale, key: entry.key, expectedVersion });
      setFeedback({ kind: "success", text: t("i18nAdmin.editor.resetQueued") });
    } catch (error) {
      handleError(error);
    }
  }

  async function onDiscard() {
    if (!entry.draft) return;
    setFeedback(null);
    try {
      await discard.mutateAsync({ locale, key: entry.key, expectedVersion: entry.draft.version });
      setFeedback({ kind: "success", text: t("i18nAdmin.editor.draftDiscarded") });
    } catch (error) {
      handleError(error);
    }
  }

  const canReset = canEdit && (entry.publishedOverride !== null || entry.draft?.action === "SET");
  const fieldId = `translation-value-${entry.key.replace(/[^A-Za-z0-9_-]/g, "-")}`;

  return (
    <article className="flex min-w-0 flex-col gap-4 rounded-lg border border-border p-4" aria-labelledby={`${fieldId}-heading`} data-testid="entry-editor">
      <div className="flex min-w-0 flex-col gap-2">
        <h3 id={`${fieldId}-heading`} className="font-mono text-sm break-all text-foreground">
          {entry.key}
        </h3>
        <EntryBadges entry={entry} />
        {entry.context ? (
          <p className="text-sm text-muted-foreground">
            <span className="font-medium">{t("i18nAdmin.editor.context")}:</span> {entry.context}
          </p>
        ) : null}
      </div>

      <dl className="grid min-w-0 gap-3 text-sm">
        <div className="min-w-0">
          <dt className="text-xs font-medium text-muted-foreground">{t("i18nAdmin.editor.source")}</dt>
          <dd className="break-words whitespace-pre-wrap text-foreground" lang="en">
            {entry.source}
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="text-xs font-medium text-muted-foreground">{t("i18nAdmin.editor.platform")}</dt>
          <dd className="break-words whitespace-pre-wrap text-foreground" lang={locale}>
            {entry.platformValue ?? <span className="text-muted-foreground italic">{t("i18nAdmin.editor.none")}</span>}
          </dd>
        </div>
        {entry.suggestion ? (
          <div className="min-w-0 rounded-md border border-warning/40 bg-warning/5 p-2">
            <dt className="text-xs font-medium text-muted-foreground">{t("i18nAdmin.editor.suggestion")}</dt>
            <dd className="flex min-w-0 flex-col gap-2">
              <span className="break-words whitespace-pre-wrap" lang={locale}>
                {entry.suggestion}
              </span>
              {canEdit ? (
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="w-fit"
                  onClick={() => form.setValue("value", entry.suggestion ?? "", { shouldValidate: true, shouldDirty: true })}
                >
                  {t("i18nAdmin.editor.useSuggestion")}
                </Button>
              ) : null}
            </dd>
          </div>
        ) : null}
        <div className="min-w-0">
          <dt className="text-xs font-medium text-muted-foreground">{t("i18nAdmin.editor.published")}</dt>
          <dd className="break-words whitespace-pre-wrap text-foreground" lang={locale}>
            {entry.publishedOverride ?? <span className="text-muted-foreground italic">{t("i18nAdmin.editor.none")}</span>}
            {entry.overrideNeedsReview ? (
              <span className="mt-1 block text-xs text-warning">{t("i18nAdmin.editor.overrideNeedsReview")}</span>
            ) : null}
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="text-xs font-medium text-muted-foreground">{t("i18nAdmin.editor.draft")}</dt>
          <dd className="break-words whitespace-pre-wrap text-foreground" lang={locale}>
            {entry.draft ? (
              <>
                {entry.draft.action === "RESET" ? t("i18nAdmin.editor.draftIsReset") : entry.draft.value}
                <span className="mt-1 block text-xs text-muted-foreground">
                  {t("i18nAdmin.editor.draftUpdated", { when: formatDateTime(entry.draft.updatedAt) })}
                </span>
              </>
            ) : (
              <span className="text-muted-foreground italic">{t("i18nAdmin.editor.none")}</span>
            )}
          </dd>
        </div>
      </dl>

      {canEdit ? (
        <form onSubmit={onSubmit} noValidate className="flex min-w-0 flex-col gap-3">
          <div className="flex min-w-0 flex-col gap-1.5">
            <Label htmlFor={fieldId}>
              {t("i18nAdmin.editor.translationLabel", { language: localeAutonym(locale) })}
            </Label>
            <Textarea
              id={fieldId}
              rows={4}
              lang={locale}
              aria-invalid={liveProblems.length > 0 ? true : undefined}
              aria-describedby={`${fieldId}-problems`}
              disabled={pending}
              {...form.register("value")}
            />
            <div id={`${fieldId}-problems`} aria-live="polite">
              {liveProblems.length > 0 ? (
                <ul className="list-disc pl-5 text-sm text-destructive" data-testid="live-problems">
                  {liveProblems.map((problem) => (
                    <li key={problem}>{problem}</li>
                  ))}
                </ul>
              ) : form.formState.errors.value?.message && !value.trim() ? (
                <p className="text-sm text-destructive">{form.formState.errors.value.message}</p>
              ) : null}
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={pending || liveProblems.length > 0 || !value.trim()} aria-busy={save.isPending}>
              {save.isPending ? t("i18nAdmin.common.saving") : t("i18nAdmin.editor.saveDraft")}
            </Button>
            {canReset ? (
              <Button type="button" variant="outline" disabled={pending} onClick={() => void onReset()}>
                <RotateCcw aria-hidden="true" />
                {t("i18nAdmin.editor.reset")}
              </Button>
            ) : null}
            {entry.draft ? (
              <Button type="button" variant="ghost" disabled={pending} onClick={() => void onDiscard()}>
                {t("i18nAdmin.editor.discard")}
              </Button>
            ) : null}
          </div>
        </form>
      ) : (
        <p className="text-xs text-muted-foreground">{t("i18nAdmin.editor.readOnly")}</p>
      )}

      {feedback?.kind === "success" ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>{feedback.text}</AlertDescription>
        </Alert>
      ) : null}
      {feedback && feedback.kind !== "success" ? (
        <Alert variant="destructive" role="alert" data-testid="editor-error">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            <span className="block">{feedback.kind === "stale" ? t("i18nAdmin.editor.stale") : feedback.text}</span>
            {feedback.problems && feedback.problems.length > 0 ? (
              <ul className="mt-1 list-disc pl-5">
                {feedback.problems.map((problem) => (
                  <li key={problem}>{problem}</li>
                ))}
              </ul>
            ) : null}
            {feedback.kind === "stale" ? (
              <Button type="button" size="sm" variant="outline" className="mt-2" onClick={onReload}>
                {t("i18nAdmin.common.reload")}
              </Button>
            ) : null}
          </AlertDescription>
        </Alert>
      ) : null}
    </article>
  );
}
