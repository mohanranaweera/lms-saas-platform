package com.lms.tenantmanagement.service;

/**
 * Internal command for {@link TenantRegistrationService#register(TenantRegistrationCommand)}.
 * Kept distinct from the web-layer {@code TenantRegistrationRequest} DTO so
 * this service's public signature never depends on the {@code web} package.
 *
 * <p>{@code subdomain} carries {@link NotReservedSubdomain}, validated
 * explicitly inside {@link TenantRegistrationService#register(TenantRegistrationCommand)}
 * (not via automatic {@code @Valid} request-body binding) - this is the
 * reserved-word business rule that the public request DTO's declarative
 * annotations cannot express on their own.
 */
public record TenantRegistrationCommand(String name, @NotReservedSubdomain String subdomain, String requestedPlan,
		String contactName, String contactEmail, String contactPhone) {

}
