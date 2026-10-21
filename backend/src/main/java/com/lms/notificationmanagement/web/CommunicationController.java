package com.lms.notificationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.notificationmanagement.domain.Broadcast.AudienceType;
import com.lms.notificationmanagement.domain.Broadcast.PaymentStatusFilter;
import com.lms.notificationmanagement.domain.DeliveryChannel;
import com.lms.notificationmanagement.domain.NotificationDelivery;
import com.lms.notificationmanagement.service.AudienceResolver.AudienceSpec;
import com.lms.notificationmanagement.service.BroadcastService;
import com.lms.notificationmanagement.service.BroadcastService.AudiencePreview;
import com.lms.notificationmanagement.service.BroadcastService.BroadcastView;
import com.lms.notificationmanagement.service.BroadcastService.ChannelOption;
import com.lms.notificationmanagement.service.BroadcastService.DeliveryView;
import com.lms.notificationmanagement.service.BroadcastService.RecipientDeliveryView;
import com.lms.notificationmanagement.service.BroadcastService.SendCommand;
import com.lms.notificationmanagement.service.CommunicationTemplateService;
import com.lms.notificationmanagement.service.CommunicationTemplateService.TemplateView;
import com.lms.notificationmanagement.service.CommunicationTemplateService.WhatsAppAction;
import com.lms.notificationmanagement.service.EventTemplateService;
import com.lms.notificationmanagement.service.EventTemplateService.EventTemplateView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Communications (Wave 11): broadcast templates, event templates, audience
 * preview, broadcasts and delivery logs. Tenant from the trusted context
 * only; every permission and ownership check is in the services ({@code
 * COMMUNICATIONS} grants for staff, course ownership for teachers).
 */
@RestController
@RequestMapping("/api/v1/communications")
public class CommunicationController {

	private final BroadcastService broadcastService;

	private final CommunicationTemplateService templateService;

	private final EventTemplateService eventTemplateService;

	public CommunicationController(BroadcastService broadcastService, CommunicationTemplateService templateService,
			EventTemplateService eventTemplateService) {
		this.broadcastService = broadcastService;
		this.templateService = templateService;
		this.eventTemplateService = eventTemplateService;
	}

	public record AudienceRequest(@NotNull AudienceType type, UUID courseId, PaymentStatusFilter paymentStatus,
			@Size(max = 5000) List<UUID> studentIds) {

		AudienceSpec toSpec() {
			return new AudienceSpec(type, courseId, paymentStatus, studentIds);
		}

	}

	public record SendBroadcastRequest(@NotNull UUID clientRequestId, @Size(max = 200) String subject,
			@Size(max = 5000) String body, @Size(max = 640) String smsBody, UUID templateId,
			Set<DeliveryChannel> channels, @NotNull @Valid AudienceRequest audience) {

	}

	public record TemplateRequest(@Size(max = 120) String name, @Size(max = 200) String subject,
			@Size(max = 5000) String body, @Size(max = 640) String smsBody) {

	}

	public record WhatsAppStatusRequest(@NotNull WhatsAppAction action, @Size(max = 100) String providerTemplateName,
			@Size(max = 500) String reason) {

	}

	public record EventTemplateRequest(@Size(max = 200) String subject, @Size(max = 5000) String body) {

	}

	// ---- channels / audience ----

	@GetMapping("/channels")
	public ResponseEntity<ApiResponse<List<ChannelOption>>> channels() {
		return ResponseEntity.ok(ApiResponse.success(broadcastService.channels()));
	}

	@PostMapping("/audience-preview")
	public ResponseEntity<ApiResponse<AudiencePreview>> preview(@Valid @RequestBody AudienceRequest request) {
		return ResponseEntity.ok(ApiResponse.success(broadcastService.preview(request.toSpec())));
	}

	// ---- broadcasts ----

	@GetMapping("/broadcasts")
	public ResponseEntity<ApiResponse<PageResponse<BroadcastView>>> listBroadcasts(
			@PageableDefault(size = 20) Pageable pageable) {
		return ResponseEntity.ok(ApiResponse.success(broadcastService.list(pageable)));
	}

	/** 201 for a new broadcast; 200 when {@code clientRequestId} was already used by this sender (replay). */
	@PostMapping("/broadcasts")
	public ResponseEntity<ApiResponse<BroadcastView>> send(@Valid @RequestBody SendBroadcastRequest request) {
		BroadcastView view = broadcastService.send(new SendCommand(request.clientRequestId(), request.subject(),
				request.body(), request.smsBody(), request.templateId(), request.channels(),
				request.audience().toSpec()));
		return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
			.body(ApiResponse.success(view));
	}

	@GetMapping("/broadcasts/{id}")
	public ResponseEntity<ApiResponse<BroadcastView>> getBroadcast(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(broadcastService.get(id)));
	}

	@GetMapping("/broadcasts/{id}/deliveries")
	public ResponseEntity<ApiResponse<PageResponse<DeliveryView>>> deliveries(@PathVariable UUID id,
			@RequestParam(required = false) NotificationDelivery.Status status,
			@RequestParam(required = false) DeliveryChannel channel, @PageableDefault(size = 50) Pageable pageable) {
		return ResponseEntity.ok(ApiResponse.success(broadcastService.deliveries(id, status, channel, pageable)));
	}

	/** {@code studentId} = student profile id, as every other Student detail tab read. */
	@GetMapping("/students/{studentId}/deliveries")
	public ResponseEntity<ApiResponse<PageResponse<RecipientDeliveryView>>> studentHistory(
			@PathVariable UUID studentId, @PageableDefault(size = 20) Pageable pageable) {
		return ResponseEntity.ok(ApiResponse.success(broadcastService.studentHistory(studentId, pageable)));
	}

	// ---- broadcast templates ----

	@GetMapping("/templates")
	public ResponseEntity<ApiResponse<List<TemplateView>>> templates(
			@RequestParam(defaultValue = "false") boolean includeArchived) {
		return ResponseEntity.ok(ApiResponse.success(templateService.list(includeArchived)));
	}

	@PostMapping("/templates")
	public ResponseEntity<ApiResponse<TemplateView>> createTemplate(@Valid @RequestBody TemplateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(templateService.create(request.name(), request.subject(), request.body(),
					request.smsBody())));
	}

	@PutMapping("/templates/{id}")
	public ResponseEntity<ApiResponse<TemplateView>> updateTemplate(@PathVariable UUID id,
			@Valid @RequestBody TemplateRequest request) {
		return ResponseEntity.ok(ApiResponse.success(
				templateService.update(id, request.name(), request.subject(), request.body(), request.smsBody())));
	}

	@PostMapping("/templates/{id}/archive")
	public ResponseEntity<ApiResponse<TemplateView>> archiveTemplate(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(templateService.archive(id)));
	}

	@PostMapping("/templates/{id}/whatsapp-status")
	public ResponseEntity<ApiResponse<TemplateView>> whatsAppStatus(@PathVariable UUID id,
			@Valid @RequestBody WhatsAppStatusRequest request) {
		return ResponseEntity.ok(ApiResponse.success(templateService.recordWhatsAppStatus(id, request.action(),
				request.providerTemplateName(), request.reason())));
	}

	// ---- event (system) templates ----

	@GetMapping("/event-templates")
	public ResponseEntity<ApiResponse<List<EventTemplateView>>> eventTemplates() {
		return ResponseEntity.ok(ApiResponse.success(eventTemplateService.list()));
	}

	@PutMapping("/event-templates/{key}")
	public ResponseEntity<ApiResponse<EventTemplateView>> updateEventTemplate(@PathVariable String key,
			@Valid @RequestBody EventTemplateRequest request) {
		return ResponseEntity.ok(ApiResponse.success(eventTemplateService.update(key, request.subject(),
				request.body())));
	}

}
