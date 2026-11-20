import { test, expect } from "@playwright/test";
import { ApiClientError } from "../../src/lib/api/error";
import { lessonAccessMessage, playbackErrorMessage, VIDEO_NOT_AVAILABLE } from "../../src/components/player/playback-errors";

const refusal = (status: number, code: string) =>
  new ApiClientError({ code, message: "server text that must never be shown", fieldErrors: [] }, status);

test.describe("playback refusal copy (web player and AOC Player launcher share it)", () => {
  test("lesson-access refusals explain why instead of a generic failure, and never echo the server", () => {
    for (const [code, expected] of [
      ["EXPIRED", /lesson has ended/],
      ["NOT_STARTED", /hasn't started yet/],
      ["PAYMENT_REQUIRED", /needs a payment/],
      ["PAYMENT_PENDING", /awaiting confirmation/],
      ["NOT_ENROLLED", /Enroll in this course/],
      ["DEVICE_NOT_AUTHORIZED", /registered devices/],
    ] as const) {
      const copy = playbackErrorMessage(refusal(403, code));
      expect(copy.message).toMatch(expected);
      expect(copy.message).not.toContain("server text");
      expect(copy.retryable).toBe(false);
      expect(lessonAccessMessage(refusal(403, code))).toBe(copy.message);
    }
  });

  test("other 403/404 refusals keep the fixed anti-enumeration copy", () => {
    expect(playbackErrorMessage(refusal(403, "FORBIDDEN")).message).toBe(VIDEO_NOT_AVAILABLE);
    expect(playbackErrorMessage(refusal(404, "NOT_FOUND")).message).toBe(VIDEO_NOT_AVAILABLE);
    expect(lessonAccessMessage(refusal(403, "FORBIDDEN"))).toBeNull();
    expect(lessonAccessMessage(new Error("network"))).toBeNull();
  });
});
