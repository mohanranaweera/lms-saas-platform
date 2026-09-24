package com.lms.integrationmanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The generic, no-vendor-named adapter boundary {@code live-class-management}
 * is permitted to depend on for meeting-provider interaction (Wave 4 plan
 * §4/§9), mirroring {@link PaymentGatewayApi}'s exact shape - {@code
 * integration-management} owns all third-party credentials/webhooks (Zoom
 * included), per {@code .claude/rules/architecture.md}; other domains call it
 * through this interface, never a vendor SDK directly.
 */
public interface LiveClassProviderApi {

	/**
	 * Creates a meeting with the provider for an already-persisted (SCHEDULED
	 * /PENDING) {@code class_session}. Called strictly OUTSIDE any open
	 * database transaction (Wave 4 plan §4 / {@code .claude/rules/backend.md}'s
	 * "do not span a transaction across an outbound call to an external
	 * system" rule) - callers must persist the local PENDING state, commit,
	 * call this, then persist the returned reference (or failure reason) in a
	 * second, separate transaction.
	 */
	MeetingCreationResult createMeeting(UUID tenantId, UUID sessionId, String title, Instant scheduledStart,
			Instant scheduledEnd);

	/**
	 * Mints a fresh, short-lived, single-use, caller-scoped join URL - never a
	 * stable/reusable/predictable URL (.claude/rules/security.md's "Video &
	 * Session Protection"). Must be called only after the caller's own
	 * entitlement (tenant/enrollment/ownership) and session-status
	 * ({@code LIVE}) checks have already passed - this method itself performs
	 * no entitlement check, it only mints the link.
	 * @param participantRole an opaque, provider-facing role hint (e.g.
	 * {@code "HOST"}/{@code "PARTICIPANT"}) - never used for authorization
	 * decisions on this side, those are already complete by the time this is
	 * called.
	 */
	ShortLivedJoinLink getJoinUrl(String providerReference, UUID tenantId, String participantRole, String displayName);

	/**
	 * Mints a fresh, short-lived signed playback reference for a completed
	 * recording - never a stable/reusable/predictable URL, same rule as
	 * {@link #getJoinUrl}. Must be called only after the caller's own
	 * entitlement check and the session's {@code COMPLETED}/recording-{@code
	 * AVAILABLE} state have already been verified by the caller.
	 */
	ShortLivedPlaybackLink getRecordingPlaybackUrl(String providerRecordingReference, UUID tenantId);

	/**
	 * Best-effort cancellation of a previously-created meeting (e.g. when a
	 * {@code class_session} is cancelled). Never throws for an
	 * already-cancelled/nonexistent provider reference - cancellation is
	 * idempotent from the caller's perspective.
	 */
	void cancelMeeting(String providerReference);

	/**
	 * Verifies the provider's signature over the raw webhook request body.
	 * Must be called, and must pass, before any state change is persisted
	 * from a webhook delivery - a hard gate, never a best-effort check (per
	 * {@code .claude/rules/security.md}'s webhook-verification requirement).
	 */
	boolean verifySignature(String rawBody, String signatureHeader);

}
