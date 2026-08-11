package com.lms.tenantmanagement.service;

import com.lms.tenantmanagement.api.TenantStatus;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Pure state-machine logic for {@code TEN-2}'s tenant status lifecycle.
 *
 * <p><strong>Deliberately not wired to any controller or exposed via any
 * endpoint in this slice.</strong> This validates {@code TEN-2}'s business
 * rules ahead of time without requiring the Platform Admin authentication
 * that does not exist yet in this codebase (see
 * {@code docs/plans/MVP-004 Tenant Management.md} SS20). This class only
 * decides "is this transition legal, and what status results" - it does not
 * call {@code TenantRepository} or perform any persistence/orchestration
 * (audit-row writes, default-config provisioning, etc. belong to the future
 * {@code TEN-2} endpoint implementation).
 *
 * <p>Legal transitions (see plan SS7):
 * <pre>
 * PENDING_APPROVAL --APPROVE--&gt;                TRIAL
 * PENDING_APPROVAL --REJECT--&gt;                 REJECTED
 * TRIAL            --ACTIVATE_TRIAL_TO_PAID--&gt; ACTIVE
 * TRIAL            --SUSPEND--&gt;                SUSPENDED
 * ACTIVE           --SUSPEND--&gt;                SUSPENDED
 * TRIAL            --CANCEL--&gt;                 CANCELLED
 * ACTIVE           --CANCEL--&gt;                 CANCELLED
 * SUSPENDED        --CANCEL--&gt;                 CANCELLED
 * </pre>
 * Every other combination - including anything out of {@code REJECTED} or
 * {@code CANCELLED} (both terminal for MVP), and {@code SUSPENDED ->
 * ACTIVE/TRIAL} (reactivation is an unresolved open decision) - throws
 * {@link IllegalTenantStatusTransitionException}.
 */
@Service
public class TenantStatusService {

	private static final Map<TenantStatus, Map<TenantTransition, TenantStatus>> TRANSITIONS = buildTransitions();

	public TenantStatus nextStatus(TenantStatus current, TenantTransition transition) {
		Map<TenantTransition, TenantStatus> allowedFromCurrent = TRANSITIONS.get(current);
		TenantStatus next = allowedFromCurrent == null ? null : allowedFromCurrent.get(transition);
		if (next == null) {
			throw new IllegalTenantStatusTransitionException(current, transition);
		}
		return next;
	}

	private static Map<TenantStatus, Map<TenantTransition, TenantStatus>> buildTransitions() {
		Map<TenantStatus, Map<TenantTransition, TenantStatus>> transitions = new EnumMap<>(TenantStatus.class);

		transitions.put(TenantStatus.PENDING_APPROVAL,
				Map.of(TenantTransition.APPROVE, TenantStatus.TRIAL, TenantTransition.REJECT, TenantStatus.REJECTED));

		transitions.put(TenantStatus.TRIAL,
				Map.of(TenantTransition.ACTIVATE_TRIAL_TO_PAID, TenantStatus.ACTIVE, TenantTransition.SUSPEND,
						TenantStatus.SUSPENDED, TenantTransition.CANCEL, TenantStatus.CANCELLED));

		transitions.put(TenantStatus.ACTIVE, Map.of(TenantTransition.SUSPEND, TenantStatus.SUSPENDED,
				TenantTransition.CANCEL, TenantStatus.CANCELLED));

		transitions.put(TenantStatus.SUSPENDED, Map.of(TenantTransition.CANCEL, TenantStatus.CANCELLED));

		// REJECTED and CANCELLED are terminal for MVP - no entries, so any
		// transition attempted from either throws.
		transitions.put(TenantStatus.REJECTED, Map.of());
		transitions.put(TenantStatus.CANCELLED, Map.of());

		return Map.copyOf(transitions);
	}

}
