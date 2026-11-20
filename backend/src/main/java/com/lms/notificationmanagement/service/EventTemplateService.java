package com.lms.notificationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.notificationmanagement.domain.NotificationEventType;
import com.lms.notificationmanagement.domain.NotificationTemplate;
import com.lms.notificationmanagement.repository.NotificationTemplateRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant Admin editing of the transactional ("event") email templates - the
 * existing {@code notification_template} rows (Wave 11 §2.4, closes the
 * template-origination/authoring gap in open-decisions §22). Each key has a
 * fixed variable allow-list (what its dispatcher supplies); credential
 * templates must keep {@code {{link}}}. VIEW: {@code COMMUNICATIONS}/VIEW;
 * edit: {@code COMMUNICATIONS}/CREATE_EDIT <b>and</b> the Tenant Admin role
 * (these messages carry payment and account-security content). Every edit is
 * audit-logged with before/after copy.
 */
@Service
public class EventTemplateService {

	private static final String TARGET_ENTITY = "notification_template";

	private static final Pattern TOKEN = Pattern.compile("\\{\\{(\\w+)}}");

	private static final Map<NotificationEventType, Set<String>> ALLOWED = Map.of(
			NotificationEventType.PAYMENT_CONFIRMED, Set.of("amount", "currency", "paymentId"),
			NotificationEventType.PAYMENT_REJECTED, Set.of("amount", "currency", "paymentId"),
			NotificationEventType.PAYMENT_REFUNDED, Set.of("amount", "reason", "paymentId"),
			NotificationEventType.ACCOUNT_SETUP_INVITE, Set.of("link", "tenantName", "expiresIn"),
			NotificationEventType.PASSWORD_RESET, Set.of("link", "tenantName", "expiresIn"));

	private static final Map<NotificationEventType, Set<String>> REQUIRED = Map.of(
			NotificationEventType.ACCOUNT_SETUP_INVITE, Set.of("link"), NotificationEventType.PASSWORD_RESET,
			Set.of("link"));

	private final NotificationTemplateRepository repository;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public EventTemplateService(NotificationTemplateRepository repository,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.repository = repository;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	public record EventTemplateView(String key, boolean configured, String subject, String body,
			List<String> variables, List<String> requiredVariables, Instant updatedAt) {

	}

	@Transactional(readOnly = true)
	public List<EventTemplateView> list() {
		permissionCheckService.requirePermission(DomainArea.COMMUNICATIONS, PermissionAction.VIEW);
		List<EventTemplateView> views = new ArrayList<>();
		for (NotificationEventType type : NotificationEventType.values()) {
			Optional<NotificationTemplate> row = repository.findByTemplateKey(type.name());
			views.add(toView(type, row.orElse(null)));
		}
		return views;
	}

	@Transactional
	public EventTemplateView update(String key, String subject, String body) {
		permissionCheckService.requirePermission(DomainArea.COMMUNICATIONS, PermissionAction.CREATE_EDIT);
		if (!"TENANT_ADMIN".equals(CommunicationActors.currentRole())) {
			throw new AccessDeniedException("Only the Tenant Admin can edit system message templates");
		}
		NotificationEventType type = Arrays.stream(NotificationEventType.values())
			.filter(candidate -> candidate.name().equals(key))
			.findFirst()
			.orElseThrow(() -> new NotFoundException("Template not found"));
		String s = (subject == null) ? "" : subject.trim();
		String b = (body == null) ? "" : body.strip();
		List<FieldError> errors = new ArrayList<>();
		if (s.isEmpty() || s.length() > 200) {
			errors.add(new FieldError("subject", "must be 1-200 characters"));
		}
		if (b.isEmpty() || b.length() > 5000) {
			errors.add(new FieldError("body", "must be 1-5000 characters"));
		}
		checkVariables(type, "subject", s, errors, false);
		checkVariables(type, "body", b, errors, true);
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid template", errors);
		}

		Optional<NotificationTemplate> existing = repository.findByTemplateKey(type.name());
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("key", type.name());
		existing.ifPresent(row -> metadata.put("before", Map.of("subject", row.getSubject(), "body", row.getBody())));
		NotificationTemplate template = existing.orElseGet(
				() -> new NotificationTemplate(tenantContext.getTenantId(), type.name(), s, b));
		template.updateContent(s, b);
		NotificationTemplate saved = repository.saveAndFlush(template);
		metadata.put("after", Map.of("subject", s, "body", b));
		auditLogApi.record(new AuditLogEntry(CommunicationActors.currentUserId(), "notification_template.updated",
				TARGET_ENTITY, saved.getId(), null, metadata));
		return toView(type, saved);
	}

	private static void checkVariables(NotificationEventType type, String field, String text, List<FieldError> errors,
			boolean enforceRequired) {
		Set<String> allowed = ALLOWED.getOrDefault(type, Set.of());
		Set<String> found = new java.util.HashSet<>();
		Matcher matcher = TOKEN.matcher(text);
		while (matcher.find()) {
			found.add(matcher.group(1));
			if (!allowed.contains(matcher.group(1))) {
				errors.add(new FieldError(field, "uses unknown variable {{" + matcher.group(1) + "}}"));
			}
		}
		if (enforceRequired) {
			for (String required : REQUIRED.getOrDefault(type, Set.of())) {
				if (!found.contains(required)) {
					errors.add(new FieldError(field, "must include {{" + required + "}}"));
				}
			}
		}
	}

	private static EventTemplateView toView(NotificationEventType type, NotificationTemplate row) {
		List<String> variables = ALLOWED.getOrDefault(type, Set.of()).stream().sorted().toList();
		List<String> required = REQUIRED.getOrDefault(type, Set.of()).stream().sorted().toList();
		if (row == null) {
			return new EventTemplateView(type.name(), false, null, null, variables, required, null);
		}
		return new EventTemplateView(type.name(), true, row.getSubject(), row.getBody(), variables, required,
				row.getUpdatedAt());
	}

}
