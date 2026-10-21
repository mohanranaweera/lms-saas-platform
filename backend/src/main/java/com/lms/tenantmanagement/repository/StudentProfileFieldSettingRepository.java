package com.lms.tenantmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.tenantmanagement.domain.StudentProfileFieldSetting;
import java.util.UUID;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md}: the inherited {@code
 * findAll()} is AND-combined with the resolved tenant context by {@code
 * TenantAwareRepositoryImpl}, and {@code save} rejects a row whose {@code
 * tenantId} differs from it. No method accepts a caller-supplied tenant id.
 */
public interface StudentProfileFieldSettingRepository extends TenantAwareRepository<StudentProfileFieldSetting, UUID> {

}
