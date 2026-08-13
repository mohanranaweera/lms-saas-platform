package com.lms.usermanagement.student.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.usermanagement.student.domain.StudentProfile;
import java.util.UUID;

/**
 * Tenant-scoped per {@code .claude/rules/tenancy.md}: every inherited finder
 * (findById, findAll, ...) is AND-combined with the resolved tenant context
 * by {@code TenantAwareRepositoryImpl} - no method here accepts a
 * caller-supplied {@code tenant_id}. Deliberately has no derived-query
 * method (e.g. {@code findByUserId}) - a Spring-Data-generated method
 * declared directly on this interface would NOT go through {@code
 * TenantAwareRepositoryImpl}'s scoping logic. The one "find by user id"
 * lookup this module needs (the self-service profile lookup) is implemented
 * in {@code StudentService} via the inherited, tenant-scoped {@code
 * findOne(Specification)} instead.
 */
public interface StudentProfileRepository extends TenantAwareRepository<StudentProfile, UUID> {

}
