package com.lms.integrationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.domain.ZoomExternalUser;
import com.lms.integrationmanagement.domain.ZoomExternalUserStatus;
import com.lms.integrationmanagement.domain.ZoomIntegration;
import com.lms.integrationmanagement.domain.ZoomIntegrationAllowedTeacher;
import com.lms.integrationmanagement.domain.ZoomTeacherPolicy;
import com.lms.integrationmanagement.domain.ZoomTeacherUserMapping;
import com.lms.integrationmanagement.repository.ZoomExternalUserRepository;
import com.lms.integrationmanagement.repository.ZoomIntegrationAllowedTeacherRepository;
import com.lms.integrationmanagement.repository.ZoomIntegrationRepository;
import com.lms.integrationmanagement.repository.ZoomTeacherUserMappingRepository;
import com.lms.integrationmanagement.service.ZoomIntegrationViews.ExternalUserView;
import com.lms.integrationmanagement.service.ZoomIntegrationViews.IntegrationView;
import com.lms.integrationmanagement.service.ZoomIntegrationViews.MappingView;
import com.lms.integrationmanagement.service.ZoomIntegrationViews.TeacherView;
import com.lms.usermanagement.api.TeacherLookupApi;
import com.lms.usermanagement.api.TeacherLookupApi.TeacherSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ZoomIntegrationFoundationService {

	private static final int MAX_INTEGRATIONS = 10;

	private final ZoomIntegrationRepository integrations;

	private final ZoomIntegrationAllowedTeacherRepository allowedTeachers;

	private final ZoomExternalUserRepository externalUsers;

	private final ZoomTeacherUserMappingRepository mappings;

	private final TeacherLookupApi teacherLookupApi;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public ZoomIntegrationFoundationService(ZoomIntegrationRepository integrations,
			ZoomIntegrationAllowedTeacherRepository allowedTeachers, ZoomExternalUserRepository externalUsers,
			ZoomTeacherUserMappingRepository mappings, TeacherLookupApi teacherLookupApi,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.integrations = integrations;
		this.allowedTeachers = allowedTeachers;
		this.externalUsers = externalUsers;
		this.mappings = mappings;
		this.teacherLookupApi = teacherLookupApi;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public List<IntegrationView> list() {
		requireViewPermission();
		Map<UUID, TeacherSummary> teachers = teacherSummaries();
		return integrations.findAllVisible().stream().map(integration -> toView(integration, teachers)).toList();
	}

	@Transactional(readOnly = true)
	public IntegrationView get(UUID integrationId) {
		requireViewPermission();
		return toView(requireVisible(integrationId), teacherSummaries());
	}

	@Transactional
	public IntegrationView create(String name, String description, ZoomTeacherPolicy teacherPolicy,
			List<UUID> allowedTeacherIds) {
		requireEditPermission();
		ValidatedInput input = validateInput(name, description, teacherPolicy, allowedTeacherIds);
		if (integrations.countVisible() >= MAX_INTEGRATIONS) {
			throw new ProviderConflictException(ApiErrorCodes.CONFLICT,
					"Your institute can have up to " + MAX_INTEGRATIONS + " Zoom integrations.");
		}
		requireNameFree(input.name(), null);
		ZoomIntegration saved;
		try {
			saved = integrations.saveAndFlush(new ZoomIntegration(tenantContext.getTenantId(), input.name(),
					input.description(), input.teacherPolicy()));
		}
		catch (DataIntegrityViolationException ex) {
			throw new ProviderConflictException(ApiErrorCodes.CONFLICT,
					"Another Zoom integration already has this name.");
		}
		replaceAllowedTeachers(saved.getId(), input.allowedTeacherIds());
		audit("zoom_integration.created", saved.getId(), Map.of("name", saved.getName(),
				"teacherPolicy", saved.getTeacherPolicy().name()));
		return toView(saved, teacherSummaries());
	}

	@Transactional
	public IntegrationView update(UUID integrationId, String name, String description, ZoomTeacherPolicy teacherPolicy,
			List<UUID> allowedTeacherIds) {
		requireEditPermission();
		ZoomIntegration integration = requireVisible(integrationId);
		ValidatedInput input = validateInput(name, description, teacherPolicy, allowedTeacherIds);
		requireNameFree(input.name(), integrationId);
		Map<String, Object> before = Map.of("name", integration.getName(), "description",
				integration.getDescription() == null ? "" : integration.getDescription(), "teacherPolicy",
				integration.getTeacherPolicy().name());
		integration.updateDetails(input.name(), input.description(), input.teacherPolicy());
		replaceAllowedTeachers(integrationId, input.allowedTeacherIds());
		Map<String, Object> after = Map.of("name", integration.getName(), "description",
				integration.getDescription() == null ? "" : integration.getDescription(), "teacherPolicy",
				integration.getTeacherPolicy().name());
		audit("zoom_integration.updated", integrationId, Map.of("before", before, "after", after));
		return toView(integration, teacherSummaries());
	}

	@Transactional
	public void archive(UUID integrationId) {
		requireEditPermission();
		ZoomIntegration integration = requireVisible(integrationId);
		integration.archive(IntegrationActors.currentUserId(), Instant.now());
		audit("zoom_integration.archived", integrationId, Map.of("name", integration.getName()));
	}

	@Transactional(readOnly = true)
	public List<ExternalUserView> listExternalUsers(UUID integrationId) {
		requireViewPermission();
		requireVisible(integrationId);
		return externalUsers.findForIntegration(integrationId).stream().map(this::toExternalUserView).toList();
	}

	@Transactional
	public IntegrationView mapTeacher(UUID integrationId, UUID teacherUserId, UUID externalUserId) {
		requireEditPermission();
		ZoomIntegration integration = requireVisible(integrationId);
		if (!isAllowed(integration, teacherUserId)) {
			throw new ProviderConflictException(ApiErrorCodes.FORBIDDEN,
					"The teacher is not allowed to use this Zoom integration.");
		}
		requireTeacher(teacherUserId);
		ZoomExternalUser externalUser = externalUsers.findForIntegrationById(integrationId, externalUserId)
			.orElseThrow(() -> new NotFoundException("Zoom external user not found"));
		if (externalUser.getStatus() == ZoomExternalUserStatus.DELETED
				|| externalUser.getStatus() == ZoomExternalUserStatus.INACTIVE) {
			throw new ProviderConflictException(ApiErrorCodes.CONFLICT, "The selected Zoom user is not active.");
		}
		if (mappings.findActiveForTeacher(integrationId, teacherUserId).isPresent()
				|| mappings.findActiveForExternalUser(integrationId, externalUserId).isPresent()) {
			throw new ProviderConflictException(ApiErrorCodes.CONFLICT,
					"The teacher or Zoom user already has an active mapping for this integration.");
		}
		mappings.saveAndFlush(new ZoomTeacherUserMapping(tenantContext.getTenantId(), integrationId, teacherUserId,
				externalUserId, Instant.now()));
		audit("zoom_teacher_user_mapping.created", integrationId,
				Map.of("teacherUserId", teacherUserId.toString(), "externalUserId", externalUser.getZoomUserId()));
		return toView(integration, teacherSummaries());
	}

	@Transactional
	public void revokeMapping(UUID integrationId, UUID teacherUserId) {
		requireEditPermission();
		requireVisible(integrationId);
		ZoomTeacherUserMapping mapping = mappings.findActiveForTeacher(integrationId, teacherUserId)
			.orElseThrow(() -> new NotFoundException("Zoom teacher mapping not found"));
		mapping.revoke(Instant.now());
		audit("zoom_teacher_user_mapping.revoked", integrationId,
				Map.of("teacherUserId", teacherUserId.toString(), "externalUserId",
						mapping.getZoomExternalUserId().toString()));
	}

	private void requireViewPermission() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.VIEW);
	}

	private void requireEditPermission() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
	}

	private ZoomIntegration requireVisible(UUID integrationId) {
		return integrations.findVisibleById(integrationId)
			.orElseThrow(() -> new NotFoundException("Zoom integration not found"));
	}

	private boolean isAllowed(ZoomIntegration integration, UUID teacherUserId) {
		return integration.getTeacherPolicy() == ZoomTeacherPolicy.ALL_TEACHERS
				|| allowedTeachers.existsForIntegrationAndTeacher(integration.getId(), teacherUserId);
	}

	private void requireTeacher(UUID teacherUserId) {
		boolean exists = teacherLookupApi.listTeachers().stream().anyMatch(t -> t.userId().equals(teacherUserId));
		if (!exists) {
			throw new NotFoundException("Teacher not found");
		}
	}

	private void replaceAllowedTeachers(UUID integrationId, Set<UUID> wanted) {
		List<ZoomIntegrationAllowedTeacher> current = allowedTeachers.findForIntegration(integrationId);
		List<ZoomIntegrationAllowedTeacher> removed = current.stream()
			.filter(row -> !wanted.contains(row.getTeacherUserId())).toList();
		if (!removed.isEmpty()) {
			allowedTeachers.deleteAllInBatch(removed);
		}
		Set<UUID> kept = current.stream().map(ZoomIntegrationAllowedTeacher::getTeacherUserId)
			.filter(wanted::contains).collect(Collectors.toSet());
		for (UUID teacherId : wanted) {
			if (!kept.contains(teacherId)) {
				allowedTeachers.save(new ZoomIntegrationAllowedTeacher(tenantContext.getTenantId(), integrationId, teacherId));
			}
		}
		allowedTeachers.flush();
	}

	private ValidatedInput validateInput(String name, String description, ZoomTeacherPolicy teacherPolicy,
			List<UUID> allowedTeacherIds) {
		List<FieldError> errors = new ArrayList<>();
		String trimmedName = name == null ? "" : name.trim();
		if (trimmedName.isEmpty() || trimmedName.length() > 100 || hasAngleBrackets(trimmedName)) {
			errors.add(new FieldError("name", "is required (at most 100 characters, without < or >)"));
		}
		String trimmedDescription = description == null || description.isBlank() ? null : description.trim();
		if (trimmedDescription != null && (trimmedDescription.length() > 500 || hasAngleBrackets(trimmedDescription))) {
			errors.add(new FieldError("description", "must be at most 500 characters, without < or >"));
		}
		Set<UUID> wanted = new LinkedHashSet<>(allowedTeacherIds == null ? List.of() : allowedTeacherIds);
		Set<UUID> instituteTeachers = teacherLookupApi.listTeachers().stream().map(TeacherSummary::userId)
			.collect(Collectors.toSet());
		if (wanted.size() > 500 || wanted.contains(null) || !instituteTeachers.containsAll(wanted)) {
			errors.add(new FieldError("allowedTeacherIds", "must list teachers of this institute"));
		}
		if (teacherPolicy == null) {
			errors.add(new FieldError("teacherPolicy", "is required"));
		}
		else if (teacherPolicy == ZoomTeacherPolicy.ALL_TEACHERS && !wanted.isEmpty()) {
			errors.add(new FieldError("allowedTeacherIds", "must be empty when teacherPolicy is ALL_TEACHERS"));
		}
		else if (teacherPolicy == ZoomTeacherPolicy.SELECTED_TEACHERS && wanted.isEmpty()) {
			errors.add(new FieldError("allowedTeacherIds", "must contain at least one teacher"));
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid Zoom integration", errors);
		}
		return new ValidatedInput(trimmedName, trimmedDescription, teacherPolicy, wanted);
	}

	private void requireNameFree(String name, UUID exceptId) {
		String wanted = name.toLowerCase(java.util.Locale.ROOT);
		boolean taken = integrations.findAllVisible().stream()
			.anyMatch(i -> !i.getId().equals(exceptId) && i.getName().toLowerCase(java.util.Locale.ROOT).equals(wanted));
		if (taken) {
			throw new ProviderConflictException(ApiErrorCodes.CONFLICT, "Another Zoom integration already has this name.");
		}
	}

	private Map<UUID, TeacherSummary> teacherSummaries() {
		return teacherLookupApi.listTeachers().stream()
			.collect(Collectors.toMap(TeacherSummary::userId, Function.identity(), (first, ignored) -> first));
	}

	private IntegrationView toView(ZoomIntegration integration, Map<UUID, TeacherSummary> teacherMap) {
		List<TeacherView> allowed = allowedTeachers.findForIntegration(integration.getId()).stream()
			.map(row -> teacherView(row.getTeacherUserId(), teacherMap)).toList();
		List<MappingView> mappingViews = mappings.findForIntegration(integration.getId()).stream()
			.map(mapping -> toMappingView(mapping, teacherMap)).toList();
		return new IntegrationView(integration.getId(), integration.getName(), integration.getDescription(),
			integration.getStatus(), integration.getTeacherPolicy(), integration.getHostStrategy(), integration.getReadiness(),
			integration.getZoomAccountLabel(), integration.getConnectedAt(), integration.getLastVerifiedAt(),
			integration.getLastError(), allowed, mappingViews);
	}

	private MappingView toMappingView(ZoomTeacherUserMapping mapping, Map<UUID, TeacherSummary> teacherMap) {
		ZoomExternalUser external = externalUsers.findById(mapping.getZoomExternalUserId()).orElse(null);
		return new MappingView(mapping.getId(), mapping.getTeacherUserId(), teacherView(mapping.getTeacherUserId(), teacherMap),
			mapping.getZoomExternalUserId(), external == null ? null : toExternalUserView(external), mapping.getStatus(),
			mapping.getMappedAt(), mapping.getRevokedAt());
	}

	private TeacherView teacherView(UUID userId, Map<UUID, TeacherSummary> teacherMap) {
		TeacherSummary teacher = teacherMap.get(userId);
		return teacher == null ? new TeacherView(userId, null, null) : new TeacherView(userId, teacher.name(), teacher.email());
	}

	private ExternalUserView toExternalUserView(ZoomExternalUser user) {
		return new ExternalUserView(user.getId(), user.getZoomUserId(), user.getEmail(), user.getDisplayName(),
			user.getStatus(), user.getZoomUserType(), user.getHostCapability(), user.getWebinarCapability(),
			user.getLastSyncedAt(), user.getCapabilityCheckedAt());
	}

	private void audit(String action, UUID targetId, Map<String, Object> metadata) {
		auditLogApi.record(new AuditLogEntry(IntegrationActors.currentUserId(), action, "zoom_integration", targetId, null,
				metadata));
	}

	private static boolean hasAngleBrackets(String value) {
		return value.indexOf('<') >= 0 || value.indexOf('>') >= 0;
	}

	private record ValidatedInput(String name, String description, ZoomTeacherPolicy teacherPolicy,
			Set<UUID> allowedTeacherIds) {
	}

}
