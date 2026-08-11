package com.lms.usermanagement.staff.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.usermanagement.staff.domain.StaffProfile;
import java.util.UUID;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md}: every inherited finder
 * (findById, findAll, ...) is AND-combined with the resolved tenant context
 * by {@code TenantAwareRepositoryImpl} - no method here accepts a
 * caller-supplied {@code tenant_id}.
 */
public interface StaffProfileRepository extends TenantAwareRepository<StaffProfile, UUID> {

}
