package com.lms.tenantmanagement.service;

import com.lms.tenantmanagement.config.ConfigValueType;

/**
 * Service-layer view of one resolved configuration property (persisted
 * override if present, else the registry default) - never a JPA entity,
 * per {@code backend/CLAUDE.md}. {@code TenantConfigController} maps this
 * to {@code ConfigPropertyResponse}. {@code value} is already masked to
 * {@code null} when {@code sensitive} is {@code true} - see {@link
 * TenantConfigService#getDomain}.
 */
public record ConfigPropertyValue(String key, Object value, ConfigValueType type, Object defaultValue,
		boolean sensitive) {

}
