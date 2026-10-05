package com.lms.integrationmanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.PlatformOAuthApp;
import com.lms.integrationmanagement.domain.PlatformOAuthAppChange;
import com.lms.integrationmanagement.domain.PlatformOAuthProvider;
import com.lms.integrationmanagement.gateway.MediaCredentialCipher;
import com.lms.integrationmanagement.gateway.media.GoogleClientCredentials;
import com.lms.integrationmanagement.gateway.media.YouTubeOAuthClient;
import com.lms.integrationmanagement.gateway.media.YouTubeOAuthClient.ClientCheck;
import com.lms.integrationmanagement.repository.PlatformOAuthAppChangeRepository;
import com.lms.integrationmanagement.repository.PlatformOAuthAppRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Platform Admin → the platform's Google OAuth app (VIDEO Phase 5c, ADR-018
 * Amendment 5). One app for the whole platform; institutes only click
 * Connect. The secret is write-only: encrypted on save, never returned,
 * logged or put in the history. Every save / removal is recorded in the
 * append-only {@code platform_oauth_app_change} and the SECURITY log.
 */
@Service
public class GoogleOAuthAppService {

	private static final Logger security = LoggerFactory.getLogger("SECURITY");

	static final int MAX_LENGTH = 255;

	/** Google web client ids look like {@code 1234-abc.apps.googleusercontent.com}. */
	private static final Pattern CLIENT_ID = Pattern.compile("^[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com$");

	private static final Pattern NO_WHITESPACE = Pattern.compile("^\\S+$");

	public enum Source {

		SAVED, ENVIRONMENT, NOT_CONFIGURED

	}

	public record ChangeView(String action, String clientIdBefore, String clientIdAfter, boolean secretChanged,
			Instant occurredAt) {

	}

	/**
	 * @param clientId the effective client id (public - Google shows it in every consent URL)
	 * @param secretSaved whether a secret is saved on this screen (its value is never returned)
	 * @param encryptionAvailable false = the credential encryption key is missing, nothing can be saved
	 */
	public record GoogleOAuthAppView(Source source, String clientId, boolean secretSaved,
			boolean environmentConfigured, String redirectUri, boolean encryptionAvailable, Instant updatedAt,
			List<ChangeView> history) {

	}

	public record VerifyView(ClientCheck result, Source source, String message) {

	}

	private final PlatformOAuthAppRepository repository;

	private final PlatformOAuthAppChangeRepository changes;

	private final MediaCredentialCipher cipher;

	private final GoogleClientCredentials credentials;

	private final YouTubeOAuthClient youTube;

	private final OAuthConnectFlowService connectFlow;

	public GoogleOAuthAppService(PlatformOAuthAppRepository repository, PlatformOAuthAppChangeRepository changes,
			MediaCredentialCipher cipher, GoogleClientCredentials credentials, YouTubeOAuthClient youTube,
			OAuthConnectFlowService connectFlow) {
		this.repository = repository;
		this.changes = changes;
		this.cipher = cipher;
		this.credentials = credentials;
		this.youTube = youTube;
		this.connectFlow = connectFlow;
	}

	@Transactional(readOnly = true)
	public GoogleOAuthAppView view() {
		requirePlatformAdmin();
		return currentView();
	}

	/** Saves (or replaces) the client id + secret; both are always required. */
	@Transactional
	public GoogleOAuthAppView save(String clientId, String clientSecret) {
		UUID actor = requirePlatformAdmin();
		String id = (clientId == null) ? "" : clientId.trim();
		String secret = (clientSecret == null) ? "" : clientSecret.trim();
		validate(id, secret, cipher);

		Instant now = Instant.now();
		Optional<PlatformOAuthApp> existing = repository.findByProvider(PlatformOAuthProvider.GOOGLE);
		String before = existing.map(PlatformOAuthApp::getClientId).orElse(null);
		boolean secretChanged = existing.map(app -> !secret.equals(decryptQuietly(app))).orElse(true);
		PlatformOAuthApp app = existing.orElseGet(() -> new PlatformOAuthApp(PlatformOAuthProvider.GOOGLE));
		app.replace(id, cipher.encrypt(secret, GoogleClientCredentials.SECRET_AAD), actor, now);
		repository.saveAndFlush(app);
		changes.save(new PlatformOAuthAppChange(PlatformOAuthProvider.GOOGLE, PlatformOAuthAppChange.Action.SAVED,
				mask(before), mask(id), secretChanged, actor, now));
		security.atInfo()
			.setMessage("google_oauth_app.saved")
			.addKeyValue("actorId", actor)
			.addKeyValue("clientIdChanged", before != null && !before.equals(id))
			.addKeyValue("secretChanged", secretChanged)
			.log();
		return currentView();
	}

	/** Removes the saved credentials; the environment variables (if any) apply again. */
	@Transactional
	public GoogleOAuthAppView remove() {
		UUID actor = requirePlatformAdmin();
		repository.findByProvider(PlatformOAuthProvider.GOOGLE).ifPresent(app -> {
			repository.delete(app);
			repository.flush();
			changes.save(new PlatformOAuthAppChange(PlatformOAuthProvider.GOOGLE,
					PlatformOAuthAppChange.Action.REMOVED, mask(app.getClientId()), null, true, actor, Instant.now()));
			security.atInfo().setMessage("google_oauth_app.removed").addKeyValue("actorId", actor).log();
		});
		return currentView();
	}

	/**
	 * Asks Google whether the effective credentials are valid. Deliberately
	 * not transactional: it calls Google ({@code .claude/rules/backend.md}).
	 */
	public VerifyView verify() {
		requirePlatformAdmin();
		GoogleClientCredentials.Credentials current = credentials.current()
			.orElseThrow(() -> new FieldValidationException("Nothing to verify",
					List.of(new FieldError("clientId", "save a client id and secret first"))));
		ClientCheck result = youTube.verifyClient(current.clientId(), current.clientSecret(),
				connectFlow.redirectUri(MediaProvider.YOUTUBE));
		Source source = (current.source() == GoogleClientCredentials.Source.SAVED) ? Source.SAVED
				: Source.ENVIRONMENT;
		String message = verifyMessage(result);
		security.atInfo()
			.setMessage("google_oauth_app.verified")
			.addKeyValue("result", result)
			.addKeyValue("source", source)
			.log();
		return new VerifyView(result, source, message);
	}

	/** Shared by the platform and institute apps: a Google web client id and a secret without spaces. */
	static void validate(String id, String secret, MediaCredentialCipher cipher) {
		List<FieldError> errors = new ArrayList<>();
		if (id.isEmpty() || id.length() > MAX_LENGTH || !CLIENT_ID.matcher(id).matches()) {
			errors.add(new FieldError("clientId", "must be a Google OAuth client id ending in .apps.googleusercontent.com"));
		}
		if (secret.isEmpty() || secret.length() > MAX_LENGTH || !NO_WHITESPACE.matcher(secret).matches()) {
			errors.add(new FieldError("clientSecret", "is required (at most " + MAX_LENGTH + " characters, no spaces)"));
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid Google OAuth app", errors);
		}
		if (!cipher.isConfigured()) {
			throw new ServiceUnavailableException(
					"Credentials can't be stored: the platform's credential encryption key isn't set.");
		}
	}

	/** The message shown for a Google client check. */
	static String verifyMessage(ClientCheck result) {
		return switch (result) {
			case VALID -> "Google accepted this client id and secret.";
			case REJECTED -> "Google rejected this client id or secret. Check both in Google Cloud and save them again.";
			case INCONCLUSIVE -> "Google gave an unexpected answer. Try again, or try connecting a channel.";
		};
	}

	private GoogleOAuthAppView currentView() {
		Optional<PlatformOAuthApp> saved = repository.findByProvider(PlatformOAuthProvider.GOOGLE);
		boolean envConfigured = credentials.environmentConfigured();
		Source source = saved.isPresent() ? Source.SAVED : envConfigured ? Source.ENVIRONMENT : Source.NOT_CONFIGURED;
		String clientId = saved.map(PlatformOAuthApp::getClientId)
			.orElse(envConfigured ? credentials.environmentClientId() : null);
		List<ChangeView> history = changes.findTop20ByProviderOrderByOccurredAtDesc(PlatformOAuthProvider.GOOGLE)
			.stream()
			.map(c -> new ChangeView(c.getAction().name(), c.getClientIdBefore(), c.getClientIdAfter(),
					c.isSecretChanged(), c.getOccurredAt()))
			.toList();
		String redirect = connectFlow.redirectUri(MediaProvider.YOUTUBE);
		return new GoogleOAuthAppView(source, clientId, saved.isPresent(), envConfigured,
				redirect.startsWith("http") ? redirect : null, cipher.isConfigured(),
				saved.map(PlatformOAuthApp::getUpdatedAt).orElse(null), history);
	}

	private String decryptQuietly(PlatformOAuthApp app) {
		try {
			return cipher.decrypt(app.getClientSecretCiphertext(), GoogleClientCredentials.SECRET_AAD);
		}
		catch (IllegalStateException ex) {
			return null;
		}
	}

	/** {@code 123456…ntent.com} - enough to tell two clients apart in the history. */
	static String mask(String clientId) {
		if (!StringUtils.hasText(clientId)) {
			return null;
		}
		if (clientId.length() <= 16) {
			return "****";
		}
		return clientId.substring(0, 6) + "…" + clientId.substring(clientId.length() - 10);
	}

	/** Defense in depth behind the controller's {@code hasRole('PLATFORM_ADMIN')}. */
	private static UUID requirePlatformAdmin() {
		AuthenticatedPrincipalHolder.requireRole("PLATFORM_ADMIN");
		return AuthenticatedPrincipalHolder.get().userId();
	}

}
