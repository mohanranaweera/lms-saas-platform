package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code class_session_provider_event} (V49) - see that
 * migration's own header for the full rationale. This is
 * {@code integration-management}'s OWN webhook-idempotency ledger for the
 * live-class meeting-provider webhook, NOT a {@code live-class-management}
 * business entity - it deliberately does NOT implement {@code TenantOwned}
 * (its {@code tenantId} is nullable, resolved only after the referenced
 * {@code class_session} is looked up by {@code providerReference}, never
 * trusted from the payload) and its repository is a plain {@code
 * JpaRepository}, never {@code TenantAwareRepository}.
 *
 * <p>Append-only by construction: {@code
 * ClassSessionProviderEventRepository} exposes no update/delete method beyond
 * what {@code JpaRepository} provides by default, and no service ever calls
 * {@code save} a second time for the same row - {@code
 * uq_class_session_provider_event} is the schema-enforced idempotency gate
 * (a second insert for the same {@code (provider, providerEventId)} throws
 * {@code DataIntegrityViolationException}, caught by {@code
 * LiveClassWebhookController} and turned into a 200 OK no-op).
 */
@Entity
@Table(name = "class_session_provider_event")
public class ClassSessionProviderEvent extends BaseEntity {

	@Column(name = "tenant_id")
	private UUID tenantId;

	@Column(name = "provider", nullable = false, length = 10)
	private String provider;

	@Column(name = "provider_event_id", nullable = false)
	private String providerEventId;

	@Column(name = "event_type", nullable = false)
	private String eventType;

	@Column(name = "received_at", nullable = false)
	private Instant receivedAt;

	@Column(name = "processed", nullable = false)
	private boolean processed;

	protected ClassSessionProviderEvent() {
	}

	public ClassSessionProviderEvent(UUID tenantId, String provider, String providerEventId, String eventType,
			Instant receivedAt, boolean processed) {
		this.tenantId = tenantId;
		this.provider = provider;
		this.providerEventId = providerEventId;
		this.eventType = eventType;
		this.receivedAt = receivedAt;
		this.processed = processed;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public String getProvider() {
		return provider;
	}

	public String getProviderEventId() {
		return providerEventId;
	}

	public String getEventType() {
		return eventType;
	}

	public Instant getReceivedAt() {
		return receivedAt;
	}

	public boolean isProcessed() {
		return processed;
	}

	public void markProcessed() {
		this.processed = true;
	}

}
