package com.lms.notificationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.notificationmanagement.domain.CommunicationTemplate;
import com.lms.notificationmanagement.repository.CommunicationTemplateRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Broadcast templates (Wave 11 §2.4): {@code COMMUNICATIONS} VIEW to read,
 * CREATE_EDIT to create/edit/archive. Teachers may read ACTIVE templates (to
 * compose); they cannot change them. Recording WhatsApp pre-approval status
 * additionally needs {@code INTEGRATIONS} CREATE_EDIT (Tenant Admin) - it is a
 * statement about the tenant's provider account. Every change is
 * audit-logged.
 */
@Service
public class CommunicationTemplateService {

	private static final String TARGET_ENTITY = "communication_template";

	static final int MAX_BODY = 5000;

	static final int MAX_SMS = 640;

	private final CommunicationTemplateRepository repository;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final BroadcastTextRenderer renderer;

	public CommunicationTemplateService(CommunicationTemplateRepository repository,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext,
			BroadcastTextRenderer renderer) {
		this.repository = repository;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.renderer = renderer;
	}

	public record TemplateView(UUID id, String name, String subject, String body, String smsBody, String status,
			String whatsAppStatus, String whatsAppTemplateName, String whatsAppStatusReason,
			Instant whatsAppStatusChangedAt, Instant updatedAt) {

	}

	public enum WhatsAppAction {

		SUBMIT, APPROVE, REJECT

	}

	@Transactional(readOnly = true)
	public List<TemplateView> list(boolean includeArchived) {
		boolean teacher = CommunicationActors.isTeacher();
		if (!teacher) {
			permissionCheckService.requirePermission(DomainArea.COMMUNICATIONS, PermissionAction.VIEW);
		}
		List<CommunicationTemplate> rows = new ArrayList<>(
				repository.findAllByStatus(CommunicationTemplate.Status.ACTIVE));
		if (includeArchived && !teacher) {
			rows.addAll(repository.findAllByStatus(CommunicationTemplate.Status.ARCHIVED));
		}
		return rows.stream().map(CommunicationTemplateService::toView).toList();
	}

	/** Loads an ACTIVE template for use in a broadcast (caller has already been authorized). */
	@Transactional(readOnly = true)
	public CommunicationTemplate requireActive(UUID id) {
		CommunicationTemplate template = repository.findById(id)
			.orElseThrow(() -> new NotFoundException("Template not found"));
		if (!template.isActive()) {
			throw new ConflictException("This template is archived");
		}
		return template;
	}

	@Transactional
	public TemplateView create(String name, String subject, String body, String smsBody) {
		permissionCheckService.requirePermission(DomainArea.COMMUNICATIONS, PermissionAction.CREATE_EDIT);
		Copy copy = validate(name, subject, body, smsBody, null);
		CommunicationTemplate saved = repository.saveAndFlush(
				new CommunicationTemplate(tenantContext.getTenantId(), copy.name, copy.subject, copy.body,
						copy.smsBody));
		auditLogApi.record(new AuditLogEntry(CommunicationActors.currentUserId(), "communication_template.created",
				TARGET_ENTITY, saved.getId(), null, Map.of("name", saved.getName())));
		return toView(saved);
	}

	@Transactional
	public TemplateView update(UUID id, String name, String subject, String body, String smsBody) {
		permissionCheckService.requirePermission(DomainArea.COMMUNICATIONS, PermissionAction.CREATE_EDIT);
		CommunicationTemplate template = load(id);
		if (!template.isActive()) {
			throw new ConflictException("An archived template cannot be edited");
		}
		Copy copy = validate(name, subject, body, smsBody, id);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("before", snapshot(template));
		boolean whatsAppReset = template.updateContent(copy.name, copy.subject, copy.body, copy.smsBody);
		CommunicationTemplate saved = repository.saveAndFlush(template);
		metadata.put("after", snapshot(saved));
		metadata.put("whatsAppApprovalReset", whatsAppReset);
		auditLogApi.record(new AuditLogEntry(CommunicationActors.currentUserId(), "communication_template.updated",
				TARGET_ENTITY, saved.getId(), null, metadata));
		return toView(saved);
	}

	@Transactional
	public TemplateView archive(UUID id) {
		permissionCheckService.requirePermission(DomainArea.COMMUNICATIONS, PermissionAction.CREATE_EDIT);
		CommunicationTemplate template = load(id);
		if (!template.isActive()) {
			throw new ConflictException("This template is already archived");
		}
		template.archive();
		repository.saveAndFlush(template);
		auditLogApi.record(new AuditLogEntry(CommunicationActors.currentUserId(), "communication_template.archived",
				TARGET_ENTITY, template.getId(), null, Map.of("name", template.getName())));
		return toView(template);
	}

	/** PAR-22-02: record a WhatsApp pre-approval transition (Tenant Admin). */
	@Transactional
	public TemplateView recordWhatsAppStatus(UUID id, WhatsAppAction action, String providerTemplateName,
			String reason) {
		permissionCheckService.requirePermission(DomainArea.COMMUNICATIONS, PermissionAction.CREATE_EDIT);
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		if (action == null) {
			throw new FieldValidationException("Invalid WhatsApp status change",
					List.of(new FieldError("action", "must be SUBMIT, APPROVE or REJECT")));
		}
		CommunicationTemplate template = load(id);
		String previous = template.getWhatsAppStatus().name();
		UUID actor = CommunicationActors.currentUserId();
		Instant now = Instant.now();
		String trimmedReason = (reason == null || reason.isBlank()) ? null : reason.trim();
		try {
			switch (action) {
				case SUBMIT -> {
					String trimmedName = (providerTemplateName == null) ? "" : providerTemplateName.trim();
					if (!trimmedName.matches("^[a-z0-9_]{1,100}$")) {
						throw new FieldValidationException("Invalid WhatsApp status change",
								List.of(new FieldError("providerTemplateName",
										"must be 1-100 lowercase letters, digits or underscores")));
					}
					template.submitForWhatsApp(trimmedName, actor, now);
				}
				case APPROVE -> template.recordWhatsAppDecision(true, trimmedReason, actor, now);
				case REJECT -> {
					if (trimmedReason == null) {
						throw new FieldValidationException("Invalid WhatsApp status change",
								List.of(new FieldError("reason", "is required when recording a rejection")));
					}
					template.recordWhatsAppDecision(false, trimmedReason, actor, now);
				}
			}
		}
		catch (IllegalStateException ex) {
			throw new ConflictException(ex.getMessage());
		}
		repository.saveAndFlush(template);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("from", previous);
		metadata.put("to", template.getWhatsAppStatus().name());
		metadata.put("providerTemplateName", template.getWhatsAppTemplateName());
		auditLogApi.record(new AuditLogEntry(actor, "communication_template.whatsapp_status_changed", TARGET_ENTITY,
				template.getId(), trimmedReason, metadata));
		return toView(template);
	}

	private record Copy(String name, String subject, String body, String smsBody) {

	}

	private Copy validate(String name, String subject, String body, String smsBody, UUID excludeId) {
		List<FieldError> errors = new ArrayList<>();
		String n = trimToNull(name);
		String s = trimToNull(subject);
		String b = (body == null || body.isBlank()) ? null : body.strip();
		String sms = trimToNull(smsBody);
		if (n == null || n.length() > 120) {
			errors.add(new FieldError("name", "must be 1-120 characters"));
		}
		if (s == null || s.length() > 200) {
			errors.add(new FieldError("subject", "must be 1-200 characters"));
		}
		if (b == null || b.length() > MAX_BODY) {
			errors.add(new FieldError("body", "must be 1-" + MAX_BODY + " characters"));
		}
		if (sms != null && sms.length() > MAX_SMS) {
			errors.add(new FieldError("smsBody", "must be at most " + MAX_SMS + " characters"));
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid template", errors);
		}
		renderer.validate("subject", s);
		renderer.validate("body", b);
		renderer.validate("smsBody", sms);
		if (repository.existsActiveName(n, excludeId)) {
			throw new ConflictException("A template with this name already exists");
		}
		return new Copy(n, s, b, sms);
	}

	private CommunicationTemplate load(UUID id) {
		return repository.findById(id).orElseThrow(() -> new NotFoundException("Template not found"));
	}

	private static Map<String, Object> snapshot(CommunicationTemplate template) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("name", template.getName());
		snapshot.put("subject", template.getSubject());
		snapshot.put("body", template.getBody());
		snapshot.put("smsBody", template.getSmsBody());
		snapshot.put("whatsAppStatus", template.getWhatsAppStatus().name());
		return snapshot;
	}

	private static String trimToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	static TemplateView toView(CommunicationTemplate template) {
		return new TemplateView(template.getId(), template.getName(), template.getSubject(), template.getBody(),
				template.getSmsBody(), template.getStatus().name(), template.getWhatsAppStatus().name(),
				template.getWhatsAppTemplateName(), template.getWhatsAppStatusReason(),
				template.getWhatsAppStatusChangedAt(), template.getUpdatedAt());
	}

}
