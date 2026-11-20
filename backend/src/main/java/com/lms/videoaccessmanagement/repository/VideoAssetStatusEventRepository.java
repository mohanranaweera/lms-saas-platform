package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.videoaccessmanagement.domain.VideoAssetStatusEvent;
import java.util.UUID;

/**
 * Tenant-scoped (ADR-006) and append-only: every delete method fails, and
 * the V70 trigger rejects UPDATE/DELETE at the database too. Reads go
 * through {@code Specification}s so the structural tenant filter applies.
 */
public interface VideoAssetStatusEventRepository extends AppendOnlyTenantAwareRepository<VideoAssetStatusEvent, UUID> {

}
