package com.lms.tenantmanagement.service;

import com.lms.common.error.ConflictException;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.repository.TenantRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles {@code TEN-1} tenant self-registration. Every tenant created here
 * starts at {@code PENDING_APPROVAL} - there is no parameter or code path
 * that lets a caller (however the request body is shaped) choose a
 * different initial status; see {@link Tenant#registerPending}.
 */
@Service
@Transactional
public class TenantRegistrationService {

	private final TenantRepository tenantRepository;

	private final Validator validator;

	public TenantRegistrationService(TenantRepository tenantRepository, Validator validator) {
		this.tenantRepository = tenantRepository;
		this.validator = validator;
	}

	public TenantRegistrationResult register(TenantRegistrationCommand command) {
		Set<ConstraintViolation<TenantRegistrationCommand>> violations = validator.validate(command);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}

		// Friendlier pre-check for the common case; NOT the race-safe guard.
		// The DB's uq_tenant_subdomain constraint (enforced below via
		// saveAndFlush) is what actually prevents a concurrent double
		// registration from both succeeding.
		if (tenantRepository.existsBySubdomain(command.subdomain())) {
			throw new ConflictException("A tenant with this subdomain already exists");
		}

		Tenant tenant = Tenant.registerPending(command.name(), command.subdomain(), command.requestedPlan(),
				command.contactName(), command.contactEmail(), command.contactPhone());

		try {
			tenant = tenantRepository.saveAndFlush(tenant);
		}
		catch (DataIntegrityViolationException ex) {
			// subdomain is the only unique constraint on this table, so this
			// message is accurate without needing to inspect the cause.
			throw new ConflictException("A tenant with this subdomain already exists");
		}

		return new TenantRegistrationResult(tenant.getId(), tenant.getSubdomain(), tenant.getStatus());
	}

}
