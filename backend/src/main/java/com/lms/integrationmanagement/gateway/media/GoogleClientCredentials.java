package com.lms.integrationmanagement.gateway.media;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.integrationmanagement.config.MediaIntegrationProperties;
import com.lms.integrationmanagement.domain.PlatformOAuthApp;
import com.lms.integrationmanagement.domain.PlatformOAuthProvider;
import com.lms.integrationmanagement.gateway.MediaCredentialCipher;
import com.lms.integrationmanagement.domain.TenantOAuthApp;
import com.lms.integrationmanagement.repository.PlatformOAuthAppRepository;
import com.lms.integrationmanagement.repository.TenantOAuthAppRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Google OAuth client to use (VIDEO Phase 5c, ADR-018 Amendment 5 as
 * revised 2026-10-05). For an institute: its own app ({@code
 * tenant_oauth_app}, V75), else the platform app a Platform Admin saved
 * ({@code platform_oauth_app}, V74), else the {@code GOOGLE_OAUTH_CLIENT_ID}
 * / {@code _SECRET} environment variables. Read on every use, so a save takes
 * effect without a restart. The tenant id always comes from a trusted source
 * (the session, a connection row or the server-side OAuth state).
 */
@Component
public class GoogleClientCredentials {

	private static final Logger log = LoggerFactory.getLogger(GoogleClientCredentials.class);

	/** Binds the stored ciphertext to this row's purpose. */
	public static final String SECRET_AAD = "platform-oauth-app:GOOGLE";

	public enum Source {

		/** The institute's own app, saved by its Tenant Admin (V75). */
		TENANT,
		/** Saved by a Platform Admin. */
		SAVED,
		/** The environment variables. */
		ENVIRONMENT

	}

	/** The secret never leaves this module's OAuth calls; {@code toString} hides it. */
	public record Credentials(String clientId, String clientSecret, Source source) {

		@Override
		public String toString() {
			return "Credentials[clientId=" + clientId + ", source=" + source + "]";
		}

	}

	private final PlatformOAuthAppRepository repository;

	private final TenantOAuthAppRepository tenantRepository;

	private final MediaCredentialCipher cipher;

	private final MediaIntegrationProperties.OAuthProvider environment;

	public GoogleClientCredentials(PlatformOAuthAppRepository repository, TenantOAuthAppRepository tenantRepository,
			MediaCredentialCipher cipher, MediaIntegrationProperties properties) {
		this.repository = repository;
		this.tenantRepository = tenantRepository;
		this.cipher = cipher;
		this.environment = properties.getGoogle();
	}

	/** Binds an institute's stored secret to that institute. */
	public static String tenantSecretAad(UUID tenantId) {
		return "tenant-oauth-app:" + tenantId + ":GOOGLE";
	}

	/** The institute's own app if it has one, else the platform app; empty when none is configured. */
	public Optional<Credentials> current(UUID tenantId) {
		Optional<TenantOAuthApp> own = inTenant(tenantId,
				() -> tenantRepository.findForCurrentTenant(PlatformOAuthProvider.GOOGLE));
		if (own.isPresent()) {
			return Optional.of(new Credentials(own.get().getClientId(),
					cipher.decrypt(own.get().getClientSecretCiphertext(), tenantSecretAad(tenantId)), Source.TENANT));
		}
		return current();
	}

	public boolean isConfigured(UUID tenantId) {
		try {
			return current(tenantId).isPresent();
		}
		catch (ServiceUnavailableException | IllegalStateException ex) {
			log.atWarn()
				.setMessage("google_oauth_app.credentials_unreadable")
				.addKeyValue("errorType", ex.getClass().getSimpleName())
				.log();
			return false;
		}
	}

	public Credentials require(UUID tenantId) {
		return current(tenantId)
			.orElseThrow(() -> new ServiceUnavailableException("YouTube isn't available on this platform right now."));
	}

	/**
	 * Runs a tenant-scoped read for {@code tenantId}. Inside a request or job
	 * the context must already be that tenant (a mismatch is a bug, never a
	 * reason to read another tenant); without one (the OAuth callback) it is
	 * set for the read only.
	 */
	private static <T> T inTenant(UUID tenantId, Supplier<T> read) {
		if (TenantContextHolder.isSet()) {
			if (!tenantId.equals(new TenantContextHolder().getTenantId())) {
				throw new IllegalStateException("Tenant context does not match the requested tenant");
			}
			return read.get();
		}
		TenantContextHolder.set(tenantId);
		try {
			return read.get();
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	/** The platform app (saved, else environment), or empty when neither is configured. */
	public Optional<Credentials> current() {
		Optional<PlatformOAuthApp> saved = repository.findByProvider(PlatformOAuthProvider.GOOGLE);
		if (saved.isPresent()) {
			return Optional.of(new Credentials(saved.get().getClientId(),
					cipher.decrypt(saved.get().getClientSecretCiphertext(), SECRET_AAD), Source.SAVED));
		}
		if (environment.isConfigured()) {
			return Optional.of(
					new Credentials(environment.getClientId(), environment.getClientSecret(), Source.ENVIRONMENT));
		}
		return Optional.empty();
	}

	/** Whether usable credentials exist (a saved secret that can't be decrypted counts as not configured). */
	public boolean isConfigured() {
		try {
			return current().isPresent();
		}
		catch (ServiceUnavailableException | IllegalStateException ex) {
			log.atWarn()
				.setMessage("google_oauth_app.credentials_unreadable")
				.addKeyValue("errorType", ex.getClass().getSimpleName())
				.log();
			return false;
		}
	}

	/** 503 when Google isn't configured - callers normally check {@link #isConfigured()} first. */
	public Credentials require() {
		return current()
			.orElseThrow(() -> new ServiceUnavailableException("YouTube isn't available on this platform right now."));
	}

	/** Whether the environment variables hold a client (shown to the Platform Admin as the fallback). */
	public boolean environmentConfigured() {
		return environment.isConfigured();
	}

	public String environmentClientId() {
		return environment.getClientId();
	}

}
