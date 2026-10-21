package com.lms.integrationmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One save / removal of a platform OAuth app ({@code platform_oauth_app_change},
 * V74). Append-only (DB trigger). Client ids are masked; the secret never
 * appears, only whether it changed.
 */
@Entity
@Table(name = "platform_oauth_app_change")
public class PlatformOAuthAppChange extends BaseEntity {

	public enum Action {

		SAVED, REMOVED

	}

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, updatable = false)
	private PlatformOAuthProvider provider;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false, updatable = false)
	private Action action;

	@Column(name = "client_id_before", updatable = false)
	private String clientIdBefore;

	@Column(name = "client_id_after", updatable = false)
	private String clientIdAfter;

	@Column(name = "secret_changed", nullable = false, updatable = false)
	private boolean secretChanged;

	@Column(name = "actor_id", nullable = false, updatable = false)
	private UUID actorId;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	protected PlatformOAuthAppChange() {
	}

	public PlatformOAuthAppChange(PlatformOAuthProvider provider, Action action, String clientIdBefore,
			String clientIdAfter, boolean secretChanged, UUID actorId, Instant occurredAt) {
		this.provider = provider;
		this.action = action;
		this.clientIdBefore = clientIdBefore;
		this.clientIdAfter = clientIdAfter;
		this.secretChanged = secretChanged;
		this.actorId = actorId;
		this.occurredAt = occurredAt;
	}

	public PlatformOAuthProvider getProvider() {
		return provider;
	}

	public Action getAction() {
		return action;
	}

	public String getClientIdBefore() {
		return clientIdBefore;
	}

	public String getClientIdAfter() {
		return clientIdAfter;
	}

	public boolean isSecretChanged() {
		return secretChanged;
	}

	public UUID getActorId() {
		return actorId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

}
