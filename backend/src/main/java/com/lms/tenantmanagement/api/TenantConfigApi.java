package com.lms.tenantmanagement.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only tenant-configuration contract other domains may depend on, per
 * {@code .claude/rules/architecture.md}'s "a module may depend only on
 * another module's {@code api} package" rule. No other module calls this
 * yet (Wave 1 ships the framework only) - it exists now so a later wave's
 * business-domain module (e.g. {@code course-management} reading a
 * {@code COURSE} setting) and the public/unauthenticated branding read
 * (which resolves tenant from the request's host, not an authenticated
 * actor - see {@code PublicBrandingController}) have a stable interface to
 * call.
 *
 * <p>{@code tenantId} is explicit (never resolved internally from {@code
 * TenantContext}) so a caller that has already independently resolved its
 * own trusted tenant identity (the request's {@code TenantContext} value,
 * or - for a future background/async caller - a tenant id carried
 * explicitly in its own job/event payload per {@code
 * .claude/rules/tenancy.md}) can pass it through directly. As of this wave,
 * the implementation only supports a {@code tenantId} equal to the current
 * request's resolved {@code TenantContext} (there is no production caller
 * outside a tenant-resolved request yet) - see {@code
 * TenantConfigService}'s javadoc for the exact guard.
 *
 * <p>A {@code sensitive} property's value is never returned by either
 * method - masked to absent/{@code null} - even for a same-tenant, in-context
 * caller. No property is marked {@code sensitive} yet, but the masking is
 * proven now so a later wave (e.g. an {@code INTEGRATION} credential) can
 * rely on it without a behavior change here.
 */
public interface TenantConfigApi {

	Optional<Object> resolveValue(UUID tenantId, ConfigDomain domain, String key);

	Map<String, Object> resolveDomain(UUID tenantId, ConfigDomain domain);

}
