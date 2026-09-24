package com.lms.videoaccessmanagement.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The single, narrow contract {@code content-management} is permitted to
 * depend on from {@code video-access-management} (plan §9/{@code
 * .claude/rules/architecture.md}) - never {@code VideoAssetRepository}/
 * {@code VideoAsset} directly. Both methods resolve tenant identity
 * exclusively from the trusted {@link com.lms.common.tenant.TenantContext}
 * internally - there is no overload accepting a caller-supplied tenant id;
 * {@code tenantId} is taken as an explicit parameter here only because the
 * caller (content-management's own already-resolved tenant) must be proven
 * to match the asset's row, not because this interface trusts a client
 * -supplied value.
 */
public interface VideoAccessApi {

	/**
	 * Used by {@code MaterialService#createMaterial}'s {@code VIDEO}/{@code
	 * RECORDING} branch (plan §4) to verify a supplied {@code videoAssetId}
	 * both resolves to a real, {@code READY} asset AND belongs to the same
	 * tenant as the material being created - a cross-tenant or not-yet-ready
	 * {@code videoAssetId} must never be attachable to a material.
	 */
	boolean isVideoAssetReadyAndOwnedByTenant(UUID videoAssetId, UUID tenantId);

	/**
	 * Minimal read projection for surfacing basic video status/duration
	 * without content-management importing the {@code VideoAsset} entity.
	 * @return {@link Optional#empty()} if no such asset exists for {@code tenantId}.
	 */
	Optional<VideoAssetSummary> getSummary(UUID videoAssetId, UUID tenantId);

}
