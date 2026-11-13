package com.lms.localizationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.localizationmanagement.domain.UserLocalePreference;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006 ({@code findById(userId)} carries the structural tenant predicate);
 * no tenant-id parameter. Never used outside {@code com.lms.localizationmanagement}.
 */
public interface UserLocalePreferenceRepository extends TenantAwareRepository<UserLocalePreference, UUID> {

}
