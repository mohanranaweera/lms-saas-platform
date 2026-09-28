package com.lms.usermanagement.student.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.tenantmanagement.api.StudentProfileField;
import com.lms.tenantmanagement.api.StudentProfileFieldConfigApi;
import com.lms.tenantmanagement.api.StudentProfileFieldPolicy;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.repository.StudentProfileRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-configurable student profile field values (STU-PROF-01): the
 * student's own "Additional details", the staff view/correction of a
 * student's values, and validation of the values asked at registration.
 * Which fields exist, are required, or are asked at registration comes from
 * {@link StudentProfileFieldConfigApi} for the request's resolved tenant -
 * never from the client.
 *
 * <p>Rules:
 * <ul>
 * <li>Only enabled, non-locked fields are shown or accepted; a disabled or
 * unknown key is rejected (400). Values of disabled fields stay stored,
 * hidden (owner decision 4).</li>
 * <li>A student's own save must leave every required field filled; staff
 * saves are not held to required-ness (admin-created/imported students may
 * be incomplete).</li>
 * <li>Missing required fields never block anything else (owner decision 3):
 * they are reported as {@code missingRequiredFields}.</li>
 * </ul>
 *
 * <p>The self-service methods take no id: the profile is resolved from the
 * authenticated user id, like {@code StudentService#getOwnProfile}. Staff
 * methods re-check {@code STUDENTS} permission and look the profile up
 * through the tenant-scoped repository (another tenant's id -> 404). Staff
 * edits are audit-logged with the changed field keys only - never values.
 * Sensitive (identity-document) values are masked for view-only staff.
 */
@Service
@Transactional
public class StudentProfileFieldsService {

	private static final Logger log = LoggerFactory.getLogger(StudentProfileFieldsService.class);

	private final StudentProfileRepository studentProfileRepository;

	private final StudentProfileFieldConfigApi fieldConfigApi;

	private final TenantContext tenantContext;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	public StudentProfileFieldsService(StudentProfileRepository studentProfileRepository,
			StudentProfileFieldConfigApi fieldConfigApi, TenantContext tenantContext,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi) {
		this.studentProfileRepository = studentProfileRepository;
		this.fieldConfigApi = fieldConfigApi;
		this.tenantContext = tenantContext;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
	}

	@Transactional(readOnly = true)
	public StudentProfileFieldsView getOwnFields() {
		return view(ownProfile(), policies());
	}

	public StudentProfileFieldsView updateOwnFields(Map<String, Object> values) {
		StudentProfile profile = ownProfile();
		List<StudentProfileFieldPolicy> policies = policies();
		List<StudentProfileField> changed = apply(profile, values, policies, true);
		studentProfileRepository.save(profile);
		log.info("Student profile {} details updated ({} fields) in tenant {} by self-service user {}",
				profile.getId(), changed.size(), tenantContext.getTenantId(), profile.getUserId());
		return view(profile, policies);
	}

	/**
	 * Staff read. Sensitive values (identity-document numbers) are returned
	 * in full only to staff who may edit students ({@code STUDENTS}
	 * CREATE_EDIT - Tenant Admin, Student Support); every view-only staff
	 * role gets them masked to the last four characters.
	 */
	@Transactional(readOnly = true)
	public StudentProfileFieldsView getStudentFields(UUID studentProfileId) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);
		boolean maskSensitive = !permissionCheckService.hasPermission(DomainArea.STUDENTS,
				PermissionAction.CREATE_EDIT);
		return view(staffProfile(studentProfileId), policies(), maskSensitive);
	}

	public StudentProfileFieldsView updateStudentFields(UUID studentProfileId, Map<String, Object> values) {
		permissionCheckService.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
		StudentProfile profile = staffProfile(studentProfileId);
		List<StudentProfileFieldPolicy> policies = policies();
		List<StudentProfileField> changed = apply(profile, values, policies, false);
		studentProfileRepository.save(profile);
		if (!changed.isEmpty()) {
			auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(),
					"student.profile_fields_updated", "student_profile", profile.getId(), null,
					Map.of("fields", changed.stream().map(Enum::name).toList())));
		}
		return view(profile, policies);
	}

	/**
	 * Validates the values submitted at registration against the fields the
	 * tenant asks at registration: each is parsed, and a required one must be
	 * filled. Values for any other key are ignored. Throws 400 listing every
	 * problem; otherwise returns the non-null parsed values to store with
	 * {@link #applyRegistrationValues}. Called before any account is created.
	 */
	@Transactional(readOnly = true)
	public Map<StudentProfileField, Object> validateRegistrationValues(Map<String, Object> rawValues) {
		Map<String, Object> raw = (rawValues == null) ? Map.of() : rawValues;
		List<FieldError> errors = new ArrayList<>();
		Map<StudentProfileField, Object> parsed = new EnumMap<>(StudentProfileField.class);
		for (StudentProfileFieldPolicy policy : policies()) {
			StudentProfileField field = policy.field();
			if (field.locked() || !policy.collectAtRegistration()) {
				continue;
			}
			String path = "profileFields." + field.name();
			int before = errors.size();
			Object value = StudentProfileFieldValueParser.parse(field, raw.get(field.name()), path, errors);
			if (errors.size() > before) {
				continue;
			}
			if (policy.required() && !StudentProfileFieldValueParser.isFilled(value)) {
				errors.add(new FieldError(path, field.label() + " is required"));
			}
			else if (value != null) {
				parsed.put(field, value);
			}
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Registration is missing required fields for this institute", errors);
		}
		return parsed;
	}

	/** Stores values returned by {@link #validateRegistrationValues} on a new profile. */
	public static void applyRegistrationValues(StudentProfile profile, Map<StudentProfileField, Object> values) {
		values.forEach(profile::writeField);
	}

	private List<StudentProfileField> apply(StudentProfile profile, Map<String, Object> values,
			List<StudentProfileFieldPolicy> policies, boolean enforceRequired) {
		Map<StudentProfileField, StudentProfileFieldPolicy> byField = byField(policies);
		Map<StudentProfileField, Object> parsed = new EnumMap<>(StudentProfileField.class);
		List<FieldError> errors = new ArrayList<>();

		Map<String, Object> safeValues = (values == null) ? Map.of() : values;
		for (Map.Entry<String, Object> entry : safeValues.entrySet()) {
			String path = "values." + entry.getKey();
			Optional<StudentProfileField> field = StudentProfileField.fromKey(entry.getKey());
			if (field.isEmpty()) {
				errors.add(new FieldError(path, "Unknown profile field"));
				continue;
			}
			if (field.get().locked()) {
				errors.add(new FieldError(path, field.get().label() + " cannot be changed here"));
				continue;
			}
			if (!byField.get(field.get()).enabled()) {
				errors.add(new FieldError(path, field.get().label() + " is not collected by this institute"));
				continue;
			}
			int before = errors.size();
			Object value = StudentProfileFieldValueParser.parse(field.get(), entry.getValue(), path, errors);
			if (errors.size() == before) {
				parsed.put(field.get(), value);
			}
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Some profile details are invalid", errors);
		}

		if (enforceRequired) {
			for (StudentProfileFieldPolicy policy : policies) {
				StudentProfileField field = policy.field();
				if (field.locked() || !policy.enabled() || !policy.required()) {
					continue;
				}
				Object value = parsed.containsKey(field) ? parsed.get(field) : profile.readField(field);
				if (!StudentProfileFieldValueParser.isFilled(value)) {
					errors.add(new FieldError("values." + field.name(), field.label() + " is required"));
				}
			}
			if (!errors.isEmpty()) {
				throw new FieldValidationException("Some required profile details are missing", errors);
			}
		}

		List<StudentProfileField> changed = new ArrayList<>();
		parsed.forEach((field, value) -> {
			if (!Objects.equals(profile.readField(field), value)) {
				profile.writeField(field, value);
				changed.add(field);
			}
		});
		return changed;
	}

	private static StudentProfileFieldsView view(StudentProfile profile, List<StudentProfileFieldPolicy> policies) {
		return view(profile, policies, false);
	}

	private static StudentProfileFieldsView view(StudentProfile profile, List<StudentProfileFieldPolicy> policies,
			boolean maskSensitive) {
		List<StudentProfileFieldValue> fields = new ArrayList<>();
		List<StudentProfileField> missing = new ArrayList<>();
		for (StudentProfileFieldPolicy policy : policies) {
			StudentProfileField field = policy.field();
			if (field.locked() || !policy.enabled()) {
				continue;
			}
			Object value = profile.readField(field);
			Object json;
			if (value == null) {
				json = null;
			}
			else if (maskSensitive && field.sensitive()) {
				json = mask(String.valueOf(value));
			}
			else {
				json = StudentProfileFieldValueParser.toJson(value);
			}
			fields.add(new StudentProfileFieldValue(field, policy.required(), json));
			if (policy.required() && !StudentProfileFieldValueParser.isFilled(value)) {
				missing.add(field);
			}
		}
		return new StudentProfileFieldsView(fields, missing);
	}

	/** Last four characters only ("•••• 5678"); values of four characters or fewer are fully hidden. */
	static String mask(String value) {
		return (value.length() <= 4) ? "••••" : "•••• " + value.substring(value.length() - 4);
	}

	private List<StudentProfileFieldPolicy> policies() {
		return fieldConfigApi.resolveFieldPolicies(tenantContext.getTenantId());
	}

	private static Map<StudentProfileField, StudentProfileFieldPolicy> byField(
			List<StudentProfileFieldPolicy> policies) {
		Map<StudentProfileField, StudentProfileFieldPolicy> byField = new EnumMap<>(StudentProfileField.class);
		policies.forEach(policy -> byField.put(policy.field(), policy));
		return byField;
	}

	private StudentProfile ownProfile() {
		UUID userId = AuthenticatedPrincipalHolder.get().userId();
		return studentProfileRepository.findOne((root, query, cb) -> cb.equal(root.get("userId"), userId))
			.orElseThrow(() -> new NotFoundException("Student account not found"));
	}

	private StudentProfile staffProfile(UUID studentProfileId) {
		// Tenant-scoped findById: another tenant's id is structurally invisible (404).
		return studentProfileRepository.findById(studentProfileId)
			.orElseThrow(() -> new NotFoundException("Student account not found"));
	}

}
