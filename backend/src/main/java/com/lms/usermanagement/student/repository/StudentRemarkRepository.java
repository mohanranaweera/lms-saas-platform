package com.lms.usermanagement.student.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.usermanagement.student.domain.StudentRemark;
import java.util.UUID;

/** Tenant-scoped by {@code TenantAwareRepositoryImpl}; remarks are never updated or deleted. */
public interface StudentRemarkRepository extends AppendOnlyTenantAwareRepository<StudentRemark, UUID> {

}
