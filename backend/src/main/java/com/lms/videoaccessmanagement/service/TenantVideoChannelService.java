package com.lms.videoaccessmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.usermanagement.api.TeacherLookupApi;
import com.lms.usermanagement.api.TeacherLookupApi.TeacherSummary;
import com.lms.videoaccessmanagement.domain.TenantVideoConfiguration;
import com.lms.videoaccessmanagement.domain.TenantVideoSettings;
import com.lms.videoaccessmanagement.domain.TenantVideoUploadTeacher;
import com.lms.videoaccessmanagement.repository.TenantVideoConfigurationRepository;
import com.lms.videoaccessmanagement.repository.TenantVideoUploadTeacherRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The institute YouTube channel's own settings (VIDEO Phase 5b): an optional
 * display name / description and which teachers may upload to it - an empty
 * list means every teacher. Tenant Admin only ({@code INTEGRATIONS}, like the
 * channel connection itself); every change is audited with before/after.
 * The allow-list is enforced by {@link MediaUploadService} on every upload.
 */
@Service
public class TenantVideoChannelService {

	static final String AUDIT_ACTION = "video_provider.settings_updated";

	static final int MAX_NAME = 100;

	static final int MAX_DESCRIPTION = 500;

	static final int MAX_TEACHERS = 500;

	public record AllowedTeacher(UUID userId, String name, String email) {

	}

	/**
	 * @param allowedTeachers empty when {@code allTeachersAllowed}
	 * @param availableTeachers every teacher of the institute (by user id) - the choices the list accepts
	 */
	public record ChannelSettingsView(String displayName, String description, boolean allTeachersAllowed,
			List<AllowedTeacher> allowedTeachers, List<AllowedTeacher> availableTeachers) {

	}

	private final TenantVideoConfigurationRepository configurationRepository;

	private final TenantVideoUploadTeacherRepository teacherRepository;

	private final TeacherLookupApi teacherLookupApi;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public TenantVideoChannelService(TenantVideoConfigurationRepository configurationRepository,
			TenantVideoUploadTeacherRepository teacherRepository, TeacherLookupApi teacherLookupApi,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.configurationRepository = configurationRepository;
		this.teacherRepository = teacherRepository;
		this.teacherLookupApi = teacherLookupApi;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public ChannelSettingsView get() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.VIEW);
		return currentView();
	}

	/** Replaces the settings; {@code allowedTeacherIds} empty = all teachers. */
	@Transactional
	public ChannelSettingsView update(String displayName, String description, List<UUID> allowedTeacherIds) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		List<FieldError> errors = new ArrayList<>();
		String name = trimToNull(displayName);
		if (name != null && (name.length() > MAX_NAME || hasAngleBrackets(name))) {
			errors.add(new FieldError("displayName", "must be at most " + MAX_NAME + " characters, without < or >"));
		}
		String desc = trimToNull(description);
		if (desc != null && (desc.length() > MAX_DESCRIPTION || hasAngleBrackets(desc))) {
			errors.add(new FieldError("description",
					"must be at most " + MAX_DESCRIPTION + " characters, without < or >"));
		}
		Set<UUID> wanted = new LinkedHashSet<>((allowedTeacherIds == null) ? List.of() : allowedTeacherIds);
		Set<UUID> teachers = teacherLookupApi.listTeachers()
			.stream()
			.map(TeacherSummary::userId)
			.collect(Collectors.toSet());
		if (wanted.size() > MAX_TEACHERS || wanted.contains(null) || !teachers.containsAll(wanted)) {
			errors.add(new FieldError("allowedTeacherIds", "must list teachers of this institute"));
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid channel settings", errors);
		}

		ChannelSettingsView before = currentView();
		TenantVideoConfiguration configuration = configurationRepository.findForCurrentTenant()
			.orElseGet(() -> new TenantVideoConfiguration(tenantContext.getTenantId(),
					TenantVideoSettings.PLATFORM_DEFAULTS));
		configuration.updateChannel(name, desc);
		configurationRepository.saveAndFlush(configuration);

		List<TenantVideoUploadTeacher> current = teacherRepository.findAll();
		List<TenantVideoUploadTeacher> removed = current.stream()
			.filter(row -> !wanted.contains(row.getTeacherUserId()))
			.toList();
		if (!removed.isEmpty()) {
			teacherRepository.deleteAllInBatch(removed);
		}
		Set<UUID> kept = current.stream()
			.map(TenantVideoUploadTeacher::getTeacherUserId)
			.filter(wanted::contains)
			.collect(Collectors.toSet());
		for (UUID teacherId : wanted) {
			if (!kept.contains(teacherId)) {
				teacherRepository.save(new TenantVideoUploadTeacher(tenantContext.getTenantId(), teacherId));
			}
		}
		teacherRepository.flush();

		ChannelSettingsView after = currentView();
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("before", auditShape(before));
		metadata.put("after", auditShape(after));
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(), AUDIT_ACTION,
				"tenant_video_configuration", configuration.getId(), null, metadata));
		return after;
	}

	/** Whether this teacher may upload to the institute channel. No permission check (callers authorize). */
	@Transactional(readOnly = true)
	public boolean isTeacherAllowedToUpload(UUID teacherUserId) {
		List<TenantVideoUploadTeacher> allowed = teacherRepository.findAll();
		return allowed.isEmpty() || allowed.stream().anyMatch(row -> row.getTeacherUserId().equals(teacherUserId));
	}

	private ChannelSettingsView currentView() {
		TenantVideoConfiguration configuration = configurationRepository.findForCurrentTenant().orElse(null);
		List<UUID> ids = teacherRepository.findAll().stream().map(TenantVideoUploadTeacher::getTeacherUserId).toList();
		List<TeacherSummary> teachers = teacherLookupApi.listTeachers();
		Map<UUID, TeacherSummary> byId = teachers.stream()
			.collect(Collectors.toMap(TeacherSummary::userId, Function.identity(), (a, b) -> a));
		List<AllowedTeacher> allowed = ids.stream()
			.map(id -> {
				TeacherSummary t = byId.get(id);
				return new AllowedTeacher(id, (t == null) ? null : t.name(), (t == null) ? null : t.email());
			})
			.toList();
		List<AllowedTeacher> available = teachers.stream()
			.map(t -> new AllowedTeacher(t.userId(), t.name(), t.email()))
			.toList();
		return new ChannelSettingsView((configuration == null) ? null : configuration.getChannelDisplayName(),
				(configuration == null) ? null : configuration.getChannelDescription(), allowed.isEmpty(), allowed,
				available);
	}

	private static Map<String, Object> auditShape(ChannelSettingsView view) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("displayName", view.displayName());
		map.put("description", view.description());
		map.put("allTeachersAllowed", view.allTeachersAllowed());
		map.put("allowedTeacherIds", view.allowedTeachers().stream().map(t -> t.userId().toString()).toList());
		return map;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static boolean hasAngleBrackets(String value) {
		return value.indexOf('<') >= 0 || value.indexOf('>') >= 0;
	}

}
