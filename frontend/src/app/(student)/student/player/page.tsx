"use client";

import { useCallback, useEffect, useState } from "react";
import {
  AlertCircle,
  Download,
  Link2,
  Loader2,
  MonitorSmartphone,
  Trash2,
} from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { playbackErrorMessage } from "@/components/player/playback-errors";
import { useT, type TranslateFn } from "@/i18n/i18n-provider";
import { localizeApiError } from "@/i18n/errors";
import { useLocaleFormat } from "@/i18n/use-locale-format";
import { env } from "@/lib/env";
import {
  useMyPlayerInstallations,
  useRevokeMyInstallation,
  useStartPairing,
  type PlayerInstallation,
} from "@/lib/api/player";
import {
  openInAocPlayer,
  useAocPlayerSupported,
} from "@/lib/player/native-launch";
import { UnsupportedPlatformNotice } from "@/components/player/native-player-launcher";

/**
 * Student → AOC Player (VIDEO Phase 8, ADR-025). Pairs the desktop player with
 * this browser's device and lists the Student's own players. Pairing hands a
 * one-time `aocplayer://pair` link to the OS from the click; the backend then
 * registers the player's key. The page only reflects what the backend lists.
 */

const PAIRING_WAIT_MS = 2 * 60_000;
const PAIRING_POLL_MS = 3_000;

const REVOCATION_LABELS: Record<string, (t: TranslateFn) => string> = {
  STUDENT: (t) => t("student.player.revoked.student"),
  ADMIN: (t) => t("student.player.revoked.admin"),
  DEVICE_REVOKED: (t) => t("student.player.revoked.device"),
  REPAIRED: (t) => t("student.player.revoked.repaired"),
};

export default function AocPlayerPage() {
  const query = useMyPlayerInstallations();
  const { refetch } = query;
  const refreshPlayers = useCallback(() => void refetch(), [refetch]);
  const t = useT();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold break-words text-foreground">{t("student.player.title")}</h1>
        <p className="text-sm text-muted-foreground">
          {t("student.player.subtitle")}
        </p>
      </div>
      <InstallCard />
      <PairCard
        onPaired={refreshPlayers}
        activeIds={activeIds(query.data)}
      />
      <section
        aria-labelledby="players-heading"
        className="flex flex-col gap-3"
      >
        <h2
          id="players-heading"
          className="text-base font-semibold text-foreground"
        >
          {t("student.player.yourPlayers")}
        </h2>
        <QueryStateBoundary
          query={query}
          loadingLabel={t("student.player.loading")}
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/student/dashboard" }}
          isEmpty={(data) => data.length === 0}
          emptyState={{
            title: t("student.player.empty.title"),
            description: t("student.player.empty.description"),
          }}
        >
          {(installations) => (
            <InstallationList installations={installations} />
          )}
        </QueryStateBoundary>
      </section>
    </div>
  );
}

function activeIds(installations: PlayerInstallation[] | undefined): string[] {
  return (
    installations
      ?.filter((installation) => installation.status === "ACTIVE")
      .map((installation) => installation.id) ?? []
  );
}

/**
 * Step 1: install the player (VIDEO Phase 9). The installer location is a
 * build-time setting; until signed releases exist it is unset and the card
 * says so. On phones, tablets and Macs the pairing card already explains the
 * player is Windows-only, so this card is not shown.
 */
function InstallCard() {
  const supported = useAocPlayerSupported();
  const t = useT();
  if (supported === false) {
    return null;
  }
  const downloadUrl = env.NEXT_PUBLIC_AOC_PLAYER_DOWNLOAD_URL;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Download aria-hidden="true" className="size-4" />
          {t("student.player.install.title")}
        </CardTitle>
        <CardDescription>
          {t("student.player.install.description")}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {downloadUrl ? (
          <a
            href={downloadUrl}
            rel="noopener noreferrer"
            className={buttonVariants({ variant: "outline" })}
          >
            <Download aria-hidden="true" />
            {t("student.player.install.download")}
          </a>
        ) : (
          <p className="text-sm text-muted-foreground">
            {t("student.player.install.unavailable")}
          </p>
        )}
      </CardContent>
    </Card>
  );
}

function PairCard({
  onPaired,
  activeIds,
}: {
  onPaired: () => void;
  activeIds: string[];
}) {
  const pairing = useStartPairing();
  const t = useT();
  const [waitingSince, setWaitingSince] = useState<number | null>(null);
  const [baseline, setBaseline] = useState<string[]>(activeIds);
  const [launchFailed, setLaunchFailed] = useState(false);
  const [timedOut, setTimedOut] = useState(false);

  // Paired once an active player appears that wasn't there before. Re-pairing
  // this computer replaces its player, so the count alone doesn't change.
  const paired =
    waitingSince !== null && activeIds.some((id) => !baseline.includes(id));
  const waiting = waitingSince !== null && !paired;

  // While waiting for the player, re-read the list so the new player shows up.
  useEffect(() => {
    if (!waiting) return;
    const poll = setInterval(onPaired, PAIRING_POLL_MS);
    const stop = setTimeout(() => {
      setWaitingSince(null);
      setTimedOut(true);
    }, PAIRING_WAIT_MS);
    return () => {
      clearInterval(poll);
      clearTimeout(stop);
    };
  }, [waiting, onPaired]);

  const pair = async () => {
    setLaunchFailed(false);
    setTimedOut(false);
    try {
      const start = await pairing.mutateAsync();
      if (!openInAocPlayer(start.pairingUrl)) {
        setLaunchFailed(true);
        return;
      }
      setBaseline(activeIds);
      setWaitingSince(Date.now());
    } catch {
      // shown from pairing.error below
    }
  };

  const error = pairing.isError ? playbackErrorMessage(pairing.error) : null;
  const supported = useAocPlayerSupported();

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <MonitorSmartphone aria-hidden="true" className="size-4" />
          {t("student.player.pair.title")}
        </CardTitle>
        <CardDescription>
          {t("student.player.pair.description")}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div role="status" aria-live="polite" className="text-sm">
          {paired ? (
            <p className="text-foreground">{t("student.player.pair.done")}</p>
          ) : null}
          {waiting ? (
            <p className="flex items-center gap-2 text-muted-foreground">
              <Loader2 aria-hidden="true" className="size-4 animate-spin" />
              {t("student.player.pair.waiting")}
            </p>
          ) : null}
        </div>
        {error ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{error.message}</AlertDescription>
          </Alert>
        ) : null}
        {timedOut ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>
              {t("student.player.pair.timedOut")}
            </AlertDescription>
          </Alert>
        ) : null}
        {launchFailed ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>
              {t("student.player.pair.launchFailed")}
            </AlertDescription>
          </Alert>
        ) : null}
        {supported === false ? (
          <UnsupportedPlatformNotice />
        ) : (
          <div>
            <Button
              type="button"
              onClick={pair}
              disabled={pairing.isPending || supported === null}
              aria-busy={pairing.isPending}
            >
              <Link2 aria-hidden="true" />
              {pairing.isPending ? t("student.player.preparing") : t("student.player.pair.button")}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function InstallationList({
  installations,
}: {
  installations: PlayerInstallation[];
}) {
  const t = useT();
  const { formatDate } = useLocaleFormat();
  const shortDate = (iso: string) => formatDate(iso, { year: "numeric", month: "short", day: "numeric" });
  return (
    <ul className="flex flex-col gap-2" aria-label={t("student.player.listLabel")}>
      {installations.map((installation) => (
        <li
          key={installation.id}
          className="flex flex-col gap-2 rounded-md border border-border p-3 sm:flex-row sm:items-center sm:justify-between"
        >
          <div className="min-w-0">
            <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-foreground">
              {t("student.player.forWindows")}
              <Badge
                variant={
                  installation.status === "ACTIVE" ? "default" : "outline"
                }
              >
                {installation.status === "ACTIVE" ? t("student.player.active") : t("student.player.removedBadge")}
              </Badge>
            </p>
            <p className="text-xs text-muted-foreground">
              {t("student.player.details", { version: installation.playerVersion, paired: shortDate(installation.pairedAt), lastUsed: shortDate(installation.lastSeenAt) })}
              {installation.status === "REVOKED" &&
              installation.revocationSource
                ? ` · ${REVOCATION_LABELS[installation.revocationSource]?.(t) ?? t("student.player.revoked.other")}`
                : ""}
            </p>
          </div>
          {installation.status === "ACTIVE" ? (
            <RemovePlayerDialog installation={installation} />
          ) : null}
        </li>
      ))}
    </ul>
  );
}

function RemovePlayerDialog({
  installation,
}: {
  installation: PlayerInstallation;
}) {
  const mutation = useRevokeMyInstallation();
  const [open, setOpen] = useState(false);
  const t = useT();
  const errorMessage = mutation.isError ? localizeApiError(t, mutation.error) : null;

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) mutation.reset();
      }}
    >
      <AlertDialogTrigger
        render={
          <Button
            type="button"
            variant="outline"
            size="sm"
            aria-label={t("student.player.remove.label")}
          />
        }
      >
        <Trash2 aria-hidden="true" />
        {t("student.player.remove.button")}
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{t("student.player.remove.title")}</AlertDialogTitle>
          <AlertDialogDescription>
            {t("student.player.remove.description")}
          </AlertDialogDescription>
        </AlertDialogHeader>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>
            {t("common.actions.cancel")}
          </AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
            onClick={() =>
              mutation.mutate(installation.id, {
                onSuccess: () => setOpen(false),
              })
            }
          >
            {mutation.isPending ? t("student.player.remove.removing") : t("student.player.remove.confirm")}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}


