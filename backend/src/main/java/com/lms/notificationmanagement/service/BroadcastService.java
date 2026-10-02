package com.lms.notificationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.api.PageResponse;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.api.MessagingChannelApi;
import com.lms.integrationmanagement.api.MessagingChannelApi.ChannelAvailability;
import com.lms.notificationmanagement.domain.Broadcast;
import com.lms.notificationmanagement.domain.Broadcast.AudienceType;
import com.lms.notificationmanagement.domain.CommunicationTemplate;
import com.lms.notificationmanagement.domain.DeliveryChannel;
import com.lms.notificationmanagement.domain.InAppNotification;
import com.lms.notificationmanagement.domain.NotificationDelivery;
import com.lms.notificationmanagement.repository.BroadcastRepository;
import com.lms.notificationmanagement.repository.InAppNotificationRepository;
import com.lms.notificationmanagement.repository.NotificationDeliveryRepository;
import com.lms.notificationmanagement.service.AudienceResolver.AudienceSpec;
import com.lms.notificationmanagement.service.AudienceResolver.Recipient;
import com.lms.tenantmanagement.api.TenantLookupApi;
import com.lms.tenantmanagement.api.TenantSummary;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.usermanagement.api.StudentSummary;
import com.lms.usermanagement.api.TeacherLookupApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Broadcasts (Wave 11 §2.5): compose, preview, send, and read back with
 * delivery logs.
 *
 * <p><b>Authorization.</b> Staff need {@code COMMUNICATIONS} VIEW to read and
 * CREATE_EDIT to send. Teachers (decision D2) may send only a {@code COURSE}
 * broadcast for a course they own, on {@code IN_APP}/{@code EMAIL} only
 * (judgment call J2), and see only their own broadcasts.
 *
 * <p><b>Send.</b> One transaction: resolve the audience, persist the
 * broadcast, write IN_APP notifications immediately, and enqueue one {@code
 * notification_delivery} per (recipient, channel) for the async dispatcher.
 * No provider is called here. A repeated {@code clientRequestId} returns the
 * original broadcast (no second send).
 */
@Service
public class BroadcastService {

	private static final String TARGET_ENTITY = "broadcast";

	private static final Set<DeliveryChannel> TEACHER_CHANNELS = EnumSet.of(DeliveryChannel.IN_APP,
			DeliveryChannel.EMAIL);

	private final BroadcastRepository broadcastRepository;

	private final NotificationDeliveryRepository deliveryRepository;

	private final InAppNotificationRepository inAppNotificationRepository;

	private final AudienceResolver audienceResolver;

	private final CommunicationTemplateService templateService;

	private final BroadcastTextRenderer renderer;

	private final MessagingChannelApi messagingChannelApi;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final TenantLookupApi tenantLookupApi;

	private final CourseLookupApi courseLookupApi;

	private final StudentLookupApi studentLookupApi;

	private final TeacherLookupApi teacherLookupApi;

	public BroadcastService(BroadcastRepository broadcastRepository, NotificationDeliveryRepository deliveryRepository,
			InAppNotificationRepository inAppNotificationRepository, AudienceResolver audienceResolver,
			CommunicationTemplateService templateService, BroadcastTextRenderer renderer,
			MessagingChannelApi messagingChannelApi, PermissionCheckService permissionCheckService,
			AuditLogApi auditLogApi, TenantContext tenantContext, TenantLookupApi tenantLookupApi,
			CourseLookupApi courseLookupApi, StudentLookupApi studentLookupApi, TeacherLookupApi teacherLookupApi) {
		this.broadcastRepository = broadcastRepository;
		this.deliveryRepository = deliveryRepository;
		this.inAppNotificationRepository = inAppNotificationRepository;
		this.audienceResolver = audienceResolver;
		this.templateService = templateService;
		this.renderer = renderer;
		this.messagingChannelApi = messagingChannelApi;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.tenantLookupApi = tenantLookupApi;
		this.courseLookupApi = courseLookupApi;
		this.studentLookupApi = studentLookupApi;
		this.teacherLookupApi = teacherLookupApi;
	}

	// ------------------------------------------------------------------
	// Views / commands
	// ------------------------------------------------------------------

	public record ChannelOption(String channel, boolean available, boolean allowedForYou, String reason) {

	}

	public record AudiencePreview(int recipientCount, Map<String, Integer> reachByChannel, List<String> sampleNames) {

	}

	public record SendCommand(UUID clientRequestId, String subject, String body, String smsBody, UUID templateId,
			Set<DeliveryChannel> channels, AudienceSpec audience) {

	}

	public record ChannelStats(String channel, long pending, long sent, long failed, long skipped) {

	}

	public record BroadcastView(UUID id, String subject, String body, String smsBody, List<String> channels,
			String audienceType, UUID audienceCourseId, String audiencePaymentStatus, int recipientCount,
			UUID senderUserId, String senderRole, UUID templateId, Instant createdAt, List<ChannelStats> stats,
			boolean replayed) {

	}

	/**
	 * @param providerStatus what the provider later reported (WhatsApp via Meta webhook), or null
	 * @param providerErrorCode the provider's error code when {@code providerStatus} is FAILED
	 */
	public record DeliveryView(UUID id, UUID recipientUserId, String recipientName, String channel, String status,
			int attemptCount, String destination, String errorCode, Instant nextAttemptAt, Instant completedAt,
			String providerStatus, Instant providerStatusAt, String providerErrorCode) {

	}

	public record RecipientDeliveryView(UUID id, UUID broadcastId, String subject, String channel, String status,
			String errorCode, Instant createdAt, Instant completedAt) {

	}

	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<ChannelOption> channels() {
		boolean teacher = CommunicationActors.isTeacher();
		if (!teacher) {
			permissionCheckService.requirePermission(DomainArea.COMMUNICATIONS, PermissionAction.VIEW);
		}
		List<ChannelOption> options = new ArrayList<>();
		for (DeliveryChannel channel : DeliveryChannel.values()) {
			boolean allowed = !teacher || TEACHER_CHANNELS.contains(channel);
			Optional<ChannelAvailability> availability = providerAvailability(channel);
			boolean available = availability.map(ChannelAvailability::available).orElse(true);
			String reason = availability.filter(a -> !a.available()).map(ChannelAvailability::reason).orElse(null);
			options.add(new ChannelOption(channel.name(), available, allowed, reason));
		}
		return options;
	}

	@Transactional(readOnly = true)
	public AudiencePreview preview(AudienceSpec audience) {
		authorizeSend(audience, null);
		List<Recipient> recipients = audienceResolver.resolve(audience);
		Map<String, Integer> reach = new LinkedHashMap<>();
		reach.put(DeliveryChannel.IN_APP.name(), recipients.size());
		reach.put(DeliveryChannel.EMAIL.name(), (int) recipients.stream().filter(Recipient::hasEmail).count());
		reach.put(DeliveryChannel.SMS.name(), (int) recipients.stream().filter(Recipient::hasMobile).count());
		reach.put(DeliveryChannel.WHATSAPP.name(), (int) recipients.stream().filter(Recipient::hasWhatsApp).count());
		List<String> sample = recipients.stream().limit(10).map(Recipient::name).toList();
		return new AudiencePreview(recipients.size(), reach, sample);
	}

	@Transactional
	public BroadcastView send(SendCommand command) {
		if (command.clientRequestId() == null) {
			throw invalid("clientRequestId", "is required");
		}
		Set<DeliveryChannel> channels = (command.channels() == null || command.channels().isEmpty())
				? EnumSet.noneOf(DeliveryChannel.class) : EnumSet.copyOf(command.channels());
		authorizeSend(command.audience(), channels);
		UUID actor = CommunicationActors.currentUserId();

		Optional<Broadcast> existing = broadcastRepository.findByClientRequestId(command.clientRequestId());
		if (existing.isPresent()) {
			if (!existing.get().getSenderUserId().equals(actor)) {
				throw new ConflictException("This request id has already been used");
			}
			return toView(existing.get(), true);
		}

		if (channels.isEmpty()) {
			throw invalid("channels", "must include at least one channel");
		}
		String subject = trimToNull(command.subject());
		String body = (command.body() == null || command.body().isBlank()) ? null : command.body().strip();
		String smsBody = trimToNull(command.smsBody());
		List<FieldError> errors = new ArrayList<>();
		if (subject == null || subject.length() > 200) {
			errors.add(new FieldError("subject", "must be 1-200 characters"));
		}
		if (body == null || body.length() > CommunicationTemplateService.MAX_BODY) {
			errors.add(new FieldError("body", "must be 1-" + CommunicationTemplateService.MAX_BODY + " characters"));
		}
		if (smsBody != null && smsBody.length() > CommunicationTemplateService.MAX_SMS) {
			errors.add(new FieldError("smsBody", "must be at most " + CommunicationTemplateService.MAX_SMS
					+ " characters"));
		}
		boolean textChannel = channels.contains(DeliveryChannel.SMS) || channels.contains(DeliveryChannel.WHATSAPP);
		if (textChannel && smsBody == null && body != null && body.length() > CommunicationTemplateService.MAX_SMS) {
			errors.add(new FieldError("smsBody", "is required for SMS/WhatsApp when the message is longer than "
					+ CommunicationTemplateService.MAX_SMS + " characters"));
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid broadcast", errors);
		}
		renderer.validate("subject", subject);
		renderer.validate("body", body);
		renderer.validate("smsBody", smsBody);

		CommunicationTemplate template = (command.templateId() == null) ? null
				: templateService.requireActive(command.templateId());
		if (channels.contains(DeliveryChannel.WHATSAPP) && (template == null
				|| template.getWhatsAppStatus() != CommunicationTemplate.WhatsAppStatus.APPROVED)) {
			throw invalid("channels", "WhatsApp needs a template whose WhatsApp approval is APPROVED");
		}
		for (DeliveryChannel channel : channels) {
			providerAvailability(channel).filter(availability -> !availability.available())
				.ifPresent(availability -> {
					throw new ProviderConflictException(ApiErrorCodes.CHANNEL_NOT_AVAILABLE,
							channel.name() + " is not available for this institute");
				});
		}

		List<Recipient> recipients = audienceResolver.resolve(command.audience());
		Instant now = Instant.now();
		UUID tenantId = tenantContext.getTenantId();
		AudienceSpec audience = command.audience();
		Broadcast broadcast = broadcastRepository.saveAndFlush(new Broadcast(tenantId, command.clientRequestId(),
				actor, CommunicationActors.currentRole(), (template == null) ? null : template.getId(), subject, body,
				smsBody, channels, audience.type(),
				(audience.type() == AudienceType.COURSE || audience.type() == AudienceType.PAYMENT_STATUS)
						? audience.courseId() : null,
				(audience.type() == AudienceType.PAYMENT_STATUS) ? audience.paymentStatus() : null,
				recipients.size(), now));

		String instituteName = instituteName(tenantId);
		List<NotificationDelivery> deliveries = new ArrayList<>();
		List<InAppNotification> inApp = new ArrayList<>();
		for (Recipient recipient : recipients) {
			for (DeliveryChannel channel : channels) {
				if (channel == DeliveryChannel.IN_APP) {
					Map<String, String> vars = Map.of("name", nullToEmpty(recipient.name()), "instituteName",
							instituteName);
					inApp.add(new InAppNotification(tenantId, recipient.userId(), renderer.render(subject, vars),
							renderer.render(body, vars), now));
					deliveries.add(NotificationDelivery.deliveredInApp(tenantId, broadcast.getId(), recipient.userId(),
							now));
				}
				else {
					deliveries.add(NotificationDelivery.pending(tenantId, broadcast.getId(), recipient.userId(),
							channel, now));
				}
			}
		}
		inAppNotificationRepository.saveAll(inApp);
		deliveryRepository.saveAll(deliveries);

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("audienceType", audience.type().name());
		metadata.put("channels", channels.stream().map(Enum::name).sorted().toList());
		metadata.put("recipientCount", recipients.size());
		if (broadcast.getAudienceCourseId() != null) {
			metadata.put("courseId", broadcast.getAudienceCourseId().toString());
		}
		if (broadcast.getAudiencePaymentStatus() != null) {
			metadata.put("paymentStatus", broadcast.getAudiencePaymentStatus().name());
		}
		auditLogApi.record(new AuditLogEntry(actor, "communication.broadcast_sent", TARGET_ENTITY, broadcast.getId(),
				null, metadata));
		return toView(broadcast, false);
	}

	@Transactional(readOnly = true)
	public PageResponse<BroadcastView> list(Pageable pageable) {
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100),
				Sort.by(Sort.Direction.DESC, "createdAt"));
		Page<Broadcast> page;
		if (CommunicationActors.isTeacher()) {
			page = broadcastRepository.findBySender(CommunicationActors.currentUserId(), sorted);
		}
		else {
			permissionCheckService.requirePermission(DomainArea.COMMUNICATIONS, PermissionAction.VIEW);
			page = broadcastRepository.findAllNewestFirst(sorted);
		}
		return PageResponse.from(page.map(broadcast -> toView(broadcast, false)));
	}

	@Transactional(readOnly = true)
	public BroadcastView get(UUID id) {
		return toView(loadVisible(id), false);
	}

	@Transactional(readOnly = true)
	public PageResponse<DeliveryView> deliveries(UUID broadcastId, NotificationDelivery.Status status,
			DeliveryChannel channel, Pageable pageable) {
		Broadcast broadcast = loadVisible(broadcastId);
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 200),
				Sort.by("createdAt", "id"));
		Page<NotificationDelivery> page = deliveryRepository.findForBroadcast(broadcast.getId(), status, channel,
				sorted);
		Map<UUID, String> names = recipientNames(page.getContent().stream()
			.map(NotificationDelivery::getRecipientUserId)
			.collect(Collectors.toSet()));
		return PageResponse.from(page.map(delivery -> new DeliveryView(delivery.getId(),
				delivery.getRecipientUserId(), names.get(delivery.getRecipientUserId()), delivery.getChannel().name(),
				delivery.getStatus().name(), delivery.getAttemptCount(), delivery.getDestinationMasked(),
				delivery.getLastErrorCode(), delivery.getNextAttemptAt(), delivery.getCompletedAt(),
				(delivery.getProviderStatus() == null) ? null : delivery.getProviderStatus().name(),
				delivery.getProviderStatusAt(), delivery.getProviderErrorCode())));
	}

	/**
	 * PAR-03-04: a student's broadcast history for the staff Student detail
	 * page. {@code studentId} is the student profile id (as every other
	 * {@code /students/{id}/...} read); resolved within the current tenant, so
	 * another tenant's id is a 404.
	 */
	@Transactional(readOnly = true)
	public PageResponse<RecipientDeliveryView> studentHistory(UUID studentId, Pageable pageable) {
		permissionCheckService.requirePermission(DomainArea.COMMUNICATIONS, PermissionAction.VIEW);
		UUID userId = studentLookupApi.resolveUserId(studentId)
			.orElseThrow(() -> new NotFoundException("Student not found"));
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100),
				Sort.by(Sort.Direction.DESC, "createdAt"));
		Page<NotificationDelivery> page = deliveryRepository.findForRecipient(userId, sorted);
		Map<UUID, Broadcast> broadcasts = broadcastRepository
			.findAllById(page.getContent().stream().map(NotificationDelivery::getBroadcastId).collect(
					Collectors.toSet()))
			.stream()
			.collect(Collectors.toMap(Broadcast::getId, Function.identity()));
		return PageResponse.from(page.map(delivery -> {
			Broadcast broadcast = broadcasts.get(delivery.getBroadcastId());
			return new RecipientDeliveryView(delivery.getId(), delivery.getBroadcastId(),
					(broadcast == null) ? null : broadcast.getSubject(), delivery.getChannel().name(),
					delivery.getStatus().name(), delivery.getLastErrorCode(), delivery.getCreatedAt(),
					delivery.getCompletedAt());
		}));
	}

	// ------------------------------------------------------------------

	/**
	 * Staff: {@code COMMUNICATIONS}/CREATE_EDIT. Teacher: COURSE audience for
	 * an owned course, teacher channels only. {@code channels} is null for a
	 * preview.
	 */
	private void authorizeSend(AudienceSpec audience, Set<DeliveryChannel> channels) {
		if (!CommunicationActors.isTeacher()) {
			permissionCheckService.requirePermission(DomainArea.COMMUNICATIONS, PermissionAction.CREATE_EDIT);
			return;
		}
		if (audience == null || audience.type() != AudienceType.COURSE || audience.courseId() == null) {
			throw new AccessDeniedException("Teachers can only message the students of their own courses");
		}
		UUID owner = courseLookupApi.getTeacherId(audience.courseId())
			.orElseThrow(() -> new NotFoundException("Course not found"));
		if (!owner.equals(CommunicationActors.currentUserId())) {
			// Same tenant, another teacher's course: indistinguishable from "not found".
			throw new NotFoundException("Course not found");
		}
		if (channels != null && !TEACHER_CHANNELS.containsAll(channels)) {
			throw new AccessDeniedException("Teachers can send in-app and email messages only");
		}
	}

	private Broadcast loadVisible(UUID id) {
		Broadcast broadcast = broadcastRepository.findById(id)
			.orElseThrow(() -> new NotFoundException("Broadcast not found"));
		if (CommunicationActors.isTeacher()) {
			if (!broadcast.getSenderUserId().equals(CommunicationActors.currentUserId())) {
				throw new NotFoundException("Broadcast not found");
			}
		}
		else {
			permissionCheckService.requirePermission(DomainArea.COMMUNICATIONS, PermissionAction.VIEW);
		}
		return broadcast;
	}

	private Optional<ChannelAvailability> providerAvailability(DeliveryChannel channel) {
		return switch (channel) {
			case SMS -> Optional.of(messagingChannelApi.availability(MessagingChannelApi.Channel.SMS));
			case WHATSAPP -> Optional.of(messagingChannelApi.availability(MessagingChannelApi.Channel.WHATSAPP));
			default -> Optional.empty();
		};
	}

	private BroadcastView toView(Broadcast broadcast, boolean replayed) {
		List<ChannelStats> stats = new ArrayList<>();
		for (DeliveryChannel channel : broadcast.getChannels()) {
			stats.add(new ChannelStats(channel.name(),
					deliveryRepository.countForBroadcast(broadcast.getId(), channel, NotificationDelivery.Status.PENDING)
							+ deliveryRepository.countForBroadcast(broadcast.getId(), channel,
									NotificationDelivery.Status.SENDING),
					deliveryRepository.countForBroadcast(broadcast.getId(), channel, NotificationDelivery.Status.SENT),
					deliveryRepository.countForBroadcast(broadcast.getId(), channel, NotificationDelivery.Status.FAILED),
					deliveryRepository.countForBroadcast(broadcast.getId(), channel,
							NotificationDelivery.Status.SKIPPED)));
		}
		return new BroadcastView(broadcast.getId(), broadcast.getSubject(), broadcast.getBody(),
				broadcast.getSmsBody(), broadcast.getChannels().stream().map(Enum::name).toList(),
				broadcast.getAudienceType().name(), broadcast.getAudienceCourseId(),
				(broadcast.getAudiencePaymentStatus() == null) ? null : broadcast.getAudiencePaymentStatus().name(),
				broadcast.getRecipientCount(), broadcast.getSenderUserId(), broadcast.getSenderRole(),
				broadcast.getTemplateId(), broadcast.getCreatedAt(), stats, replayed);
	}

	private Map<UUID, String> recipientNames(Set<UUID> userIds) {
		Map<UUID, String> names = new HashMap<>();
		if (userIds.isEmpty()) {
			return names;
		}
		for (StudentSummary student : studentLookupApi.getStudentSummariesByUserId(userIds)) {
			names.put(student.userId(), student.name());
		}
		if (names.size() < userIds.size()) {
			teacherLookupApi.listTeachers()
				.stream()
				.filter(teacher -> userIds.contains(teacher.userId()))
				.forEach(teacher -> names.put(teacher.userId(), teacher.name()));
		}
		return names;
	}

	String instituteName(UUID tenantId) {
		return tenantLookupApi.resolveTenantSummaries(Set.of(tenantId))
			.stream()
			.findFirst()
			.map(TenantSummary::name)
			.orElse("");
	}

	private static String trimToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	private static String nullToEmpty(String value) {
		return (value == null) ? "" : value;
	}

	private static FieldValidationException invalid(String field, String message) {
		return new FieldValidationException("Invalid broadcast", List.of(new FieldError(field, message)));
	}

}
