package com.lms.integrationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.integrationmanagement.api.MediaConnectionStatus;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.ConnectionDisconnectedBy;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.gateway.media.ProviderIdentity;
import com.lms.integrationmanagement.repository.MediaProviderConnectionRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short, separately committed transactions around provider calls
 * (MEDIA-INT-01) - the same shape as {@code ClassSessionWriteService}: the
 * callers ({@code OAuthConnectFlowService}, {@code MediaConnectionService},
 * {@code MediaTokenService}) are NOT transactional, call the provider with no
 * transaction open, then call one of these methods to persist the outcome.
 *
 * <p>Every user-driven change writes its audit entry in the same transaction.
 * Provider-driven changes (deauthorization, a refresh rejected with
 * {@code invalid_grant}) have no platform user to name as the actor, so they
 * are written to the security log instead (as PAY-GW-01 does for
 * chargebacks).
 */
@Service
public class MediaConnectionStore {

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	static final String TARGET_ENTITY = "media_provider_connection";

	private final MediaProviderConnectionRepository repository;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public MediaConnectionStore(MediaProviderConnectionRepository repository, AuditLogApi auditLogApi,
			TenantContext tenantContext) {
		this.repository = repository;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public MediaProviderConnection require(UUID connectionId) {
		return repository.findById(connectionId).orElseThrow(() -> new NotFoundException("Connection not found"));
	}

	/**
	 * Persists a teacher's newly authorized OAuth connection, replacing any
	 * current one (marked DISCONNECTED/SYSTEM, credentials wiped). Called only
	 * from the signed-in teacher's own confirm request (security review S1),
	 * so the tenant is the request's resolved tenant.
	 */
	@Transactional
	public MediaProviderConnection saveTeacherConnection(UUID teacherUserId, MediaProvider provider,
			ProviderIdentity identity, String credentialsCiphertext, Instant accessTokenExpiresAt,
			String grantedScopes) {
		UUID tenantId = tenantContext.getTenantId();
		Instant now = Instant.now();
		UUID replacedId = null;
		MediaProviderConnection previous = repository.findCurrentForTeacher(teacherUserId, provider).orElse(null);
		if (previous != null) {
			replacedId = previous.getId();
			previous.disconnect(ConnectionDisconnectedBy.SYSTEM, "Replaced by a new connection", now);
			repository.saveAndFlush(previous);
		}
		MediaProviderConnection saved = repository.saveAndFlush(MediaProviderConnection.teacherOAuth(tenantId,
				provider, teacherUserId, identity.externalId(), identity.label(), identity.accountType(),
				credentialsCiphertext, accessTokenExpiresAt, grantedScopes, now));
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("provider", provider.name());
		metadata.put("scope", "TEACHER");
		metadata.put("ownerUserId", teacherUserId.toString());
		metadata.put("externalAccountId", MediaConnectionViews.mask(identity.externalId()));
		if (identity.accountType() != null) {
			metadata.put("accountType", identity.accountType());
		}
		if (replacedId != null) {
			metadata.put("replacedConnectionId", replacedId.toString());
		}
		auditLogApi.record(new AuditLogEntry(teacherUserId, "media_integration.connected", TARGET_ENTITY,
				saved.getId(), null, metadata));
		return saved;
	}

	/**
	 * Saves a Tenant Admin's DynTube/Bunny account (credentials already
	 * verified with the provider), replacing any current one.
	 */
	@Transactional
	public MediaProviderConnection saveTenantConnection(UUID tenantId, UUID actorId, MediaProvider provider,
			ProviderIdentity identity, String credentialsCiphertext, String settingsJson, boolean secretChanged) {
		Instant now = Instant.now();
		MediaProviderConnection previous = repository.findCurrentForTenant(provider).orElse(null);
		if (previous != null) {
			previous.disconnect(ConnectionDisconnectedBy.TENANT_ADMIN, "Replaced by new account settings", now);
			repository.saveAndFlush(previous);
		}
		MediaProviderConnection saved = repository.saveAndFlush(MediaProviderConnection.tenantApiKey(tenantId,
				provider, identity.externalId(), identity.label(), credentialsCiphertext, settingsJson, now));
		auditLogApi.record(new AuditLogEntry(actorId, "media_integration.connected", TARGET_ENTITY, saved.getId(),
				null, Map.of("provider", provider.name(), "scope", "TENANT", "secretChanged", secretChanged)));
		return saved;
	}

	/** User-driven disconnect (owner or Tenant Admin), audited. */
	@Transactional
	public MediaProviderConnection disconnect(UUID connectionId, UUID actorId, ConnectionDisconnectedBy by,
			String reason) {
		MediaProviderConnection connection = require(connectionId);
		connection.disconnect(by, reason, Instant.now());
		MediaProviderConnection saved = repository.saveAndFlush(connection);
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("provider", connection.getProvider().name());
		metadata.put("scope", connection.getScope().name());
		metadata.put("disconnectedBy", by.name());
		if (connection.getOwnerUserId() != null) {
			metadata.put("ownerUserId", connection.getOwnerUserId().toString());
		}
		auditLogApi.record(new AuditLogEntry(actorId, "media_integration.disconnected", TARGET_ENTITY,
				connectionId, reason, metadata));
		return saved;
	}

	@Transactional
	public MediaProviderConnection markVerified(UUID connectionId, ProviderIdentity identity) {
		MediaProviderConnection connection = require(connectionId);
		connection.markVerified(Instant.now(), identity == null ? null : identity.label(),
				identity == null ? null : identity.accountType());
		return repository.saveAndFlush(connection);
	}

	@Transactional
	public MediaProviderConnection recordError(UUID connectionId, String sanitisedError) {
		MediaProviderConnection connection = require(connectionId);
		connection.recordError(sanitisedError);
		return repository.saveAndFlush(connection);
	}

	/** The provider rejected the stored credentials - security-logged (no user actor). */
	@Transactional
	public MediaProviderConnection markNeedsReconnect(UUID connectionId, String sanitisedError) {
		MediaProviderConnection connection = require(connectionId);
		MediaConnectionStatus before = connection.getStatus();
		connection.markNeedsReconnect(sanitisedError);
		MediaProviderConnection saved = repository.saveAndFlush(connection);
		if (before != saved.getStatus()) {
			securityLog.atWarn()
				.setMessage("media_integration.needs_reconnect")
				.addKeyValue("tenantId", connection.getTenantId())
				.addKeyValue("connectionId", connectionId)
				.addKeyValue("provider", connection.getProvider())
				.log();
		}
		return saved;
	}

	@Transactional
	public void storeRefreshedTokens(UUID connectionId, String credentialsCiphertext, Instant accessTokenExpiresAt) {
		MediaProviderConnection connection = require(connectionId);
		connection.replaceTokens(credentialsCiphertext, accessTokenExpiresAt);
		repository.saveAndFlush(connection);
	}

	/**
	 * ADR-018 §5: Zoom says the user removed our app. Every live connection
	 * for that Zoom user, in any tenant, is disconnected - and nothing else.
	 * Runs from the public webhook (no request tenant context); each row is
	 * saved under its own tenant.
	 * @return how many connections were disconnected
	 */
	@Transactional
	public int disconnectDeauthorizedZoomUser(String zoomUserId) {
		if (zoomUserId == null || zoomUserId.isBlank()) {
			return 0;
		}
		List<MediaProviderConnection> matches = repository.findLiveAcrossTenantsByProviderAndExternalAccountId(
				MediaProvider.ZOOM, zoomUserId, MediaConnectionStatus.DISCONNECTED);
		Instant now = Instant.now();
		for (MediaProviderConnection connection : matches) {
			TenantContextHolder.set(connection.getTenantId());
			try {
				connection.disconnect(ConnectionDisconnectedBy.PROVIDER, "The Zoom app was removed from this account",
						now);
				repository.saveAndFlush(connection);
			}
			finally {
				TenantContextHolder.clear();
			}
			securityLog.atWarn()
				.setMessage("media_integration.zoom_deauthorized")
				.addKeyValue("tenantId", connection.getTenantId())
				.addKeyValue("connectionId", connection.getId())
				.log();
		}
		return matches.size();
	}

}
