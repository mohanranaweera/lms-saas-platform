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
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.TenantMediaIntegration;
import com.lms.integrationmanagement.domain.TenantMediaIntegration.Status;
import com.lms.integrationmanagement.domain.TenantMediaIntegrationTeacher;
import com.lms.integrationmanagement.repository.MediaProviderConnectionRepository;
import com.lms.integrationmanagement.repository.TenantMediaIntegrationRepository;
import com.lms.integrationmanagement.repository.TenantMediaIntegrationTeacherRepository;
import com.lms.usermanagement.api.TeacherLookupApi;
import com.lms.usermanagement.api.TeacherLookupApi.TeacherSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The institute's named YouTube integrations - Klass-style cards (VIDEO
 * Phase 5d, ADR-018 Amendment 5 item 2): name, description and allowed
 * teachers (none = all teachers). At most {@value #MAX_INTEGRATIONS} live
 * per institute. {@code INTEGRATIONS} VIEW / CREATE_EDIT; every id is
 * resolved through the tenant filter, so another institute's card is "not
 * found". Deleting a card disconnects its channel (revoked at Google) and
 * keeps every row; its videos move to the Media Library's Archived tab.
 */
@Service
public class TenantMediaIntegrationService {

	public static final int MAX_INTEGRATIONS = 10;

	static final int MAX_NAME = 100;

	static final int MAX_DESCRIPTION = 500;

	static final int MAX_TEACHERS = 500;

	/** A teacher by USER id (the ids the allowed-teacher list takes). */
	public record IntegrationTeacher(UUID userId, String name, String email) {

	}

	/** One card: the integration and its allowed teachers (empty = all teachers). */
	public record IntegrationDetails(TenantMediaIntegration integration, List<IntegrationTeacher> allowedTeachers) {

	}

	private final TenantMediaIntegrationRepository integrations;

	private final TenantMediaIntegrationTeacherRepository teachers;

	private final MediaProviderConnectionRepository connections;

	private final MediaConnectionService connectionService;

	private final TeacherLookupApi teacherLookupApi;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final TransactionTemplate transactions;

	public TenantMediaIntegrationService(TenantMediaIntegrationRepository integrations,
			TenantMediaIntegrationTeacherRepository teachers, MediaProviderConnectionRepository connections,
			MediaConnectionService connectionService, TeacherLookupApi teacherLookupApi,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext,
			PlatformTransactionManager transactionManager) {
		this.integrations = integrations;
		this.teachers = teachers;
		this.connections = connections;
		this.connectionService = connectionService;
		this.teacherLookupApi = teacherLookupApi;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	/** The institute's live YouTube cards with their teacher lists, oldest first. */
	@Transactional(readOnly = true)
	public List<IntegrationDetails> listYouTube() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.VIEW);
		Map<UUID, TeacherSummary> byId = teachersById();
		return integrations.findForProvider(MediaProvider.YOUTUBE, List.of(Status.ACTIVE))
			.stream()
			.map(integration -> details(integration, byId))
			.toList();
	}

	/** Every teacher of the institute by user id - the choices for an allowed-teacher list. */
	@Transactional(readOnly = true)
	public List<IntegrationTeacher> teacherChoices() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.VIEW);
		return teacherLookupApi.listTeachers()
			.stream()
			.map(t -> new IntegrationTeacher(t.userId(), t.name(), t.email()))
			.toList();
	}

	@Transactional
	public IntegrationDetails create(String name, String description, List<UUID> allowedTeacherIds) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		Validated input = validate(name, description, allowedTeacherIds);
		if (integrations.countActive(MediaProvider.YOUTUBE) >= MAX_INTEGRATIONS) {
			throw new ProviderConflictException(ApiErrorCodes.VIDEO_INTEGRATION_LIMIT,
					"Your institute can have up to " + MAX_INTEGRATIONS + " YouTube integrations.");
		}
		requireNameFree(input.name(), null);
		TenantMediaIntegration saved = saveIntegration(
				new TenantMediaIntegration(tenantContext.getTenantId(), MediaProvider.YOUTUBE, input.name(),
						input.description()));
		replaceTeachers(saved.getId(), input.teacherIds());
		IntegrationDetails details = details(saved, teachersById());
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("after", auditShape(details));
		audit("video_integration.created", saved.getId(), metadata);
		return details;
	}

	@Transactional
	public IntegrationDetails update(UUID integrationId, String name, String description,
			List<UUID> allowedTeacherIds) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		TenantMediaIntegration integration = requireActive(integrationId);
		Validated input = validate(name, description, allowedTeacherIds);
		requireNameFree(input.name(), integrationId);
		Map<UUID, TeacherSummary> byId = teachersById();
		IntegrationDetails before = details(integration, byId);
		integration.rename(input.name(), input.description());
		saveIntegration(integration);
		replaceTeachers(integrationId, input.teacherIds());
		IntegrationDetails after = details(integration, byId);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("before", auditShape(before));
		metadata.put("after", auditShape(after));
		audit("video_integration.updated", integrationId, metadata);
		return after;
	}

	/**
	 * Deletes a card: its live channel is disconnected first (that revokes the
	 * grant at Google - an outside call, so not inside this method's
	 * transaction), then the card is marked DELETED and audited. Its videos
	 * stay; the Media Library lists them under Archived.
	 */
	public void delete(UUID integrationId) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		requireActive(integrationId);
		connections.findCurrentForIntegration(integrationId)
			.ifPresent(live -> connectionService.adminDisconnect(live.getId(), "The integration was deleted"));
		transactions.executeWithoutResult(status -> {
			TenantMediaIntegration integration = requireActive(integrationId);
			integration.markDeleted(IntegrationActors.currentUserId(), Instant.now());
			saveIntegration(integration);
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("name", integration.getName());
			audit("video_integration.deleted", integrationId, metadata);
		});
	}

	/** 404 unless this is the caller's own live YouTube card. */
	@Transactional(readOnly = true)
	public TenantMediaIntegration requireActive(UUID integrationId) {
		return integrations.findById(integrationId)
			.filter(i -> i.getProvider() == MediaProvider.YOUTUBE && i.isActive())
			.orElseThrow(() -> new NotFoundException("Integration not found"));
	}

	/** Allowed teacher user ids of a card; empty = every teacher. No permission check (callers authorize). */
	@Transactional(readOnly = true)
	public Set<UUID> allowedTeacherIds(UUID integrationId) {
		return teachers.findForIntegration(integrationId)
			.stream()
			.map(TenantMediaIntegrationTeacher::getTeacherUserId)
			.collect(Collectors.toSet());
	}

	private IntegrationDetails details(TenantMediaIntegration integration, Map<UUID, TeacherSummary> byId) {
		List<IntegrationTeacher> allowed = teachers.findForIntegration(integration.getId())
			.stream()
			.map(row -> {
				TeacherSummary t = byId.get(row.getTeacherUserId());
				return new IntegrationTeacher(row.getTeacherUserId(), (t == null) ? null : t.name(),
						(t == null) ? null : t.email());
			})
			.toList();
		return new IntegrationDetails(integration, allowed);
	}

	private Map<UUID, TeacherSummary> teachersById() {
		return teacherLookupApi.listTeachers()
			.stream()
			.collect(Collectors.toMap(TeacherSummary::userId, Function.identity(), (a, b) -> a));
	}

	private void replaceTeachers(UUID integrationId, Set<UUID> wanted) {
		List<TenantMediaIntegrationTeacher> current = teachers.findForIntegration(integrationId);
		List<TenantMediaIntegrationTeacher> removed = current.stream()
			.filter(row -> !wanted.contains(row.getTeacherUserId()))
			.toList();
		if (!removed.isEmpty()) {
			teachers.deleteAllInBatch(removed);
		}
		Set<UUID> kept = current.stream()
			.map(TenantMediaIntegrationTeacher::getTeacherUserId)
			.filter(wanted::contains)
			.collect(Collectors.toSet());
		for (UUID teacherId : wanted) {
			if (!kept.contains(teacherId)) {
				teachers.save(new TenantMediaIntegrationTeacher(tenantContext.getTenantId(), integrationId, teacherId));
			}
		}
		teachers.flush();
	}

	private TenantMediaIntegration saveIntegration(TenantMediaIntegration integration) {
		try {
			return integrations.saveAndFlush(integration);
		}
		catch (DataIntegrityViolationException ex) {
			// uq_tenant_media_integration_name - a concurrent create/rename won the name.
			throw nameTaken();
		}
	}

	private void requireNameFree(String name, UUID exceptId) {
		String wanted = name.toLowerCase(Locale.ROOT);
		boolean taken = integrations.findForProvider(MediaProvider.YOUTUBE, List.of(Status.ACTIVE))
			.stream()
			.anyMatch(i -> !i.getId().equals(exceptId) && i.getName().toLowerCase(Locale.ROOT).equals(wanted));
		if (taken) {
			throw nameTaken();
		}
	}

	private static ProviderConflictException nameTaken() {
		return new ProviderConflictException(ApiErrorCodes.VIDEO_INTEGRATION_NAME_TAKEN,
				"Another integration already has this name.");
	}

	private record Validated(String name, String description, Set<UUID> teacherIds) {

	}

	private Validated validate(String name, String description, List<UUID> allowedTeacherIds) {
		List<FieldError> errors = new ArrayList<>();
		String trimmedName = (name == null) ? "" : name.trim();
		if (trimmedName.isEmpty() || trimmedName.length() > MAX_NAME || hasAngleBrackets(trimmedName)) {
			errors.add(new FieldError("name", "is required (at most " + MAX_NAME + " characters, without < or >)"));
		}
		String desc = (description == null || description.isBlank()) ? null : description.trim();
		if (desc != null && (desc.length() > MAX_DESCRIPTION || hasAngleBrackets(desc))) {
			errors.add(new FieldError("description",
					"must be at most " + MAX_DESCRIPTION + " characters, without < or >"));
		}
		Set<UUID> wanted = new LinkedHashSet<>((allowedTeacherIds == null) ? List.of() : allowedTeacherIds);
		Set<UUID> institute = teacherLookupApi.listTeachers()
			.stream()
			.map(TeacherSummary::userId)
			.collect(Collectors.toSet());
		if (wanted.size() > MAX_TEACHERS || wanted.contains(null) || !institute.containsAll(wanted)) {
			errors.add(new FieldError("allowedTeacherIds", "must list teachers of this institute"));
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid integration", errors);
		}
		return new Validated(trimmedName, desc, wanted);
	}

	private static boolean hasAngleBrackets(String value) {
		return value.indexOf('<') >= 0 || value.indexOf('>') >= 0;
	}

	private static Map<String, Object> auditShape(IntegrationDetails details) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("name", details.integration().getName());
		map.put("description", Objects.requireNonNullElse(details.integration().getDescription(), ""));
		map.put("allowedTeacherIds",
				details.allowedTeachers().stream().map(t -> t.userId().toString()).toList());
		return map;
	}

	private void audit(String action, UUID integrationId, Map<String, Object> metadata) {
		auditLogApi.record(new AuditLogEntry(IntegrationActors.currentUserId(), action, "tenant_media_integration",
				integrationId, null, metadata));
	}

}
