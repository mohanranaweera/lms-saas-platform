import { isApiClientError } from "@/lib/api/error";

/**
 * Copy for a refused `POST /api/v1/playback/sessions` (VIDEO Phases 7-8).
 * 403/404 are anti-enumeration: fixed copy, never the backend's message, and
 * no retry. Other codes get their own calm message.
 */

export const VIDEO_NOT_AVAILABLE =
  "This video isn't available. It may have been removed, or you may not have access to it.";

export interface PlaybackErrorCopy {
  message: string;
  retryable: boolean;
  /** Offer the link to pair the AOC Player. */
  suggestPairing?: boolean;
}

/**
 * Lesson-access refusals (`LessonAccessDeniedException`): the lesson exists and
 * the Student is in its course, so say why it won't open instead of a generic
 * failure. Fixed copy per code — never the backend's message.
 */
const LESSON_ACCESS_COPY: Record<string, string> = {
  NOT_ENROLLED: "Enroll in this course to watch its lessons.",
  PAYMENT_REQUIRED: "This lesson needs a payment before you can watch it.",
  PAYMENT_PENDING: "Your payment for this lesson is awaiting confirmation. You can watch once it's confirmed.",
  NOT_STARTED: "This lesson hasn't started yet. Its videos open when the lesson starts.",
  EXPIRED: "This lesson has ended, so its videos can no longer be watched.",
  DEVICE_NOT_AUTHORIZED: "This course can only be watched on your registered devices, and this device isn't one of them.",
};

/** The lesson-access reason for a refused playback request, if that's what it was. */
export function lessonAccessMessage(error: unknown): string | null {
  if (!isApiClientError(error) || !error.code) return null;
  return LESSON_ACCESS_COPY[error.code] ?? null;
}

export function playbackErrorMessage(error: unknown): PlaybackErrorCopy {
  if (!isApiClientError(error)) {
    return { message: "Couldn't load this video. Please try again.", retryable: true };
  }
  const lessonAccess = lessonAccessMessage(error);
  if (lessonAccess) {
    return { message: lessonAccess, retryable: false };
  }
  if (error.status === 403 || error.status === 404) {
    return { message: VIDEO_NOT_AVAILABLE, retryable: false };
  }
  switch (error.code) {
    case "MEDIA_NOT_READY":
      return { message: "This video is still processing. Try again in a few minutes.", retryable: true };
    case "VIDEO_PLAYBACK_DISABLED":
      return { message: "YouTube videos can't be played right now. Please try again later.", retryable: true };
    case "VIDEO_PROVIDER_DISABLED_BY_PLATFORM":
      return { message: "This video's channel isn't available right now.", retryable: false };
    case "NATIVE_PLAYER_UNAVAILABLE":
      return { message: "The AOC Player isn't available right now. Please try again later.", retryable: true };
    case "PLAYBACK_WATCH_LIMIT":
      return {
        message: "You've used all the watch time allowed for this video. Ask your institute if you need more time.",
        retryable: false,
      };
    case "PLAYER_DEVICE_NOT_REGISTERED":
      return {
        message: "Sign out and sign in again on this device, then open the video in the AOC Player.",
        retryable: false,
      };
  }
  if (error.status === 409) {
    return { message: "This video isn't available right now.", retryable: false };
  }
  return { message: "Couldn't load this video. Please try again.", retryable: true };
}
