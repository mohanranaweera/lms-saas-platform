package com.lms.tenantmanagement.config;

import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.tenantmanagement.api.ConfigDomain;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * One registered property within a {@link ConfigDomain}, per {@code
 * ConfigPropertyRegistry}. {@code validator} takes the candidate value being
 * saved for this property AND the full set of values being saved together
 * in the same request (the raw {@code changes} map {@code
 * TenantConfigController#updateDomain} received) - this is what lets a
 * property express cross-field validation (e.g. {@code secondary_color}'s
 * WCAG AA contrast check against a {@code primary_color} submitted in the
 * same request) without a separate cross-field validation mechanism.
 *
 * @param sensitive when {@code true}, this property's resolved value is
 * never echoed back in a read response - masked to {@code null}/absent.
 * Nothing sets this yet; the field exists so a later wave's integration
 * credential can rely on the masking already being wired and tested.
 * @param requiredAction the {@code PermissionCheckService} action needed to
 * write this property - always {@link PermissionAction#CREATE_EDIT} for
 * now (every property in this wave is gated the same way at the domain
 * level by {@code TenantConfigController}); kept as a per-property field so
 * a future property needing a stricter gate (e.g. {@code APPROVE}) does not
 * require a shape change here.
 */
public record ConfigPropertyDefinition(ConfigDomain domain, String key, ConfigValueType valueType,
		Object defaultValue, BiPredicate<Object, Map<String, Object>> validator, boolean sensitive,
		PermissionAction requiredAction) {

	public ConfigPropertyDefinition {
		if (domain == null) {
			throw new IllegalArgumentException("domain must not be null");
		}
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("key must not be null/blank");
		}
		if (valueType == null) {
			throw new IllegalArgumentException("valueType must not be null");
		}
		if (validator == null) {
			throw new IllegalArgumentException("validator must not be null");
		}
		if (requiredAction == null) {
			throw new IllegalArgumentException("requiredAction must not be null");
		}
	}

	/** Convenience factory for the common case: not sensitive, {@code CREATE_EDIT} to write. */
	public static ConfigPropertyDefinition of(ConfigDomain domain, String key, ConfigValueType valueType,
			Object defaultValue, BiPredicate<Object, Map<String, Object>> validator) {
		return new ConfigPropertyDefinition(domain, key, valueType, defaultValue, validator, false,
				PermissionAction.CREATE_EDIT);
	}

}
