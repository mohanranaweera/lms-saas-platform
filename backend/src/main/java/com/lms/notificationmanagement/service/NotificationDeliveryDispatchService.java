package com.lms.notificationmanagement.service;

import com.lms.common.logging.ContactMasking;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.MessagingChannelApi;
import com.lms.integrationmanagement.api.MessagingChannelApi.MessagingSendException;
import com.lms.integrationmanagement.api.MessagingChannelApi.OutboundMessage;
import com.lms.integrationmanagement.api.MessagingChannelApi.SendReceipt;
import com.lms.integrationmanagement.api.MessagingProviderApi;
import com.lms.notificationmanagement.domain.Broadcast;
import com.lms.notificationmanagement.domain.CommunicationTemplate;
import com.lms.notificationmanagement.domain.DeliveryChannel;
import com.lms.notificationmanagement.domain.NotificationChannelPreference;
import com.lms.notificationmanagement.repository.BroadcastRepository;
import com.lms.notificationmanagement.repository.CommunicationTemplateRepository;
import com.lms.notificationmanagement.repository.NotificationChannelPreferenceRepository;
import com.lms.notificationmanagement.service.NotificationDeliveryClaimService.ClaimedDelivery;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantSummary;
import com.lms.usermanagement.api.StudentContact;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.TeacherLookupApi;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

/**
 * Sends one claimed broadcast delivery (Wave 11 §2.6). Not transactional: the
 * claim committed before this runs, and the outcome is committed afterwards by
 * {@link NotificationDeliveryFinalizeService} - no transaction spans the
 * provider call.
 *
 * <p><b>Tenant context.</b> Set from the claimed row's own {@code tenant_id}
 * for the whole attempt (address lookup, consent, the provider account the
 * channel API picks) and cleared in {@code finally}. Nothing is inherited
 * from the request that created the broadcast.
 *
 * <p><b>No PII in logs.</b> Addresses are resolved here at send time, never
 * stored; only masked forms reach the delivery row or a log line, and
 * provider failures surface only as fixed codes.
 */
@Service
public class NotificationDeliveryDispatchService {

	private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryDispatchService.class);

	public static final String NO_ADDRESS = "NO_ADDRESS";

	public static final String NO_CONSENT = "NO_CONSENT";

	public static final String USER_INACTIVE = "USER_INACTIVE";

	public static final String EMAIL_TRANSIENT = "EMAIL_TRANSIENT";

	public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

	private final NotificationDeliveryClaimService claimService;

	private final NotificationDeliveryFinalizeService finalizeService;

	private final BroadcastRepository broadcastRepository;

	private final CommunicationTemplateRepository templateRepository;

	private final NotificationChannelPreferenceRepository preferenceRepository;

	private final UserProvisioningApi userProvisioningApi;

	private final StudentLookupApi studentLookupApi;

	private final TeacherLookupApi teacherLookupApi;

	private final TenantLookupApi tenantLookupApi;

	private final MessagingProviderApi emailApi;

	private final MessagingChannelApi channelApi;

	private final BroadcastTextRenderer renderer;

	public NotificationDeliveryDispatchService(NotificationDeliveryClaimService claimService,
			NotificationDeliveryFinalizeService finalizeService, BroadcastRepository broadcastRepository,
			CommunicationTemplateRepository templateRepository,
			NotificationChannelPreferenceRepository preferenceRepository, UserProvisioningApi userProvisioningApi,
			StudentLookupApi studentLookupApi, TeacherLookupApi teacherLookupApi, TenantLookupApi tenantLookupApi,
			MessagingProviderApi emailApi, MessagingChannelApi channelApi, BroadcastTextRenderer renderer) {
		this.claimService = claimService;
		this.finalizeService = finalizeService;
		this.broadcastRepository = broadcastRepository;
		this.templateRepository = templateRepository;
		this.preferenceRepository = preferenceRepository;
		this.userProvisioningApi = userProvisioningApi;
		this.studentLookupApi = studentLookupApi;
		this.teacherLookupApi = teacherLookupApi;
		this.tenantLookupApi = tenantLookupApi;
		this.emailApi = emailApi;
		this.channelApi = channelApi;
		this.renderer = renderer;
	}

	public void dispatchOne(UUID deliveryId) {
		Optional<ClaimedDelivery> claimed = claimService.claim(deliveryId);
		if (claimed.isEmpty()) {
			return;
		}
		ClaimedDelivery row = claimed.get();
		String masked = null;
		try {
			TenantContextHolder.set(row.tenantId());
			Broadcast broadcast = broadcastRepository.findById(row.broadcastId())
				.orElseThrow(() -> new IllegalStateException("Broadcast not found for delivery"));
			Optional<TenantUserSummary> user = userProvisioningApi
				.findTenantUserSummaries(Set.of(row.recipientUserId()))
				.stream()
				.findFirst();
			if (user.isEmpty() || !"active".equalsIgnoreCase(user.get().status())) {
				finalizeService.markSkipped(row.id(), row.tenantId(), USER_INACTIVE, null);
				return;
			}
			if (!hasConsent(row.recipientUserId(), row.channel())) {
				finalizeService.markSkipped(row.id(), row.tenantId(), NO_CONSENT, null);
				return;
			}
			Optional<StudentContact> student = studentLookupApi.findContact(row.recipientUserId());
			String name = student.map(StudentContact::name).orElseGet(() -> teacherName(row.recipientUserId()));
			Map<String, String> vars = Map.of("name", (name == null) ? "" : name, "instituteName",
					instituteName(row.tenantId()));

			if (row.channel() == DeliveryChannel.EMAIL) {
				String email = user.get().email();
				if (email == null || email.isBlank()) {
					finalizeService.markSkipped(row.id(), row.tenantId(), NO_ADDRESS, null);
					return;
				}
				masked = ContactMasking.maskEmail(email);
				emailApi.sendEmail(email, renderer.render(broadcast.getSubject(), vars),
						renderer.render(broadcast.getBody(), vars));
				finalizeService.markSent(row.id(), row.tenantId(), masked, null, null);
				return;
			}

			String address = phoneFor(row.channel(), student.orElse(null));
			if (address == null) {
				finalizeService.markSkipped(row.id(), row.tenantId(), NO_ADDRESS, null);
				return;
			}
			masked = ContactMasking.maskPhone(address);
			String source = (broadcast.getSmsBody() != null) ? broadcast.getSmsBody() : broadcast.getBody();
			String text = renderer.render(source, vars);
			String providerTemplate = null;
			List<String> templateParameters = List.of();
			if (row.channel() == DeliveryChannel.WHATSAPP) {
				// Parameters follow the approved template's copy, not the (editable)
				// broadcast copy, so they always match what Meta approved (ADR-024 V4).
				Optional<CommunicationTemplate> template = whatsAppTemplate(broadcast);
				providerTemplate = template.map(CommunicationTemplate::getWhatsAppTemplateName).orElse(null);
				String approvedCopy = template
					.map(t -> (t.getSmsBody() != null) ? t.getSmsBody() : t.getBody())
					.orElse(source);
				templateParameters = renderer.orderedValues(approvedCopy, vars);
			}
			MessagingChannelApi.Channel channel = (row.channel() == DeliveryChannel.SMS)
					? MessagingChannelApi.Channel.SMS : MessagingChannelApi.Channel.WHATSAPP;
			SendReceipt receipt = channelApi.send(new OutboundMessage(channel, address, text, providerTemplate,
					templateParameters));
			finalizeService.markSent(row.id(), row.tenantId(), masked, receipt.providerAccountId(),
					receipt.providerMessageId());
		}
		catch (MessagingSendException ex) {
			logFailure(row, ex.getErrorCode());
			if (MessagingSendException.CHANNEL_NOT_AVAILABLE.equals(ex.getErrorCode())) {
				finalizeService.markSkipped(row.id(), row.tenantId(), ex.getErrorCode(), masked);
			}
			else {
				finalizeService.markRetryOrFailed(row.id(), row.tenantId(), ex.isRetryable(), ex.getErrorCode(),
						masked, ex.getProviderAccountId());
			}
		}
		catch (MailException ex) {
			// Never log the exception message: it can echo the address or SMTP server text.
			logFailure(row, EMAIL_TRANSIENT);
			finalizeService.markRetryOrFailed(row.id(), row.tenantId(), true, EMAIL_TRANSIENT, masked, null);
		}
		catch (RuntimeException ex) {
			log.atError()
				.setMessage("notification.delivery_unexpected_failure")
				.addKeyValue("tenantId", row.tenantId())
				.addKeyValue("deliveryId", row.id())
				.addKeyValue("reasonType", ex.getClass().getSimpleName())
				.log();
			finalizeService.markRetryOrFailed(row.id(), row.tenantId(), false, INTERNAL_ERROR, masked, null);
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	private boolean hasConsent(UUID userId, DeliveryChannel channel) {
		if (channel == DeliveryChannel.IN_APP) {
			return true;
		}
		return preferenceRepository.findForUser(userId, channel)
			.map(NotificationChannelPreference::isOptedIn)
			.orElse(NotificationChannelPreference.defaultFor(channel));
	}

	private static String phoneFor(DeliveryChannel channel, StudentContact contact) {
		if (contact == null) {
			return null;
		}
		String mobile = blankToNull(contact.mobile());
		if (channel == DeliveryChannel.WHATSAPP) {
			String whatsApp = blankToNull(contact.whatsappNumber());
			return (whatsApp != null) ? whatsApp : mobile;
		}
		return mobile;
	}

	private Optional<CommunicationTemplate> whatsAppTemplate(Broadcast broadcast) {
		if (broadcast.getTemplateId() == null) {
			return Optional.empty();
		}
		return templateRepository.findById(broadcast.getTemplateId());
	}

	private String teacherName(UUID userId) {
		return teacherLookupApi.listTeachers()
			.stream()
			.filter(teacher -> teacher.userId().equals(userId))
			.map(TeacherLookupApi.TeacherSummary::name)
			.findFirst()
			.orElse("");
	}

	private String instituteName(UUID tenantId) {
		return tenantLookupApi.resolveTenantSummaries(Set.of(tenantId))
			.stream()
			.findFirst()
			.map(TenantSummary::name)
			.orElse("");
	}

	private static void logFailure(ClaimedDelivery row, String code) {
		log.atWarn()
			.setMessage("notification.delivery_failed")
			.addKeyValue("tenantId", row.tenantId())
			.addKeyValue("deliveryId", row.id())
			.addKeyValue("channel", row.channel())
			.addKeyValue("attempt", row.attemptCount())
			.addKeyValue("errorCode", code)
			.log();
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

}
