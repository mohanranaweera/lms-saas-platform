package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.videoaccessmanagement.domain.TenantVideoUploadTeacher;
import java.util.UUID;

/** Tenant-scoped per ADR-006: every read and write is limited to the current tenant's allow-list. */
public interface TenantVideoUploadTeacherRepository extends TenantAwareRepository<TenantVideoUploadTeacher, UUID> {

}
