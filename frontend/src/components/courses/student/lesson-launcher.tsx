"use client";

import { useState, type ReactNode } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FileText, MonitorX, Smartphone, Video } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { NativePlayerLauncher } from "@/components/player/native-player-launcher";
import { isApiClientError } from "@/lib/api/error";
import {
  useJoinLessonSession,
  useLaunchLesson,
  useLessonSessionRecording,
  type LaunchResource,
  type LessonAccessStatus,
  type LessonLaunchResponse,
  type LessonNode,
} from "@/lib/api/klass-student";
import { cn } from "@/lib/utils";
import { StudentDialog } from "./student-dialog";
import { formatLessonDateTime } from "./student-format";

import { useT, type TranslateFn } from "@/i18n/i18n-provider";
type LaunchDialog =
  | { kind: "handoff"; lesson: LessonNode; result: LessonLaunchResponse }
  | { kind: "resources"; lesson: LessonNode; result: LessonLaunchResponse }
  | { kind: "denied"; lesson: LessonNode; status: LessonAccessStatus | "UNAVAILABLE" | "ERROR"; message: string };

function materialsHref(lesson: LessonNode): string {
  return `/student/courses/${lesson.courseId}/modules/${lesson.moduleId}/lessons/${lesson.lessonId}/materials`;
}

function deniedMessage(status: string, lesson: LessonNode, timeZone: string | null, t: TranslateFn): string {
  switch (status) {
    case "PAYMENT_REQUIRED":
      return t("student.lesson.denied.paymentRequired");
    case "PAYMENT_PENDING":
      return t("student.lesson.denied.paymentPending");
    case "NOT_STARTED":
      return lesson.startsAt
        ? t("student.lesson.denied.opensOn", { when: formatLessonDateTime(lesson.startsAt, timeZone) })
        : t("student.lesson.denied.notOpened");
    case "EXPIRED":
      return t("student.lesson.denied.expired");
    case "NOT_ENROLLED":
      return t("student.lesson.denied.notEnrolled");
    case "DEVICE_NOT_AUTHORIZED":
      return t("student.lesson.denied.device");
    case "UNAVAILABLE":
      return t("student.lesson.denied.unavailable");
    default:
      return t("student.lesson.denied.generic");
  }
}

/**
 * Lesson launch (CM-R01..R03, EP-L01, D14): clicking an ALLOWED lesson asks
 * the server to launch it (which records the first view and lists resource
 * ids), then opens those resources through their own lesson-guarded
 * endpoints/pages. The server re-checks access on every resource fetch; this
 * hook only routes the student to the right place. Denials show the server's
 * reason and offer Pay now when payment is what's missing.
 */
export function useLessonLauncher({
  pageCourseId,
  timeZone,
  onPay,
}: {
  pageCourseId: string;
  timeZone: string | null;
  onPay: (lesson: LessonNode) => void;
}): { launch: (lesson: LessonNode) => void; launchingId: string | null; element: ReactNode; notice: string } {
  const router = useRouter();
  const t = useT();
  const launchMutation = useLaunchLesson();
  const [launchingId, setLaunchingId] = useState<string | null>(null);
  const [dialog, setDialog] = useState<LaunchDialog | null>(null);
  const [notice, setNotice] = useState("");

  const launch = async (lesson: LessonNode) => {
    if (launchingId) return;
    setLaunchingId(lesson.lessonId);
    setNotice("");
    try {
      const result = await launchMutation.mutateAsync({
        courseId: lesson.courseId,
        lessonId: lesson.lessonId,
        pageCourseId,
      });
      if (result.playerHandoffRequired) {
        setDialog({ kind: "handoff", lesson, result });
        return;
      }
      const materials = result.resources.filter((resource) => resource.kind === "MATERIAL");
      const sessions = result.resources.filter((resource) => resource.kind === "CLASS_SESSION");
      if (materials.length === 0 && sessions.length === 0) {
        setNotice(t("student.lesson.emptyNotice"));
        setDialog({
          kind: "denied",
          lesson,
          status: "ERROR",
          message: t("student.lesson.emptyMessage"),
        });
        return;
      }
      if (sessions.length === 0) {
        // Materials (documents, links, videos) open in the existing lesson
        // materials page, which plays/downloads through guarded endpoints.
        router.push(materialsHref(lesson));
        return;
      }
      setDialog({ kind: "resources", lesson, result });
    } catch (error) {
      const status = isApiClientError(error)
        ? error.status === 404
          ? "UNAVAILABLE"
          : error.status === 403
            ? ((error.code as LessonAccessStatus) ?? "ERROR")
            : "ERROR"
        : "ERROR";
      const message = deniedMessage(status, lesson, timeZone, t);
      setNotice(message);
      setDialog({ kind: "denied", lesson, status, message });
    } finally {
      setLaunchingId(null);
    }
  };

  const close = () => setDialog(null);

  let element: ReactNode = null;
  if (dialog?.kind === "handoff") {
    element = <PlayerHandoffDialog lesson={dialog.lesson} result={dialog.result} onClose={close} />;
  } else if (dialog?.kind === "resources") {
    element = <LessonResourcesDialog lesson={dialog.lesson} result={dialog.result} onClose={close} />;
  } else if (dialog?.kind === "denied") {
    const canPay =
      dialog.status === "PAYMENT_REQUIRED" &&
      dialog.lesson.paymentType !== "FREE" &&
      dialog.lesson.access.requiredPurchase !== null;
    element = (
      <StudentDialog
        open
        onOpenChange={(open) => !open && close()}
        title={dialog.status === "ERROR" ? t("student.lessonLauncher.cantOpenThisLesson") : t("student.lessonLauncher.lessonLocked")}
        testId="lesson-launch-denied"
        footer={
          <>
            <Button type="button" variant="outline" onClick={close}>
              {t("student.lessonLauncher.close")}
            </Button>
            {canPay ? (
              <Button
                type="button"
                className="bg-red-600 text-white hover:bg-red-700"
                data-testid="lesson-launch-pay-now"
                onClick={() => {
                  const lesson = dialog.lesson;
                  close();
                  onPay(lesson);
                }}
              >
                {t("student.lesson.payNow")}
              </Button>
            ) : null}
            {dialog.status === "DEVICE_NOT_AUTHORIZED" ? (
              <Link href="/student/devices" className={buttonVariants()}>
                {t("student.lessonLauncher.myDevices")}
              </Link>
            ) : null}
          </>
        }
      >
        <p className="font-medium text-foreground">{dialog.lesson.title}</p>
        <p className="mt-1 text-muted-foreground" data-testid="lesson-launch-denied-reason" data-status={dialog.status}>
          {dialog.message}
        </p>
      </StudentDialog>
    );
  }

  return { launch: (lesson) => void launch(lesson), launchingId, element, notice };
}

/**
 * "Web playback is turned off" (CM-R03): the institute requires the AOC
 * Player. Videos open through the existing one-time player launcher (the
 * backend re-checks access before issuing the launch); other materials stay
 * on the guarded materials page. Mobile links show only when configured.
 */
function PlayerHandoffDialog({
  lesson,
  result,
  onClose,
}: {
  lesson: LessonNode;
  result: LessonLaunchResponse;
  onClose: () => void;
}) {
  const t = useT();
  const videos = result.resources.filter((resource) => resource.kind === "MATERIAL" && resource.type === "VIDEO");
  const otherMaterials = result.resources.some((resource) => resource.kind === "MATERIAL" && resource.type !== "VIDEO");
  const android = result.playerLinks?.androidUrl ?? null;
  const ios = result.playerLinks?.iosUrl ?? null;

  return (
    <StudentDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={t("student.lessonLauncher.webPlaybackIsTurned")}
      icon={
        <span className="inline-flex size-8 items-center justify-center rounded-md bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300">
          <MonitorX className="size-4" aria-hidden="true" />
        </span>
      }
      testId="player-handoff-dialog"
    >
      <div className="flex flex-col gap-4">
        <p className="text-center text-muted-foreground">
          {t("student.lessonLauncher.yourInstituteHasTurned")}
        </p>

        {videos.length > 0 ? (
          <div className="flex flex-col gap-3" data-testid="player-handoff-launchers">
            {videos.map((video) => (
              <NativePlayerLauncher key={video.id} lessonMediaId={video.id} title={video.title} />
            ))}
          </div>
        ) : (
          <p className="text-center text-sm text-muted-foreground">
            There&apos;s no video in this lesson to open in the AOC Player.
          </p>
        )}

        {otherMaterials ? (
          <Link
            href={materialsHref(lesson)}
            onClick={onClose}
            className={cn(buttonVariants({ variant: "outline" }), "w-full")}
          >
            <FileText aria-hidden="true" />
            {t("student.lessonLauncher.openOtherLessonMaterials")}
          </Link>
        ) : null}

        <div className="flex items-center gap-3 text-xs text-muted-foreground" aria-hidden="true">
          <span className="h-px flex-1 bg-border" />
          {t("student.lessonLauncher.orUseTheMobile")}
          <span className="h-px flex-1 bg-border" />
        </div>
        <div className="grid grid-cols-2 gap-2" data-testid="player-handoff-mobile">
          <MobileLink url={android} label={t("student.lessonLauncher.android")} />
          <MobileLink url={ios} label={t("student.lessonLauncher.ios")} />
        </div>
      </div>
    </StudentDialog>
  );
}

function MobileLink({ url, label }: { url: string | null; label: string }) {
  const t = useT();
  if (!url) {
    return (
      <div className="flex flex-col items-center justify-center rounded-lg bg-muted px-3 py-2 text-center text-xs text-muted-foreground">
        <span className="font-medium text-foreground">{label}</span>
        {t("student.lessonLauncher.notConfiguredByYour")}
      </div>
    );
  }
  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className={cn(buttonVariants({ variant: "secondary" }), "h-10")}
      data-testid={`player-handoff-${label.toLowerCase()}`}
    >
      <Smartphone aria-hidden="true" />
      {label}
    </a>
  );
}

/** A lesson with a live class session (and possibly materials): explicit Join / recording / materials actions. */
function LessonResourcesDialog({
  lesson,
  result,
  onClose,
}: {
  lesson: LessonNode;
  result: LessonLaunchResponse;
  onClose: () => void;
}) {
  const t = useT();
  const sessions = result.resources.filter((resource) => resource.kind === "CLASS_SESSION");
  const hasMaterials = result.resources.some((resource) => resource.kind === "MATERIAL");

  return (
    <StudentDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={lesson.title}
      testId="lesson-resources-dialog"
      footer={
        <Button type="button" variant="outline" onClick={onClose}>
          {t("student.lessonLauncher.close")}
        </Button>
      }
    >
      <ul className="flex flex-col gap-2">
        {sessions.map((session) => (
          <SessionResourceRow key={session.id} session={session} />
        ))}
        {hasMaterials ? (
          <li>
            <Link
              href={materialsHref(lesson)}
              onClick={onClose}
              className={cn(buttonVariants({ variant: "outline" }), "w-full")}
              data-testid="lesson-open-materials"
            >
              <FileText aria-hidden="true" />
              {t("student.lessonLauncher.openLessonMaterials")}
            </Link>
          </li>
        ) : null}
      </ul>
    </StudentDialog>
  );
}

function SessionResourceRow({ session }: { session: LaunchResource }) {
  const t = useT();
  const join = useJoinLessonSession();
  const recording = useLessonSessionRecording();
  const [error, setError] = useState<string | null>(null);
  const completed = session.type === "COMPLETED";
  const busy = join.isPending || recording.isPending;

  const act = async () => {
    setError(null);
    try {
      if (completed) {
        const result = await recording.mutateAsync(session.id);
        window.open(result.playbackUrl, "_blank", "noopener,noreferrer");
      } else {
        const result = await join.mutateAsync(session.id);
        window.open(result.joinUrl, "_blank", "noopener,noreferrer");
      }
    } catch (err) {
      setError(
        isApiClientError(err) && err.status !== 404
          ? err.message
          : completed
            ? t("student.lessonLauncher.theRecordingIsntAvailable")
            : t("student.lessonLauncher.youCantJoinThis")
      );
    }
  };

  return (
    <li className="flex flex-col gap-2 rounded-lg border border-border p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="flex items-center gap-2 font-medium text-foreground">
          <Video className="size-4 text-blue-700" aria-hidden="true" />
          {session.title}
        </span>
        <Button
          type="button"
          size="sm"
          onClick={act}
          disabled={busy}
          aria-busy={busy}
          className="bg-blue-600 text-white hover:bg-blue-700"
          data-testid="lesson-join-session"
        >
          {busy ? t("student.lessonLauncher.opening") : completed ? t("student.lessonLauncher.watchRecording") : t("student.lessonLauncher.join")}
        </Button>
      </div>
      {!completed ? (
        <p className="text-xs text-muted-foreground">{t("student.lessonLauncher.joiningSharesYourName")}</p>
      ) : null}
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </li>
  );
}
