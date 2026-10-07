"use client";

import { useCallback, useEffect, useState } from "react";
import {
  AlertCircle,
  Link2,
  Loader2,
  MonitorSmartphone,
  Trash2,
} from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
import { isApiClientError } from "@/lib/api/error";
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

const REVOCATION_LABELS: Record<string, string> = {
  STUDENT: "removed by you",
  ADMIN: "removed by your institute",
  DEVICE_REVOKED: "its device was removed",
  REPAIRED: "replaced by a newer pairing",
};

export default function AocPlayerPage() {
  const query = useMyPlayerInstallations();
  const { refetch } = query;
  const refreshPlayers = useCallback(() => void refetch(), [refetch]);

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">AOC Player</h1>
        <p className="text-sm text-muted-foreground">
          If your institute plays lesson videos in the AOC Player app, pair it
          with this computer once.
        </p>
      </div>
      <PairCard
        onPaired={refreshPlayers}
        pairedCount={activeCount(query.data)}
      />
      <section
        aria-labelledby="players-heading"
        className="flex flex-col gap-3"
      >
        <h2
          id="players-heading"
          className="text-base font-semibold text-foreground"
        >
          Your players
        </h2>
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading your players…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/student/dashboard" }}
          isEmpty={(data) => data.length === 0}
          emptyState={{
            title: "No AOC Player paired yet",
            description:
              "Install the AOC Player on this computer, then choose “Pair the AOC Player” above.",
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

function activeCount(installations: PlayerInstallation[] | undefined): number {
  return (
    installations?.filter((installation) => installation.status === "ACTIVE")
      .length ?? 0
  );
}

function PairCard({
  onPaired,
  pairedCount,
}: {
  onPaired: () => void;
  pairedCount: number;
}) {
  const pairing = useStartPairing();
  const [waitingSince, setWaitingSince] = useState<number | null>(null);
  const [baseline, setBaseline] = useState(pairedCount);
  const [launchFailed, setLaunchFailed] = useState(false);

  const paired = waitingSince !== null && pairedCount > baseline;
  const waiting = waitingSince !== null && !paired;

  // While waiting for the player, re-read the list so the new player shows up.
  useEffect(() => {
    if (!waiting) return;
    const poll = setInterval(onPaired, PAIRING_POLL_MS);
    const stop = setTimeout(() => setWaitingSince(null), PAIRING_WAIT_MS);
    return () => {
      clearInterval(poll);
      clearTimeout(stop);
    };
  }, [waiting, onPaired]);

  const pair = async () => {
    setLaunchFailed(false);
    try {
      const start = await pairing.mutateAsync();
      if (!openInAocPlayer(start.pairingUrl)) {
        setLaunchFailed(true);
        return;
      }
      setBaseline(pairedCount);
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
          Pair this computer
        </CardTitle>
        <CardDescription>
          1. Install the AOC Player (Windows). 2. Choose &ldquo;Pair the AOC
          Player&rdquo; and allow your browser to open it. Pairing again on this
          computer replaces the previous player.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <div role="status" aria-live="polite" className="text-sm">
          {paired ? (
            <p className="text-foreground">The AOC Player is paired.</p>
          ) : null}
          {waiting ? (
            <p className="flex items-center gap-2 text-muted-foreground">
              <Loader2 aria-hidden="true" className="size-4 animate-spin" />
              Waiting for the AOC Player to finish pairing…
            </p>
          ) : null}
        </div>
        {error ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{error.message}</AlertDescription>
          </Alert>
        ) : null}
        {launchFailed ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>
              Couldn&apos;t open the AOC Player. Please try again.
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
              {pairing.isPending ? "Preparing…" : "Pair the AOC Player"}
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
  return (
    <ul className="flex flex-col gap-2" aria-label="Your AOC Players">
      {installations.map((installation) => (
        <li
          key={installation.id}
          className="flex flex-col gap-2 rounded-md border border-border p-3 sm:flex-row sm:items-center sm:justify-between"
        >
          <div className="min-w-0">
            <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-foreground">
              AOC Player for Windows
              <Badge
                variant={
                  installation.status === "ACTIVE" ? "default" : "outline"
                }
              >
                {installation.status === "ACTIVE" ? "Active" : "Removed"}
              </Badge>
            </p>
            <p className="text-xs text-muted-foreground">
              Version {installation.playerVersion} · paired{" "}
              {formatDate(installation.pairedAt)} · last used{" "}
              {formatDate(installation.lastSeenAt)}
              {installation.status === "REVOKED" &&
              installation.revocationSource
                ? ` · ${REVOCATION_LABELS[installation.revocationSource] ?? "removed"}`
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
  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "Something went wrong. Please try again."
    : null;

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
            aria-label="Remove this AOC Player"
          />
        }
      >
        <Trash2 aria-hidden="true" />
        Remove
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Remove this AOC Player?</AlertDialogTitle>
          <AlertDialogDescription>
            Any video playing in it stops straight away. You can pair it again
            later.
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
            Cancel
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
            {mutation.isPending ? "Removing…" : "Remove player"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}
