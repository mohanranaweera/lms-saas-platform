package com.lms.tenantmanagement.service;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects a subdomain that matches the platform-reserved-word denylist (see
 * {@link NotReservedSubdomainValidator}). This check is beyond what a
 * declarative constraint on the public {@code TenantRegistrationRequest}
 * web DTO can express on its own (it needs the platform's denylist, a
 * business rule, not a structural format rule) - it is applied to the
 * internal {@link TenantRegistrationCommand} instead and validated
 * explicitly by {@link TenantRegistrationService}, not via automatic
 * request-body {@code @Valid} binding.
 */
@Constraint(validatedBy = NotReservedSubdomainValidator.class)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
public @interface NotReservedSubdomain {

	String message() default "This subdomain is reserved and cannot be used";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
