package com.lms.notificationmanagement.service;

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

	public NotificationDispatchService(NotificationTemplateRepository notificationTemplateRepository,
			UserProvisioningApi userProvisioningApi, MessagingProviderApi messagingProviderApi,
			NotificationTemplateRenderer notificationTemplateRenderer, ObjectMapper objectMapper,
			NotificationDispatchClaimService notificationDispatchClaimService,
			NotificationDispatchFinalizeService notificationDispatchFinalizeService) {
		this.notificationTemplateRepository = notificationTemplateRepository;
		this.userProvisioningApi = userProvisioningApi;
		this.messagingProviderApi = messagingProviderApi;
		this.notificationTemplateRenderer = notificationTemplateRenderer;
		this.objectMapper = objectMapper;
		this.notificationDispatchClaimService = notificationDispatchClaimService;
		this.notificationDispatchFinalizeService = notificationDispatchFinalizeService;
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

			NotificationTemplate template = notificationTemplateRepository.findByTemplateKey(row.getEventType().name())
				.orElseThrow(() -> new TemplateNotFoundException(
						"No notification_template found for templateKey " + row.getEventType().name()));

			Map<String, Object> payloadMap = objectMapper.readValue(row.getPayload(),
					new TypeReference<Map<String, Object>>() {
					});

			RenderedNotification rendered = notificationTemplateRenderer.render(template, payloadMap);

			messagingProviderApi.sendEmail(recipientEmail, rendered.subject(), rendered.body());

			notificationDispatchFinalizeService.markSent(row.getId(), row.getTenantId(), row.getRecipientUserId(),
					rendered.subject(), rendered.body());
		}
		catch (RuntimeException e) {
			logDispatchFailure(row, e);
			notificationDispatchFinalizeService.markFailed(row.getId(), row.getTenantId());
		}
		finally {
			TenantContextHolder.clear();
		}
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

}
