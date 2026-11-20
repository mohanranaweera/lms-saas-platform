package com.lms.usermanagement.staff.service;

import com.lms.auditlogmanagement.api.AuditActivityEntry;
import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.logging.ContactMasking;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.api.TeacherLookupApi;
import com.lms.usermanagement.staff.domain.StaffProfile;
import com.lms.usermanagement.staff.repository.StaffProfileRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAFF-MGMT-02 Staff Activities: the tenant's audit feed (every action, not only staff ones),
 * newest first, with resolved actor names and redacted details. Requires
 * {@link Capability#STAFF_ACTIVITY_VIEW} (Tenant Admin / Read-only Auditor); the audit module
 * re-checks {@code AUDIT_LOG/VIEW} + its viewer allowlist and scopes the read to the caller's
 * tenant. Actors resolve to staff/teacher names, else a masked email, else "System".
 */
@Service
@Transactional(readOnly = true)
public class StaffActivityService {

	static final int DEFAULT_PAGE_SIZE = 20;

	static final int MAX_PAGE_SIZE = 50;

	private static final int MAX_ACTOR_MATCHES = 200;

	/** Metadata keys never shown in the feed, at any nesting depth. */
	private static final Pattern SENSITIVE_KEY = Pattern.compile(
			"password|token|secret|hash|otp|credential|signature|cookie|authori[sz]ation|api.?key|url|link",
			Pattern.CASE_INSENSITIVE);

	/** Contact data from other modules' metadata is shown masked, never verbatim. */
	private static final Pattern CONTACT_KEY = Pattern.compile("e.?mail|mobile|phone|contact",
			Pattern.CASE_INSENSITIVE);

	private final AuditLogApi auditLogApi;

	private final PermissionCheckService permissionCheckService;

	private final StaffProfileRepository staffProfileRepository;

	private final TeacherLookupApi teacherLookupApi;

	private final UserProvisioningApi userProvisioningApi;

	public StaffActivityService(AuditLogApi auditLogApi, PermissionCheckService permissionCheckService,
			StaffProfileRepository staffProfileRepository, TeacherLookupApi teacherLookupApi,
			UserProvisioningApi userProvisioningApi) {
		this.auditLogApi = auditLogApi;
		this.permissionCheckService = permissionCheckService;
		this.staffProfileRepository = staffProfileRepository;
		this.teacherLookupApi = teacherLookupApi;
		this.userProvisioningApi = userProvisioningApi;
	}

	public Page<StaffActivity> list(String q, Instant from, Instant to, Integer page, Integer size) {
		permissionCheckService.requireCapability(Capability.STAFF_ACTIVITY_VIEW);
		int safeSize = (size == null) ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		int safePage = (page == null) ? 0 : Math.max(page, 0);
		String term = (q == null || q.isBlank()) ? null : q.strip();

		List<TeacherLookupApi.TeacherSummary> teachers = teacherLookupApi.listTeachers();
		Set<UUID> actorMatches = new LinkedHashSet<>();
		if (term != null) {
			String lower = term.toLowerCase(Locale.ROOT);
			staffProfileRepository.findByNameContaining(term, MAX_ACTOR_MATCHES)
				.forEach(p -> actorMatches.add(p.getUserId()));
			teachers.stream()
				.filter(t -> t.name() != null && t.name().toLowerCase(Locale.ROOT).contains(lower))
				.limit(MAX_ACTOR_MATCHES)
				.forEach(t -> actorMatches.add(t.userId()));
			actorMatches.addAll(userProvisioningApi.findTenantUserIdsByEmailContaining(term, MAX_ACTOR_MATCHES));
		}

		Page<AuditActivityEntry> entries = auditLogApi.searchTenantActivity(term, actorMatches, from, to,
				PageRequest.of(safePage, safeSize));

		List<UUID> actorIds = entries.getContent().stream().map(AuditActivityEntry::actorId).distinct().toList();
		Map<UUID, String> names = new HashMap<>();
		teachers.stream().filter(t -> actorIds.contains(t.userId())).forEach(t -> names.put(t.userId(), t.name()));
		for (StaffProfile profile : staffProfileRepository.findByUserIds(actorIds)) {
			names.put(profile.getUserId(), profile.getName());
		}
		Map<UUID, TenantUserSummary> summaries = new HashMap<>();
		if (!actorIds.isEmpty()) {
			userProvisioningApi.findTenantUserSummaries(actorIds).forEach(s -> summaries.put(s.userId(), s));
		}
		Map<String, String> roleNames = userProvisioningApi.roleDisplayNames();

		return entries.map(entry -> {
			TenantUserSummary summary = summaries.get(entry.actorId());
			String actorName = names.get(entry.actorId());
			if (actorName == null || actorName.isBlank()) {
				actorName = (summary != null) ? ContactMasking.maskEmail(summary.email()) : "System";
			}
			String actorRole = (summary != null) ? roleNames.getOrDefault(summary.roleCode(), summary.roleCode())
					: null;
			return new StaffActivity(entry.id(), entry.action(), humanise(entry.action()), actorName, actorRole,
					entry.targetEntity(), (entry.targetId() == null) ? null : entry.targetId().toString(),
					entry.occurredAt(), redact(entry.metadata()));
		});
	}

	/** {@code staff.role_changed} -> {@code Staff role changed}. */
	static String humanise(String action) {
		if (action == null || action.isBlank()) {
			return "";
		}
		String spaced = action.replace('.', ' ').replace('_', ' ').replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
		return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
	}

	/** Removes sensitive keys recursively; never returns {@code null}. */
	static Map<String, Object> redact(Map<String, Object> metadata) {
		if (metadata == null) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		metadata.forEach((key, value) -> {
			if (key == null || SENSITIVE_KEY.matcher(key).find()) {
				return;
			}
			if (CONTACT_KEY.matcher(key).find() && value instanceof String text) {
				result.put(key, maskContact(text));
				return;
			}
			result.put(key, redactValue(value));
		});
		return result;
	}

	/** "a•••@example.com" for emails, "•••123" otherwise; already-masked values ("•"/"*") pass through. */
	static String maskContact(String text) {
		if (text.contains("•") || text.contains("*")) {
			return text;
		}
		int at = text.indexOf('@');
		if (at > 0) {
			return text.charAt(0) + "•••" + text.substring(at);
		}
		return (text.length() <= 3) ? "•••" : "•••" + text.substring(text.length() - 3);
	}

	@SuppressWarnings("unchecked")
	private static Object redactValue(Object value) {
		if (value instanceof Map<?, ?> map) {
			return redact((Map<String, Object>) map);
		}
		if (value instanceof List<?> list) {
			List<Object> copy = new ArrayList<>(list.size());
			list.forEach(item -> copy.add(redactValue(item)));
			return copy;
		}
		return value;
	}

	/** One feed item (STAFF-MGMT-02 §2.5). {@code details} never contains credential-like or URL keys; contact values are masked. */
	public record StaffActivity(UUID id, String action, String actionLabel, String actorName, String actorRole,
			String targetEntity, String targetReference, Instant occurredAt, Map<String, Object> details) {

	}

}
