package com.lms.identityaccessservice.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * STAFF-MGMT-02 §2.3: marks a controller method (or every method of a controller) as the
 * operation behind a configurable {@link Capability}. Evaluated by
 * {@code CapabilityEnforcementInterceptor} before the {@code @PreAuthorize} proxy runs:
 * <ul>
 * <li>Operator/Manager without the tenant grant: 403. With it, the capability's implied
 * {@link PermissionPair}s are elevated for this request only, so the endpoint's existing
 * {@code hasPermission}/{@code requirePermission} checks pass.
 * <li>Teacher: a revoked teacher-configurable capability is 403; never elevates.
 * <li>Every other role: no effect (the endpoint's existing checks decide).
 * </ul>
 * This annotation never grants anything to a static role and never replaces the endpoint's
 * own {@code @PreAuthorize} / service-layer checks.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresCapability {

	Capability value();

}
