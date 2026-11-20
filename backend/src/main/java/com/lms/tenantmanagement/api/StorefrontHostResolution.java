package com.lms.tenantmanagement.api;

/**
 * STORE-01 / ADR-037 decision 2: a request host of the teacher-storefront
 * shape {@code <storefront-label>.<tenant-subdomain>.<platform-domain>}.
 *
 * @param storefrontLabel the first label (the candidate storefront slug)
 * @param tenant the tenant the second label names, or {@code null} when no
 * tenant has that subdomain (no status filtering, like {@link
 * TenantLookupApi#resolveByHost})
 */
public record StorefrontHostResolution(String storefrontLabel, TenantResolution tenant) {

}
