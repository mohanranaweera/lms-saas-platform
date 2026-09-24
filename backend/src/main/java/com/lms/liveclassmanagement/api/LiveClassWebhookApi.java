package com.lms.liveclassmanagement.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The narrow write surface {@code integration-management}'s {@code
 * LiveClassWebhookController} is permitted to call into after it has already
 * (1) verified the provider's signature, and (2) inserted the idempotency-
 * ledger row for this delivery - per {@code .claude/rules/architecture.md}
 * ("a module may depend only on another module's api package") and the same
 * shape {@code payment-management}'s {@code PaymentConfirmationApi} already
 * establishes for its own webhook. {@code integration-management} must never
 * import {@code liveclassmanagement.domain}/{@code repository} directly -
 * only this interface.
 *
 * <p>Every method here resolves tenant identity ITSELF, from the platform's
 * own {@code class_session} row matched by {@code providerReference} - never
 * from a caller-supplied tenant id (there is none in this interface's
 * signature, by design) - mirroring {@code PaymentConfirmationApi
 * #confirmByGatewayReference}'s identical webhook-trust posture.
 *
 * <p>Every method is a graceful no-op (never throws) for an unresolvable
 * {@code providerReference} - an unknown/malformed webhook event must never
 * surface as a 500, per {@code .claude/rules/security.md}'s
 * webhook-verification requirement and Wave 4 plan §8's "unknown event type
 * handled gracefully" test requirement.
 */
public interface LiveClassWebhookApi {

	/**
	 * Resolves the tenant that owns the {@code class_session} matched by
	 * {@code providerReference} - called by {@code LiveClassWebhookController}
	 * BEFORE inserting the idempotency-ledger row, so {@code
	 * class_session_provider_event.tenant_id} is populated wherever
	 * resolvable (V49's migration header: nullable only for a genuinely
	 * unresolvable/malformed event). {@link Optional#empty()} for an
	 * unresolvable/malformed reference - never throws.
	 */
	Optional<UUID> resolveTenantId(String providerReference);

	/**
	 * Handles a {@code "recording.completed"} event - creates or updates the
	 * single {@code class_session_recording} row for the resolved session (at
	 * most one per session, per V49's {@code
	 * uq_class_session_recording_tenant_session} constraint).
	 */
	void handleRecordingCompleted(String providerReference, String providerRecordingReference,
			Integer durationSeconds);

	/**
	 * Handles a {@code "session.attendance_synced"} event - publishes {@link
	 * ClassSessionAttendanceSyncedEvent} only. No listener exists yet in
	 * {@code attendance-management} this wave (Wave 4 plan §11 / PAR-10-03 -
	 * full consumption is deferred to Wave 8).
	 */
	void handleAttendanceSynced(String providerReference);

}
