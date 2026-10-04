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
import com.lms.videoaccessmanagement.domain.MediaPrivacyStatus;
import com.lms.videoaccessmanagement.domain.TenantVideoConfiguration;
import com.lms.videoaccessmanagement.domain.TenantVideoSettings;
import com.lms.videoaccessmanagement.domain.WatchTimePolicy;
import com.lms.videoaccessmanagement.domain.WatermarkField;
import com.lms.videoaccessmanagement.repository.TenantVideoConfigurationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-wide video settings (VIDEO Phase 3, plan §11). Tenant Admin only,
 * through the {@code INTEGRATIONS} permission (same area as the video
 * provider accounts). The row is always the authenticated tenant's own - the
 * repository's tenant filter, never a request parameter, picks it.
 *
 * <p>Every change is audited with the full before/after settings in the same
 * transaction (security.md: settings that change access rules are audited).
 */
@Service
public class TenantVideoConfigurationService {

	static final String AUDIT_ACTION = "video_configuration.updated";

	static final String TARGET_ENTITY = "tenant_video_configuration";

	private static final BigDecimal MIN_OPACITY = new BigDecimal("0.05");

	private static final BigDecimal MAX_OPACITY = new BigDecimal("0.60");

	private static final BigDecimal MIN_MULTIPLIER = new BigDecimal("1.00");

	private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("10.00");

	/** What the settings page shows: the effective settings and whether the tenant customized them. */
	public record TenantVideoConfigurationView(TenantVideoSettings settings, boolean customized, Instant updatedAt,
			UUID updatedBy) {

	}

	private final TenantVideoConfigurationRepository repository;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public TenantVideoConfigurationService(TenantVideoConfigurationRepository repository,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.repository = repository;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public TenantVideoConfigurationView get() {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.VIEW);
		return repository.findForCurrentTenant()
			.map(TenantVideoConfigurationService::toView)
			.orElseGet(() -> new TenantVideoConfigurationView(TenantVideoSettings.PLATFORM_DEFAULTS, false, null, null));
	}

	/**
	 * The current tenant's effective settings, for internal callers (upload,
	 * playback). No permission check - callers apply their own authorization.
	 */
	@Transactional(readOnly = true)
	public TenantVideoSettings effectiveSettings() {
		return repository.findForCurrentTenant()
			.map(TenantVideoConfiguration::toSettings)
			.orElse(TenantVideoSettings.PLATFORM_DEFAULTS);
	}

	@Transactional
	public TenantVideoConfigurationView update(TenantVideoSettings requested) {
		permissionCheckService.requirePermission(DomainArea.INTEGRATIONS, PermissionAction.CREATE_EDIT);
		TenantVideoSettings settings = validateAndNormalize(requested);
		TenantVideoConfiguration row = repository.findForCurrentTenant().orElse(null);
		TenantVideoSettings before = (row == null) ? null : row.toSettings();
		if (row == null) {
			row = new TenantVideoConfiguration(tenantContext.getTenantId(), settings);
		}
		else {
			row.apply(settings);
		}
		row = repository.saveAndFlush(row);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("before", (before == null) ? "PLATFORM_DEFAULTS" : asMap(before));
		metadata.put("after", asMap(settings));
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(), AUDIT_ACTION, TARGET_ENTITY,
				row.getId(), null, metadata));
		return toView(row);
	}

	/**
	 * Defense-in-depth 400s on top of the V71 CHECKs. Watch-time values that do
	 * not belong to the chosen policy are dropped rather than rejected.
	 */
	static TenantVideoSettings validateAndNormalize(TenantVideoSettings s) {
		List<FieldError> errors = new ArrayList<>();
		if (s.defaultProvider() == null) {
			errors.add(new FieldError("defaultProvider", "is required"));
		}
		if (s.defaultPrivacyStatus() == null || s.defaultPrivacyStatus() == MediaPrivacyStatus.PUBLIC) {
			errors.add(new FieldError("defaultPrivacyStatus", "must be UNLISTED or PRIVATE"));
		}
		if (s.watermarkOpacity() == null || s.watermarkOpacity().compareTo(MIN_OPACITY) < 0
				|| s.watermarkOpacity().compareTo(MAX_OPACITY) > 0) {
			errors.add(new FieldError("watermarkOpacity", "must be between 0.05 and 0.60"));
		}
		if (s.watermarkMovementIntervalSeconds() < 5 || s.watermarkMovementIntervalSeconds() > 120) {
			errors.add(new FieldError("watermarkMovementIntervalSeconds", "must be between 5 and 120"));
		}
		if (s.watermarkFields().isEmpty()) {
			errors.add(new FieldError("watermarkFields", "must contain at least one field"));
		}
		if (s.captureProtectionRequired() && !s.captureProtectionEnabled()) {
			errors.add(new FieldError("captureProtectionRequired", "requires captureProtectionEnabled"));
		}
		if (s.maxConcurrentSessions() < 1 || s.maxConcurrentSessions() > 5) {
			errors.add(new FieldError("maxConcurrentSessions", "must be between 1 and 5"));
		}
		if (s.launchCodeTtlSeconds() < 15 || s.launchCodeTtlSeconds() > 60) {
			errors.add(new FieldError("launchCodeTtlSeconds", "must be between 15 and 60"));
		}
		if (s.heartbeatIntervalSeconds() < 10 || s.heartbeatIntervalSeconds() > 60) {
			errors.add(new FieldError("heartbeatIntervalSeconds", "must be between 10 and 60"));
		}
		BigDecimal multiplier = null;
		Integer fixedMinutes = null;
		if (s.watchTimePolicy() == null) {
			errors.add(new FieldError("watchTimePolicy", "is required"));
		}
		else if (s.watchTimePolicy() == WatchTimePolicy.VIDEO_DURATION_MULTIPLIER) {
			multiplier = s.watchTimeMultiplier();
			if (multiplier == null || multiplier.compareTo(MIN_MULTIPLIER) < 0
					|| multiplier.compareTo(MAX_MULTIPLIER) > 0) {
				errors.add(new FieldError("watchTimeMultiplier", "must be between 1.00 and 10.00"));
			}
		}
		else if (s.watchTimePolicy() == WatchTimePolicy.FIXED_MINUTES) {
			fixedMinutes = s.watchTimeFixedMinutes();
			if (fixedMinutes == null || fixedMinutes < 1 || fixedMinutes > 10000) {
				errors.add(new FieldError("watchTimeFixedMinutes", "must be between 1 and 10000"));
			}
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid video configuration", errors);
		}
		return new TenantVideoSettings(s.defaultProvider(), s.defaultPrivacyStatus(), s.uploadsEnabled(),
				s.nativePlayerRequired(), s.watermarkEnabled(), s.watermarkOpacity(),
				s.watermarkMovementIntervalSeconds(), s.watermarkFields(), s.captureProtectionEnabled(),
				s.captureProtectionRequired(), s.maxConcurrentSessions(), s.watchTimePolicy(), multiplier,
				fixedMinutes, s.launchCodeTtlSeconds(), s.heartbeatIntervalSeconds());
	}

	private static TenantVideoConfigurationView toView(TenantVideoConfiguration row) {
		return new TenantVideoConfigurationView(row.toSettings(), true, row.getUpdatedAt(), row.getUpdatedBy());
	}

	private static Map<String, Object> asMap(TenantVideoSettings s) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("defaultProvider", s.defaultProvider().name());
		map.put("defaultPrivacyStatus", s.defaultPrivacyStatus().name());
		map.put("uploadsEnabled", s.uploadsEnabled());
		map.put("nativePlayerRequired", s.nativePlayerRequired());
		map.put("watermarkEnabled", s.watermarkEnabled());
		map.put("watermarkOpacity", s.watermarkOpacity().toPlainString());
		map.put("watermarkMovementIntervalSeconds", s.watermarkMovementIntervalSeconds());
		map.put("watermarkFields", s.watermarkFields().stream().map(WatermarkField::name).toList());
		map.put("captureProtectionEnabled", s.captureProtectionEnabled());
		map.put("captureProtectionRequired", s.captureProtectionRequired());
		map.put("maxConcurrentSessions", s.maxConcurrentSessions());
		map.put("watchTimePolicy", s.watchTimePolicy().name());
		map.put("watchTimeMultiplier",
				(s.watchTimeMultiplier() == null) ? null : s.watchTimeMultiplier().toPlainString());
		map.put("watchTimeFixedMinutes", s.watchTimeFixedMinutes());
		map.put("launchCodeTtlSeconds", s.launchCodeTtlSeconds());
		map.put("heartbeatIntervalSeconds", s.heartbeatIntervalSeconds());
		return map;
	}

}
