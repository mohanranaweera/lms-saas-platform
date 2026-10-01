package com.lms.integrationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.logging.ContactMasking;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.api.MessagingChannelApi;
import com.lms.integrationmanagement.domain.TenantMessagingAccount;
import com.lms.integrationmanagement.domain.TenantMessagingAccount.Provider;
import com.lms.integrationmanagement.gateway.MediaCredentialCipher;
import com.lms.integrationmanagement.gateway.messaging.MessagingProviderAdapter;
import com.lms.integrationmanagement.repository.TenantMessagingAccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Tenant SMS/WhatsApp provider accounts and the {@link MessagingChannelApi}
 * implementation (Wave 11, {@code wave-11-plan.md} §2.2-§2.3).
 *
 * <ul>
 * <li>Tenant Admin ({@link DomainArea#INTEGRATIONS} CREATE_EDIT) configures,
 * replaces, disables and test-sends; VIEW roles see status and a masked
 * hint only.</li>
 * <li>Credentials are encrypted (AES-256-GCM, AAD bound to the row) before
 * they are persisted, decrypted only in memory for one send, and never
 * returned, logged or written to the audit log.</li>
 * <li>{@link #send} runs for the current tenant only and makes the outbound
 * call outside any transaction.</li>
 * </ul>
 */
@Service
public class MessagingAccountService implements MessagingChannelApi {

	private static final Logger log = LoggerFactory.getLogger(MessagingAccountService.class);

	private static final String TARGET_ENTITY = "tenant_messaging_account";

	private static final Pattern SENDER_ID = Pattern.compile("^[A-Za-z0-9 +._-]{1,40}$");

	private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");

	private static final int MAX_CREDENTIAL_LENGTH = 512;

	private static final String MASK = "••••";

	private final TenantMessagingAccountRepository repository;

	private final MediaCredentialCipher cipher;

	private final Map<Provider, MessagingProviderAdapter> adapters;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final ObjectMapper objectMapper;

	public MessagingAccountService(TenantMessagingAccountRepository repository, MediaCredentialCipher cipher,
			List<MessagingProviderAdapter> adapterList, PermissionCheckService permissionCheckService,
			AuditLogApi auditLogApi, TenantContext tenantContext, ObjectMapper objectMapper) {
		this.repository = repository;
		this.cipher = cipher;
		this.adapters = new EnumMap<>(Provider.class);
		adapterList.forEach(adapter -> this.adapters.put(adapter.provider(), adapter));
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.objectMapper = objectMapper;
	}

	/** What the settings page shows for one channel - never a secret. */
	public record MessagingAccountView(String channel, boolean configured, UUID id, String provider, String senderId,
			String credentialHint, String status, Instant disabledAt, Instant updatedAt, boolean operational) {

	}

	public record ProviderOption(String provider, List<String> channels, List<String> credentialKeys) {

	}

	public record MessagingSettingsView(List<MessagingAccountView> accounts, List<ProviderOption> availableProviders) {

	}

	public record TestSendResult(String channel, String destination, String providerMessageId) {

	}

	// ------------------------------------------------------------------
	// Tenant Admin configuration
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public MessagingSettingsView settings() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.VIEW);
		List<MessagingAccountView> accounts = new ArrayList<>();
		for (Channel channel : Channel.values()) {
			accounts.add(repository.findLatest(channel)
				.map(this::toView)
				.orElse(new MessagingAccountView(channel.name(), false, null, null, null, null, "NOT_CONFIGURED",
						null, null, false)));
		}
		List<ProviderOption> providers = adapters.values()
			.stream()
			.filter(MessagingProviderAdapter::isEnabled)
			.map(adapter -> new ProviderOption(adapter.provider().name(),
					adapter.supportedChannels().stream().map(Enum::name).sorted().toList(),
					adapter.requiredCredentialKeys().stream().sorted().toList()))
			.toList();
		return new MessagingSettingsView(accounts, providers);
	}

	/**
	 * Saves (or replaces) the tenant's account for {@code channel}. Credentials
	 * are always required - they are write-only, so there is nothing to keep.
	 */
	@Transactional
	public MessagingAccountView configure(Channel channel, String providerName, String senderId,
			Map<String, String> credentials) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		MessagingProviderAdapter adapter = resolveConfigurableAdapter(channel, providerName);
		String trimmedSender = (senderId == null) ? "" : senderId.trim();
		if (!SENDER_ID.matcher(trimmedSender).matches()) {
			throw new FieldValidationException("Invalid messaging account",
					List.of(new FieldError("senderId", "must be 1-40 letters, digits, spaces or + . _ -")));
		}
		Map<String, String> cleaned = validateCredentials(adapter, credentials);

		Instant now = Instant.now();
		UUID actor = IntegrationActors.currentUserId();
		repository.findActive(channel).ifPresent(previous -> {
			previous.disable(actor, now);
			repository.saveAndFlush(previous);
		});
		String primarySecret = cleaned.get(adapter.requiredCredentialKeys().stream().sorted().findFirst().orElseThrow());
		TenantMessagingAccount account = new TenantMessagingAccount(tenantContext.getTenantId(), channel,
				adapter.provider(), trimmedSender, hint(primarySecret));
		account = repository.save(account);
		account.sealCredentials(cipher.encrypt(objectMapper.writeValueAsString(cleaned), account.credentialsAad()));
		account = repository.saveAndFlush(account);

		auditLogApi.record(new AuditLogEntry(actor, "messaging.account_configured", TARGET_ENTITY, account.getId(),
				null, Map.of("channel", channel.name(), "provider", adapter.provider().name(), "senderId",
						trimmedSender, "credentialKeys", List.copyOf(cleaned.keySet()))));
		return toView(account);
	}

	@Transactional
	public MessagingAccountView disable(Channel channel) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		TenantMessagingAccount account = repository.findActive(channel)
			.orElseThrow(() -> new ConflictException("This channel is not currently configured"));
		UUID actor = IntegrationActors.currentUserId();
		account.disable(actor, Instant.now());
		repository.saveAndFlush(account);
		auditLogApi.record(new AuditLogEntry(actor, "messaging.account_disabled", TARGET_ENTITY, account.getId(),
				null, Map.of("channel", channel.name(), "provider", account.getProvider().name())));
		return toView(account);
	}

	/**
	 * Sends a fixed test message through the configured account. Not
	 * transactional: the audit row is written after the outbound call in its
	 * own write (see {@link #recordTestSend}).
	 */
	public TestSendResult testSend(Channel channel, String destination) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		String trimmed = (destination == null) ? "" : destination.replaceAll("[\\s-]", "");
		if (!PHONE.matcher(trimmed).matches()) {
			throw new FieldValidationException("Invalid test destination",
					List.of(new FieldError("destination", "must be a phone number with 7-15 digits")));
		}
		SendReceipt receipt;
		try {
			receipt = send(new OutboundMessage(channel, trimmed,
					"This is a test message from your institute's messaging settings.", null));
		}
		catch (MessagingSendException ex) {
			recordTestSend(channel, ex.getProviderAccountId(), trimmed, ex.getErrorCode());
			throw new ConflictException("The test message could not be sent (" + ex.getErrorCode() + ")");
		}
		recordTestSend(channel, receipt.providerAccountId(), trimmed, "SENT");
		return new TestSendResult(channel.name(), ContactMasking.maskPhone(trimmed), receipt.providerMessageId());
	}

	private void recordTestSend(Channel channel, UUID accountId, String destination, String outcome) {
		if (accountId == null) {
			return;
		}
		auditLogApi.record(new AuditLogEntry(IntegrationActors.currentUserId(), "messaging.test_sent",
				TARGET_ENTITY, accountId, null, Map.of("channel", channel.name(), "destination",
						ContactMasking.maskPhone(destination), "outcome", outcome)));
	}

	// ------------------------------------------------------------------
	// MessagingChannelApi (current tenant)
	// ------------------------------------------------------------------

	@Override
	public ChannelAvailability availability(Channel channel) {
		Optional<TenantMessagingAccount> active = repository.findActive(channel);
		if (active.isEmpty()) {
			return new ChannelAvailability(channel, false, null, "NOT_CONFIGURED");
		}
		MessagingProviderAdapter adapter = adapters.get(active.get().getProvider());
		if (adapter == null || !adapter.isEnabled()) {
			return new ChannelAvailability(channel, false, active.get().getProvider().name(),
					"PROVIDER_DISABLED_IN_ENVIRONMENT");
		}
		return new ChannelAvailability(channel, true, active.get().getProvider().name(), null);
	}

	@Override
	public SendReceipt send(OutboundMessage message) {
		TenantMessagingAccount account = repository.findActive(message.channel())
			.orElseThrow(() -> new MessagingSendException(MessagingSendException.CHANNEL_NOT_AVAILABLE, false, null));
		MessagingProviderAdapter adapter = adapters.get(account.getProvider());
		if (adapter == null || !adapter.isEnabled()) {
			throw new MessagingSendException(MessagingSendException.CHANNEL_NOT_AVAILABLE, false, account.getId());
		}
		Map<String, String> credentials = objectMapper
			.readValue(cipher.decrypt(account.getCredentialsCiphertext(), account.credentialsAad()),
					new TypeReference<Map<String, String>>() {
					});
		try {
			String messageId = adapter.send(message.channel(), credentials, account.getSenderId(),
					message.destination(), message.body(), message.providerTemplateName());
			return new SendReceipt(account.getId(), messageId);
		}
		catch (MessagingSendException ex) {
			throw new MessagingSendException(ex.getErrorCode(), ex.isRetryable(), account.getId());
		}
		catch (RuntimeException ex) {
			// An adapter bug or an unmapped client error: never let its message
			// (which may echo the request) escape - treat as transient.
			log.atWarn()
				.setMessage("messaging.adapter_unexpected_failure")
				.addKeyValue("provider", account.getProvider())
				.addKeyValue("reasonType", ex.getClass().getSimpleName())
				.log();
			throw new MessagingSendException(MessagingSendException.PROVIDER_TRANSIENT, true, account.getId());
		}
	}

	// ------------------------------------------------------------------

	private MessagingProviderAdapter resolveConfigurableAdapter(Channel channel, String providerName) {
		Provider provider;
		try {
			provider = Provider.valueOf((providerName == null) ? "" : providerName.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new FieldValidationException(ApiErrorCodes.MESSAGING_PROVIDER_NOT_AVAILABLE,
					"This messaging provider is not available", List.of(new FieldError("provider", "is not supported")));
		}
		MessagingProviderAdapter adapter = adapters.get(provider);
		if (adapter == null || !adapter.isEnabled() || !adapter.supportedChannels().contains(channel)) {
			throw new FieldValidationException(ApiErrorCodes.MESSAGING_PROVIDER_NOT_AVAILABLE,
					"This messaging provider is not available", List.of(new FieldError("provider",
							"is not available for " + channel.name() + " in this environment")));
		}
		return adapter;
	}

	private static Map<String, String> validateCredentials(MessagingProviderAdapter adapter,
			Map<String, String> credentials) {
		Map<String, String> given = (credentials == null) ? Map.of() : credentials;
		List<FieldError> errors = new ArrayList<>();
		Map<String, String> cleaned = new LinkedHashMap<>();
		for (String key : adapter.requiredCredentialKeys().stream().sorted().toList()) {
			String value = given.get(key);
			if (value == null || value.isBlank()) {
				errors.add(new FieldError("credentials." + key, "is required"));
			}
			else if (value.length() > MAX_CREDENTIAL_LENGTH) {
				errors.add(new FieldError("credentials." + key,
						"must be at most " + MAX_CREDENTIAL_LENGTH + " characters"));
			}
			else {
				cleaned.put(key, value.trim());
			}
		}
		for (String key : given.keySet()) {
			if (!adapter.requiredCredentialKeys().contains(key)) {
				errors.add(new FieldError("credentials", "unexpected credential field"));
				break;
			}
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid messaging credentials", errors);
		}
		return cleaned;
	}

	/** {@code ••••wxyz} only when the secret is long enough that 4 chars reveal little; else {@code ••••}. */
	static String hint(String secret) {
		if (secret == null || secret.length() < 12) {
			return MASK;
		}
		return MASK + secret.substring(secret.length() - 4);
	}

	private MessagingAccountView toView(TenantMessagingAccount account) {
		MessagingProviderAdapter adapter = adapters.get(account.getProvider());
		boolean operational = account.isActive() && adapter != null && adapter.isEnabled();
		return new MessagingAccountView(account.getChannel().name(), true, account.getId(),
				account.getProvider().name(), account.getSenderId(), account.getCredentialHint(),
				account.getStatus().name(), account.getDisabledAt(), account.getUpdatedAt(), operational);
	}

}
