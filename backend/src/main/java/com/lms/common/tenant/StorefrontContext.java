package com.lms.common.tenant;

import java.util.UUID;

/**
 * STORE-01 / ADR-037: the teacher storefront a request's host names, resolved
 * by {@code TenantResolutionFilter} AFTER the tenant (and within it). A
 * storefront is a view of the tenant, never a tenant and never an auth role:
 * it only narrows what public catalog and checkout endpoints offer.
 *
 * @param storefrontId {@code teacher_storefront.id}
 * @param teacherId the storefront teacher's {@code tenant_user.id}
 * @param slug the storefront's current slug (the host's first label)
 */
public record StorefrontContext(UUID storefrontId, UUID teacherId, String slug) {

}
