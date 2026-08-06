package com.lms.tenantmanagement.domain;

import com.lms.tenantmanagement.api.TenantStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link TenantStatus} to/from the lowercase string values used by
 * {@code tenant.status}'s CHECK constraint (V2). {@code autoApply = true}
 * since {@link TenantStatus} is only ever used for this one column.
 */
@Converter(autoApply = true)
public class TenantStatusConverter implements AttributeConverter<TenantStatus, String> {

	@Override
	public String convertToDatabaseColumn(TenantStatus attribute) {
		return attribute == null ? null : attribute.name().toLowerCase();
	}

	@Override
	public TenantStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : TenantStatus.valueOf(dbData.toUpperCase());
	}

}
