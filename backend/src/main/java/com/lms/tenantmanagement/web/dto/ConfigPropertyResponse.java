package com.lms.tenantmanagement.web.dto;

import com.lms.tenantmanagement.config.ConfigValueType;

/**
 * Response shape for one resolved configuration property. {@code value} is
 * already {@code null} for a {@code sensitive} property - never the raw
 * persisted value, regardless of caller.
 */
public record ConfigPropertyResponse(String key, Object value, ConfigValueType type, Object defaultValue,
		boolean sensitive) {

}
