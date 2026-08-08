package com.lms.tenantmanagement.domain;

import com.lms.tenantmanagement.api.TenantStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link TenantStatus} to/from the lowercase-with-underscore string
 * values the {@code ck_tenant_status} CHECK constraint enforces
 * (see {@code V2__create_tenant_table.sql}). {@code autoApply = false}:
 * applied explicitly via {@code @Convert} on {@link Tenant#status}, not
 * platform-wide, since {@link TenantStatus} is the only type this converter
 * targets.
 */
@Converter(autoApply = false)
public class TenantStatusConverter implements AttributeConverter<TenantStatus, String> {

	@Override
	public String convertToDatabaseColumn(TenantStatus attribute) {
		return attribute == null ? null : attribute.toColumnValue();
	}

	@Override
	public TenantStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : TenantStatus.fromColumnValue(dbData);
	}

}
