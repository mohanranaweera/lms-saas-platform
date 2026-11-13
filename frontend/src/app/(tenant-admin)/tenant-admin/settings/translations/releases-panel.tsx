"use client";

import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { AlertCircle, CheckCircle2, History, Upload } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { isApiClientError } from "@/lib/api/error";
import {
  latestReleaseVersion,
  usePublishTranslations,
  useRollbackTranslations,
  useTranslationReleases,
  type TranslationRelease,
} from "@/lib/api/translations";
import { useT, type TranslateFn } from "@/i18n/i18n-provider";
import { localizeApiError } from "@/i18n/errors";
import { localeAutonym } from "@/i18n/locales";
import { useLocaleFormat } from "@/i18n/use-locale-format";

const SUMMARY_MAX = 500;

function summarySchema(t: TranslateFn) {
  return z.object({
    summary: z
      .string()
      .trim()
      .min(1, t("i18nAdmin.publish.summaryRequired"))
      .max(SUMMARY_MAX, t("i18nAdmin.publish.summaryTooLong", { max: SUMMARY_MAX })),
  });
}
type SummaryValues = { summary: string };

/** Releases (published versions) for one locale, with Publish and Rollback. */
export function ReleasesPanel({
  locale,
  canPublish,
  draftCount,
}: {
  locale: string;
  canPublish: boolean;
  draftCount: number | null;
}) {
  const t = useT();
  const query = useTranslationReleases(locale);
  const [publishOpen, setPublishOpen] = useState(false);
  const [rollbackTarget, setRollbackTarget] = useState<TranslationRelease | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const expectedVersion = latestReleaseVersion(query.data);

  return (
    <section aria-labelledby="releases-heading" className="flex min-w-0 flex-col gap-3 rounded-lg border border-border p-4" data-testid="releases-panel">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 id="releases-heading" className="flex items-center gap-2 text-sm font-medium text-foreground">
            <History className="size-4" aria-hidden="true" />
            {t("i18nAdmin.releases.heading", { language: localeAutonym(locale) })}
          </h3>
          <p className="text-xs text-muted-foreground">{t("i18nAdmin.releases.description")}</p>
        </div>
        {canPublish ? (
          <Button type="button" size="sm" onClick={() => setPublishOpen(true)} disabled={query.isPending} data-testid="open-publish">
            <Upload aria-hidden="true" />
            {t("i18nAdmin.publish.open")}
          </Button>
        ) : null}
      </div>

      {notice ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>{notice}</AlertDescription>
        </Alert>
      ) : null}

      <QueryStateBoundary
        query={query}
        loadingLabel={t("i18nAdmin.releases.loading")}
        loginPath="/login"
        isEmpty={(data) => data.length === 0}
        emptyState={{
          title: t("i18nAdmin.releases.empty.title"),
          description: t("i18nAdmin.releases.empty.description"),
        }}
      >
        {(releases) => (
          <ReleaseList
            releases={releases}
            latest={expectedVersion}
            canPublish={canPublish}
            onRollback={(release) => setRollbackTarget(release)}
          />
        )}
      </QueryStateBoundary>

      {publishOpen ? (
        <PublishDialog
          locale={locale}
          expectedVersion={expectedVersion}
          draftCount={draftCount}
          onClose={() => setPublishOpen(false)}
          onDone={(message) => {
            setPublishOpen(false);
            setNotice(message);
          }}
          onReload={() => void query.refetch()}
        />
      ) : null}
      {rollbackTarget ? (
        <RollbackDialog
          release={rollbackTarget}
          expectedVersion={expectedVersion}
          onClose={() => setRollbackTarget(null)}
          onDone={(message) => {
            setRollbackTarget(null);
            setNotice(message);
          }}
          onReload={() => void query.refetch()}
        />
      ) : null}
    </section>
  );
}

function ReleaseList({
  releases,
  latest,
  canPublish,
  onRollback,
}: {
  releases: TranslationRelease[];
  latest: number;
  canPublish: boolean;
  onRollback: (release: TranslationRelease) => void;
}) {
  const t = useT();
  const { formatDateTime } = useLocaleFormat();
  const sorted = [...releases].sort((a, b) => b.versionNo - a.versionNo);
  if (sorted.length === 0) {
    return <EmptyState title={t("i18nAdmin.releases.empty.title")} description={t("i18nAdmin.releases.empty.description")} />;
  }
  return (
    <ol className="flex flex-col gap-2" data-testid="release-list">
      {sorted.map((release) => {
        const rollbackOf = release.rollbackOfReleaseId
          ? releases.find((candidate) => candidate.id === release.rollbackOfReleaseId)
          : undefined;
        return (
          <li key={release.id} className="flex min-w-0 flex-col gap-1 rounded-md border border-border p-3 text-sm sm:flex-row sm:items-start sm:justify-between">
            <div className="flex min-w-0 flex-col gap-0.5">
              <span className="flex flex-wrap items-center gap-2 font-medium">
                {t("i18nAdmin.releases.version", { version: release.versionNo })}
                {release.current ? <Badge variant="secondary">{t("i18nAdmin.releases.current")}</Badge> : null}
              </span>
              {release.summary ? <span className="break-words text-foreground">{release.summary}</span> : null}
              <span className="text-xs text-muted-foreground">
                {t("i18nAdmin.releases.publishedOn", { when: formatDateTime(release.publishedAt), count: release.entryCount })}
              </span>
              {release.rollbackOfReleaseId ? (
                <span className="text-xs text-muted-foreground">
                  {t("i18nAdmin.releases.rollbackOf", { version: rollbackOf?.versionNo ?? "?" })}
                </span>
              ) : null}
            </div>
            {canPublish && !release.current && release.versionNo !== latest ? (
              <Button type="button" size="sm" variant="outline" className="w-fit shrink-0" onClick={() => onRollback(release)}>
                {t("i18nAdmin.releases.rollback")}
              </Button>
            ) : null}
          </li>
        );
      })}
    </ol>
  );
}

function SummaryForm({
  id,
  onSubmit,
  pending,
}: {
  id: string;
  onSubmit: (summary: string) => void;
  pending: boolean;
}) {
  const t = useT();
  const schema = useMemo(() => summarySchema(t), [t]);
  const form = useForm<SummaryValues>({ resolver: zodResolver(schema), defaultValues: { summary: "" } });
  const error = form.formState.errors.summary?.message;
  return (
    <form id={id} noValidate onSubmit={form.handleSubmit((values) => onSubmit(values.summary.trim()))} className="flex flex-col gap-1.5">
      <Label htmlFor={`${id}-summary`}>{t("i18nAdmin.publish.summary")}</Label>
      <Textarea
        id={`${id}-summary`}
        rows={3}
        disabled={pending}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-summary-error` : undefined}
        {...form.register("summary")}
      />
      {error ? (
        <p id={`${id}-summary-error`} role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
    </form>
  );
}

function DialogError({ error, onReload }: { error: unknown; onReload: () => void }) {
  const t = useT();
  if (!error) return null;
  const stale = isApiClientError(error) && error.code === "TRANSLATION_RELEASE_STALE";
  return (
    <Alert variant="destructive" role="alert" data-testid="publish-error">
      <AlertCircle aria-hidden="true" />
      <AlertDescription>
        <span className="block">{localizeApiError(t, error)}</span>
        {stale ? (
          <Button type="button" size="sm" variant="outline" className="mt-2" onClick={onReload}>
            {t("i18nAdmin.common.reload")}
          </Button>
        ) : null}
      </AlertDescription>
    </Alert>
  );
}

function PublishDialog({
  locale,
  expectedVersion,
  draftCount,
  onClose,
  onDone,
  onReload,
}: {
  locale: string;
  expectedVersion: number;
  draftCount: number | null;
  onClose: () => void;
  onDone: (message: string) => void;
  onReload: () => void;
}) {
  const t = useT();
  const publish = usePublishTranslations();
  return (
    <AlertDialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <AlertDialogContent data-testid="publish-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>{t("i18nAdmin.publish.title", { language: localeAutonym(locale) })}</AlertDialogTitle>
          <AlertDialogDescription>
            {draftCount === null
              ? t("i18nAdmin.publish.descriptionUnknown")
              : t("i18nAdmin.publish.description", { count: draftCount, version: expectedVersion + 1 })}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <SummaryForm
          id="publish-form"
          pending={publish.isPending}
          onSubmit={(summary) =>
            publish.mutate(
              { locale, expectedVersion, summary },
              { onSuccess: (release) => onDone(t("i18nAdmin.publish.done", { version: release?.versionNo ?? expectedVersion + 1 })) }
            )
          }
        />
        <DialogError error={publish.error} onReload={onReload} />
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>{t("common.actions.cancel")}</AlertDialogClose>
          <Button type="submit" form="publish-form" disabled={publish.isPending} aria-busy={publish.isPending} data-testid="confirm-publish">
            {publish.isPending ? t("i18nAdmin.publish.publishing") : t("i18nAdmin.publish.confirm")}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function RollbackDialog({
  release,
  expectedVersion,
  onClose,
  onDone,
  onReload,
}: {
  release: TranslationRelease;
  expectedVersion: number;
  onClose: () => void;
  onDone: (message: string) => void;
  onReload: () => void;
}) {
  const t = useT();
  const rollback = useRollbackTranslations();
  return (
    <AlertDialog open onOpenChange={(open) => (!open ? onClose() : undefined)}>
      <AlertDialogContent data-testid="rollback-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>{t("i18nAdmin.rollback.title", { version: release.versionNo })}</AlertDialogTitle>
          <AlertDialogDescription>
            {t("i18nAdmin.rollback.description", { version: release.versionNo, next: expectedVersion + 1 })}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <SummaryForm
          id="rollback-form"
          pending={rollback.isPending}
          onSubmit={(summary) =>
            rollback.mutate(
              { releaseId: release.id, expectedVersion, summary },
              { onSuccess: (created) => onDone(t("i18nAdmin.rollback.done", { version: created?.versionNo ?? expectedVersion + 1 })) }
            )
          }
        />
        <DialogError error={rollback.error} onReload={onReload} />
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>{t("common.actions.cancel")}</AlertDialogClose>
          <Button type="submit" form="rollback-form" variant="destructive" disabled={rollback.isPending} aria-busy={rollback.isPending}>
            {rollback.isPending ? t("i18nAdmin.rollback.working") : t("i18nAdmin.rollback.confirm")}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
