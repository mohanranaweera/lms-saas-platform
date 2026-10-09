# Playback Authorization — Phase 7 WEB (B3-only)

## Scope

Phase 7 adds authenticated WEB playback authorization for LessonMedia. Native-player exchange,
launch codes, installation keys, pairing, and unauthenticated playback exchange are deferred until
ADR-025/B4 is approved.

YouTube Standard is **LMS-gated but not fully protected**. The WEB descriptor contains the YouTube
provider video ID required by the documented player integration; anyone who obtains an unlisted
YouTube ID can still play it directly on YouTube. The LMS never extracts googlevideo URLs or claims
that YouTube mode prevents sharing/capture.

## `POST /api/v1/playback/sessions`

Requires an authenticated Student JWT. Body:

```json
{ "lessonMediaId": "<uuid>" }
```

The server resolves tenant, LessonMedia, lesson/course ownership, enrollment/payment-derived access,
visibility, availability window, asset status, provider integration state, and the platform playback
flag. Tenant and role are never accepted from the request body.

Success (`201`). Phase 8 added `channel` (`WEB` | `NATIVE`) and `launch`. WEB:

```json
{
  "sessionId": null,
  "channel": "WEB",
  "expiresAt": "2026-10-06T12:05:00Z",
  "launch": null,
  "descriptor": {
    "sessionId": null,
    "provider": "YOUTUBE",
    "providerVideoId": "provider-video-id",
    "signedUrl": null,
    "expiresAt": "2026-10-06T12:05:00Z",
    "title": "Lesson video",
    "durationSeconds": 120,
    "watermarkText": null,
    "allowSeeking": true,
    "allowDownload": false
  }
}
```

When the tenant requires the AOC Player (`native_player_required`, Phase 8) the answer is NATIVE —
no descriptor, a one-time launch link, for YouTube and INTERNAL media alike:

```json
{
  "sessionId": "…",
  "channel": "NATIVE",
  "expiresAt": "2026-10-07T12:00:45Z",
  "descriptor": null,
  "launch": { "url": "aocplayer://play?code=…", "expiresAt": "2026-10-07T12:00:45Z" }
}
```

The browser opens `launch.url` from the click and polls `GET /api/v1/playback/sessions/{id}/status`;
the player claims the session through `POST /api/v1/player/exchange` (see `aoc-player.md`). If the
tenant requires the player but the platform switched it off: `409 NATIVE_PLAYER_UNAVAILABLE`. A Student
with no active registered device on this browser session: `409 PLAYER_DEVICE_NOT_REGISTERED`. A
Student with no ACTIVE AOC Player on that device (never paired, removed, or revoked with the device)
gets a launch link that also carries a one-time pairing code, `aocplayer://play?code=…&pair=…`
(ADR-025 Amendment 2); the player pairs and plays in one step. A Student whose watch time for the
video is used up: `409 PLAYBACK_WATCH_LIMIT`, before anything is issued.

`providerVideoId` is only emitted in the authenticated WEB playback descriptor. Credentials,
connection IDs, storage keys, LMS refresh tokens, native launch codes, and installation secrets are
never returned.

The descriptor is currently WEB-only. INTERNAL assets remain on the legacy
`POST /api/v1/videos/{id}/playback-sessions` signed-object-storage path until the provider-aware WEB
player migration is completed. That legacy endpoint refuses YouTube assets with `409 CONFLICT`
before creating a session, counting a view or signing anything. When the tenant requires the AOC
Player it also refuses Students with `409 NATIVE_PLAYER_REQUIRED` (Phase 8).

## Errors

| Situation | Response |
|---|---|
| Missing/invalid authentication | `401` |
| Non-student caller | `403 PLAYBACK_NOT_AUTHORIZED` |
| Unknown/foreign lesson media, hidden media, deleted asset | `404` generic not-found |
| Missing/expired enrollment or inaccessible course | `404` generic not-found |
| Asset not READY | `409 MEDIA_NOT_READY` |
| Before the material's `availableFromAt` / after its `expiryAt` | `409 CONFLICT` |
| INTERNAL (platform-storage) asset — use the legacy endpoint | `409 CONFLICT` |
| YouTube WEB flag disabled | `409 VIDEO_PLAYBACK_DISABLED` |
| Disconnected/platform-disabled YouTube integration | `409 VIDEO_PROVIDER_DISABLED_BY_PLATFORM` |
| Missing provider video ID | `404` generic not-found |

## Security and tenancy

- Tenant identity comes from the trusted authenticated `TenantContext`.
- LessonMedia and asset lookups are structural tenant-scoped reads.
- The student’s current entitlement is checked at authorization time; later playback lifecycle
  checks must revalidate session/student/tenant ownership.
- No B4/native unauthenticated endpoint exists in this phase (proposed separately in
  `docs/adr/ADR-025-native-secure-player.md`).
- Covered by `PlaybackAuthorizationIntegrationTest` (Testcontainers), including cross-tenant
  T8 (a student of tenant B addressing tenant A's LessonMedia → 404, no video id leaked) and T12
  (tenant id injected via query, header and body is ignored).

## Frontend (Phase 7 WEB)

- Student lesson materials page
  (`(student)/student/courses/[courseId]/modules/[moduleId]/lessons/[lessonId]/materials`): a `VIDEO`
  material's provider comes from `GET /courses/{courseId}/lessons/{lessonId}/media` (fetched only
  when the lesson has a `VIDEO` material). `YOUTUBE` → `components/player/youtube-lesson-player.tsx`;
  anything else, and every `RECORDING`, → the legacy `SecureVideoPlayer`.
- The YouTube player calls this endpoint only when the Student opens the video
  (`lib/api/playback.ts` → `useCreateWebPlayback`), sends `{lessonMediaId}` only, and embeds
  `https://www.youtube-nocookie.com/embed/{providerVideoId}` after checking the id is an 11-character
  YouTube id. Nothing is drawn over the player; a `watermarkText`, when present, is a band below it.
- Copy: 403/404 → fixed "This video isn't available…" (never the backend message, no retry);
  `MEDIA_NOT_READY` and `VIDEO_PLAYBACK_DISABLED` → their own messages with "Try again";
  `VIDEO_PROVIDER_DISABLED_BY_PLATFORM` and other 409s → "isn't available right now". The page says
  "This video plays through YouTube." and never calls YouTube mode protected.
- Playwright: `e2e/student-youtube-playback.spec.ts` (8 tests, mocked backend).
