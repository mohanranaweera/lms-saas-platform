package com.lms.tenantmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.StudentProfileField;
import com.lms.tenantmanagement.api.StudentProfileFieldConfigApi;
import com.lms.tenantmanagement.api.StudentProfileFieldPolicy;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.domain.StudentProfileFieldSetting;
import com.lms.tenantmanagement.repository.StudentProfileFieldSettingRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-configurable student profile fields (STU-PROF-01): resolves each
 * catalogue field's effective policy for the current tenant and replaces it
 * on a Tenant Admin save.
 *
 * <p>Resolution per field: the persisted {@link StudentProfileFieldSetting}
 * row if one exists, else {@link StudentProfileField#defaultPolicy} (which
 * reads the legacy {@code ConfigDomain.STUDENT} {@code require_*} flags), so
 * a tenant that never saved behaves exactly as before. A save writes a row
 * for every non-locked catalogue field, after which the legacy flags are no
 * longer read for that tenant.
 *
 * <p>Tenant identity is always the request's {@link TenantContext}; the
 * repository is tenant-scoped structurally.
 */
@Service
public class StudentProfileFieldConfigService implements StudentProfileFieldConfigApi {

	private final StudentProfileFieldSettingRepository repository;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	private final AuditLogApi auditLogApi;

	private final PermissionCheckService permissionCheckService;

	public StudentProfileFieldConfigService(StudentProfileFieldSettingRepository repository,
			TenantConfigApi tenantConfigApi, TenantContext tenantContext, AuditLogApi auditLogApi,
			PermissionCheckService permissionCheckService) {
		this.repository = repository;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
		this.auditLogApi = auditLogApi;
		this.permissionCheckService = permissionCheckService;
	}

	@Override
	@Transactional(readOnly = true)
	public List<StudentProfileFieldPolicy> resolveFieldPolicies(UUID tenantId) {
		if (tenantId == null || !tenantId.equals(tenantContext.getTenantId())) {
			throw new IllegalStateException(
					"resolveFieldPolicies was called with a tenantId that does not match the current request's "
							+ "resolved TenantContext - cross-tenant/background calls are not supported");
		}
		return getFieldPolicies();
	}

	/**
	 * The Tenant Admin settings read: same as {@link #getFieldPolicies()},
	 * re-checking {@code BRANDING_SETTINGS}/{@code VIEW} as defense in depth
	 * on top of the controller's {@code @PreAuthorize}.
	 */
	@Transactional(readOnly = true)
	public List<StudentProfileFieldPolicy> getSettings() {
		permissionCheckService.requirePermission(DomainArea.BRANDING_SETTINGS, PermissionAction.VIEW);
		return getFieldPolicies();
	}

	/**
	 * Every catalogue field's effective policy for the current tenant, in
	 * catalogue order. Deliberately not permission-gated: it also backs the
	 * unauthenticated public registration policy and registration/profile
	 * validation, none of which expose anything beyond what those flows need.
	 */
	@Transactional(readOnly = true)
	public List<StudentProfileFieldPolicy> getFieldPolicies() {
		Map<StudentProfileField, StudentProfileFieldSetting> persisted = persistedByField();
		Map<String, Object> legacyConfig = persisted.isEmpty()
				? tenantConfigApi.resolveDomain(tenantContext.getTenantId(), ConfigDomain.STUDENT) : Map.of();
		List<StudentProfileFieldPolicy> policies = new ArrayList<>();
		for (StudentProfileField field : StudentProfileField.values()) {
			StudentProfileFieldSetting row = field.locked() ? null : persisted.get(field);
			policies.add(row != null
					? new StudentProfileFieldPolicy(field, row.isEnabled(), row.isRequired(),
							row.isCollectAtRegistration())
					: field.defaultPolicy(legacyConfig));
		}
		return policies;
	}

	/**
	 * Applies {@code changes} over the current effective settings and
	 * persists a row for every non-locked field, all-or-nothing. Fields not
	 * named in {@code changes} keep their current effective state. Rejects
	 * (400, one {@link FieldError} per problem) an unknown or locked key, a
	 * duplicate key, or {@code required}/{@code collectAtRegistration}
	 * without {@code enabled}. Writes one audit entry listing the changed
	 * fields' before/after flags (flags only - no student data is involved).
	 */
	@Transactional
	public List<StudentProfileFieldPolicy> updateFieldPolicies(List<StudentProfileFieldSettingChange> changes) {
		permissionCheckService.requirePermission(DomainArea.BRANDING_SETTINGS, PermissionAction.CREATE_EDIT);
		Map<StudentProfileField, StudentProfileFieldSettingChange> requested = validate(
				(changes == null) ? List.of() : changes);

		List<StudentProfileFieldPolicy> before = getFieldPolicies();
		Map<StudentProfileField, StudentProfileFieldSetting> persisted = persistedByField();
		UUID tenantId = tenantContext.getTenantId();
		List<Map<String, Object>> auditChanges = new ArrayList<>();

		for (StudentProfileFieldPolicy current : before) {
			StudentProfileField field = current.field();
			if (field.locked()) {
				continue;
			}
			StudentProfileFieldSettingChange change = requested.get(field);
			boolean enabled = (change != null) ? change.enabled() : current.enabled();
			boolean required = (change != null) ? change.required() : current.required();
			boolean collect = (change != null) ? change.collectAtRegistration() : current.collectAtRegistration();

			StudentProfileFieldSetting row = persisted.get(field);
			if (row == null) {
				repository.save(new StudentProfileFieldSetting(tenantId, field.name(), enabled, required, collect));
			}
			else {
				row.apply(enabled, required, collect);
				repository.save(row);
			}

			if (enabled != current.enabled() || required != current.required()
					|| collect != current.collectAtRegistration()) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("key", field.name());
				entry.put("before", flags(current.enabled(), current.required(), current.collectAtRegistration()));
				entry.put("after", flags(enabled, required, collect));
				auditChanges.add(entry);
			}
		}

		if (!auditChanges.isEmpty()) {
			auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(),
					"student_profile_fields.updated", "tenant", tenantId, null, Map.of("changes", auditChanges)));
		}
		return getFieldPolicies();
	}

	private static Map<StudentProfileField, StudentProfileFieldSettingChange> validate(
			List<StudentProfileFieldSettingChange> changes) {
		List<FieldError> errors = new ArrayList<>();
		Map<StudentProfileField, StudentProfileFieldSettingChange> requested = new EnumMap<>(StudentProfileField.class);
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < changes.size(); i++) {
			StudentProfileFieldSettingChange change = changes.get(i);
			String path = "fields[" + i + "]";
			Optional<StudentProfileField> field = StudentProfileField.fromKey(change.key());
			if (field.isEmpty()) {
				errors.add(new FieldError(path + ".key", "Unknown student profile field '" + change.key() + "'"));
			}
			else if (!seen.add(change.key())) {
				errors.add(new FieldError(path + ".key", "Field '" + change.key() + "' is listed more than once"));
			}
			else if (field.get().locked()) {
				errors.add(new FieldError(path + ".key",
						field.get().label() + " is always collected and cannot be changed"));
			}
			else if ((change.required() || change.collectAtRegistration()) && !change.enabled()) {
				errors.add(new FieldError(path + ".enabled",
						field.get().label() + " must be enabled to be required or asked at registration"));
			}
			else {
				requested.put(field.get(), change);
			}
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid student profile field settings", errors);
		}
		return requested;
	}

	private Map<StudentProfileField, StudentProfileFieldSetting> persistedByField() {
		Map<StudentProfileField, StudentProfileFieldSetting> result = new EnumMap<>(StudentProfileField.class);
		// Rows for keys no longer in the catalogue are ignored.
		repository.findAll()
			.forEach(row -> StudentProfileField.fromKey(row.getFieldKey()).ifPresent(field -> result.put(field, row)));
		return result;
	}

	private static Map<String, Object> flags(boolean enabled, boolean required, boolean collect) {
		return Map.of("enabled", enabled, "required", required, "collectAtRegistration", collect);
	}

}
