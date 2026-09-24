package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import java.util.UUID;

/** Tenant-scoped per ADR-006, mirroring {@code MaterialRepository}'s style exactly. */
public interface VideoAssetRepository extends TenantAwareRepository<VideoAsset, UUID> {

}
