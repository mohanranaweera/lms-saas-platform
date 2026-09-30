package com.lms.notificationmanagement.service;

import com.lms.identityaccessservice.api.PasswordTokenIssuingApi;
import com.lms.identityaccessservice.api.PasswordTokenIssuingApi.IssuedPasswordToken;
import com.lms.identityaccessservice.api.PasswordTokenPurpose;
import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantSummary;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Value;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.MessagingProviderApi;
import com.lms.notificationmanagement.domain.NotificationOutbox;
import com.lms.notificationmanagement.domain.NotificationTemplate;
import com.lms.notificationmanagement.repository.NotificationTemplateRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dispatches exactly one claimed {@code notification_outbox} row: resolves
 * the recipient's email, resolves the {@code (tenant_id, template_key)}
 * template, renders subject/body, sends the email, and (on success) marks the
 * row {@code SENT} and inserts one {@code in_app_notification} row.
 *
 * <h2>Transaction shape - three phases, no transaction spans the SMTP call</h2>
 * This method is deliberately NOT itself {@code @Transactional}. It
 * orchestrates three phases, each its own short transaction (or none) on a
 * separate injected bean, per {@code .claude/rules/backend.md}'s "do not span
 * a transaction across an outbound call to an external system" rule:
 * <ol>
 * <li>{@link NotificationDispatchClaimService#claim(UUID)} - claims the row
 * ({@code PENDING -> SENDING}) and commits, releasing the {@code FOR UPDATE
 * SKIP LOCKED} lock, before anything below runs.</li>
 * <li>Recipient resolution, template lookup/render, and the {@link
 * MessagingProviderApi#sendEmail} call itself - no open transaction here at
 * all (the template repository read is its own short implicit
 * transaction).</li>
 * <li>{@link NotificationDispatchFinalizeService#markSent}/{@link
 * NotificationDispatchFinalizeService#markFailed} - commits the terminal
 * status (and, on success, the {@code in_app_notification} row) in one final
 * short transaction, again with no SMTP call inside it.</li>
 * </ol>
 * The previous design held one transaction (and the claimed row's DB lock)
 * open for the entire SMTP call; that was a disclosed, reviewed exception to
 * the rule above (see {@code docs/requirements/open-decisions.md} §22), but
 * still carried a real duplicate-send risk if a later statement in that same
 * transaction failed after the email had already been sent. This three-phase
 * design removes that transaction/lock-holding violation while preserving
 * the same "two concurrent dispatch attempts on the same row never both
 * send" guarantee - see {@link NotificationDispatchClaimService}'s javadoc
 * for the (much narrower, timeout-bounded) residual gap this trades in.
 *
 * <h2>{@code TenantContextHolder} discipline</h2>
 * Same set-in-try/clear-in-finally shape as {@link NotificationOutboxService}
 * (see its javadoc for the full rationale) - the claimed row's own {@code
 * tenantId} column is the trusted source, set explicitly, never an
 * inherited/ambient value; {@code UserProvisioningApi#findTenantUserSummaries}
 * is only safe to call once this is set, since it resolves against whatever
 * tenant is currently on {@code TenantContextHolder}.
 */
@Service
public class NotificationDispatchService {

	private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

	private final NotificationTemplateRepository notificationTemplateRepository;

	private final UserProvisioningApi userProvisioningApi;

	private final MessagingProviderApi messagingProviderApi;

	private final NotificationTemplateRenderer notificationTemplateRenderer;

	private final ObjectMapper objectMapper;

	private final NotificationDispatchClaimService notificationDispatchClaimService;

	private final NotificationDispatchFinalizeService notificationDispatchFinalizeService;

	private final PasswordTokenIssuingApi passwordTokenIssuingApi;

	private final TenantLookupApi tenantLookupApi;

	private final NotificationTemplateSeedingService templateSeedingService;

	/** AUTH-PWD-01: tenant site origin for account-setup / reset links, {subdomain} from the tenant record. */
	private final String tenantUrlTemplate;

	public NotificationDispatchService(NotificationTemplateRepository notificationTemplateRepository,
			UserProvisioningApi userProvisioningApi, MessagingProviderApi messagingProviderApi,
			NotificationTemplateRenderer notificationTemplateRenderer, ObjectMapper objectMapper,
			NotificationDispatchClaimService notificationDispatchClaimService,
			NotificationDispatchFinalizeService notificationDispatchFinalizeService,
			PasswordTokenIssuingApi passwordTokenIssuingApi, TenantLookupApi tenantLookupApi,
			NotificationTemplateSeedingService templateSeedingService,
			@Value("${app.public.tenant-url-template:}") String tenantUrlTemplate) {
		this.notificationTemplateRepository = notificationTemplateRepository;
		this.userProvisioningApi = userProvisioningApi;
		this.messagingProviderApi = messagingProviderApi;
		this.notificationTemplateRenderer = notificationTemplateRenderer;
		this.objectMapper = objectMapper;
		this.notificationDispatchClaimService = notificationDispatchClaimService;
		this.notificationDispatchFinalizeService = notificationDispatchFinalizeService;
		this.passwordTokenIssuingApi = passwordTokenIssuingApi;
		this.tenantLookupApi = tenantLookupApi;
		this.templateSeedingService = templateSeedingService;
		this.tenantUrlTemplate = tenantUrlTemplate;
	}

	public void dispatchOne(UUID outboxId) {
		Optional<NotificationOutbox> maybeRow = notificationDispatchClaimService.claim(outboxId);
		if (maybeRow.isEmpty()) {
			// Already claimed/handled by another instance's concurrent run,
			// or no longer PENDING - not an error.
			return;
		}
		NotificationOutbox row = maybeRow.get();
		try {
			TenantContextHolder.set(row.getTenantId());

			List<TenantUserSummary> recipients = userProvisioningApi
				.findTenantUserSummaries(Set.of(row.getRecipientUserId()));
			if (recipients.isEmpty()) {
				throw new RecipientNotResolvedException(
						"No tenant_user found for recipientUserId " + row.getRecipientUserId());
			}
			String recipientEmail = recipients.get(0).email();

			PasswordTokenPurpose credentialPurpose = credentialPurpose(row.getEventType());
			if (credentialPurpose != null) {
				templateSeedingService.seedCredentialTemplates(row.getTenantId());
			}

			NotificationTemplate template = notificationTemplateRepository.findByTemplateKey(row.getEventType().name())
				.orElseThrow(() -> new TemplateNotFoundException(
						"No notification_template found for templateKey " + row.getEventType().name()));

			Map<String, Object> payloadMap = objectMapper.readValue(row.getPayload(),
					new TypeReference<Map<String, Object>>() {
					});

			if (credentialPurpose != null) {
				payloadMap.putAll(credentialVariables(row, credentialPurpose));
			}

			RenderedNotification rendered = notificationTemplateRenderer.render(template, payloadMap);

			messagingProviderApi.sendEmail(recipientEmail, rendered.subject(), rendered.body());

			// AUTH-PWD-01: the in-app copy of a credential email never contains the
			// link - the raw token must exist only in the email itself.
			String inAppBody = (credentialPurpose == null) ? rendered.body()
					: "We've emailed you a link to "
							+ (credentialPurpose == PasswordTokenPurpose.ACCOUNT_SETUP ? "set up your account"
									: "reset your password")
							+ ". For your security the link isn't shown here.";
			notificationDispatchFinalizeService.markSent(row.getId(), row.getTenantId(), row.getRecipientUserId(),
					rendered.subject(), inAppBody);
		}
		catch (RuntimeException e) {
			logDispatchFailure(row, e);
			notificationDispatchFinalizeService.markFailed(row.getId(), row.getTenantId());
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	private static PasswordTokenPurpose credentialPurpose(NotificationEventType type) {
		return switch (type) {
			case ACCOUNT_SETUP_INVITE -> PasswordTokenPurpose.ACCOUNT_SETUP;
			case PASSWORD_RESET -> PasswordTokenPurpose.PASSWORD_RESET;
			default -> null;
		};
	}

	/**
	 * Issues the one-time token NOW (ADR-020: never stored raw) and builds the
	 * link on the tenant's own site (primary custom domain or subdomain). Fails closed when the tenant URL
	 * template isn't configured.
	 */
	private Map<String, Object> credentialVariables(NotificationOutbox row, PasswordTokenPurpose purpose) {
		if (tenantUrlTemplate == null || tenantUrlTemplate.isBlank()) {
			throw new IllegalStateException("app.public.tenant-url-template (TENANT_URL_TEMPLATE) is not configured");
		}
		// TEN-DOM-01: the tenant's primary custom domain when it has one.
		String origin = tenantLookupApi.tenantOrigin(row.getTenantId(), tenantUrlTemplate)
			.orElseThrow(() -> new IllegalStateException("Tenant not found"));
		String tenantName = tenantLookupApi.resolveTenantSummaries(Set.of(row.getTenantId()))
			.stream()
			.findFirst()
			.map(TenantSummary::name)
			.orElse("your institute");
		IssuedPasswordToken issued = passwordTokenIssuingApi.issueForEmail(row.getRecipientUserId(), purpose);
		String path = (purpose == PasswordTokenPurpose.ACCOUNT_SETUP) ? "/account-setup" : "/reset-password";
		Map<String, Object> variables = new LinkedHashMap<>();
		// Fragment, not query: browsers never send "#..." to any server, so the token
		// can't end up in proxy or web-server access logs (security review M-2).
		variables.put("link", stripTrailingSlash(origin) + path + "#token="
				+ URLEncoder.encode(issued.rawToken(), StandardCharsets.UTF_8));
		variables.put("tenantName", tenantName);
		variables.put("expiresIn", (purpose == PasswordTokenPurpose.ACCOUNT_SETUP) ? "72 hours" : "60 minutes");
		return variables;
	}

	private void logDispatchFailure(NotificationOutbox row, RuntimeException e) {
		log.atWarn()
			.setMessage("notification.dispatch_failed")
			.addKeyValue("tenantId", row.getTenantId())
			.addKeyValue("outboxId", row.getId())
			.addKeyValue("eventType", row.getEventType())
			.addKeyValue("reasonType", e.getClass().getSimpleName())
			.addKeyValue("reason", failureReasonSafeToLog(e))
			.log();
	}

	/**
	 * {@link MailException} messages (e.g. Spring's {@code MailSendException})
	 * commonly embed the recipient's email address and/or raw SMTP server
	 * response text - never log that verbatim into this WARN-level
	 * application log (security-review finding). Every other exception this
	 * method can throw ({@link RecipientNotResolvedException}, {@link
	 * TemplateNotFoundException}, {@link TemplateRenderingException}, JSON
	 * parse failures) carries no recipient PII in its message.
	 */
	private static String failureReasonSafeToLog(RuntimeException e) {
		if (e instanceof MailException) {
			return "SMTP send failed (" + e.getClass().getSimpleName() + ")";
		}
		return e.getMessage();
	}

	private static String stripTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

}
