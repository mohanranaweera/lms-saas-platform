package com.lms.tenantmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.tenantmanagement.api.TenantStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Table-driven, exhaustive coverage of {@link TenantStatusService}'s
 * documented legal-transition graph (plan {@code MVP-004 Tenant Management.md}
 * SS7 / the class's own Javadoc).
 *
 * <p>The "legal" expectations in {@link #legalTransitions()} are hardcoded
 * independently from {@code TenantStatusService}'s internal transition map -
 * not derived/copied from it - so this test can actually catch a regression
 * in that map instead of trivially agreeing with whatever it currently says.
 *
 * <p>6 {@link TenantStatus} values x 5 {@link TenantTransition} values = 30
 * total combinations. 8 are legal; the remaining 22 - including every
 * transition attempted out of {@code REJECTED}/{@code CANCELLED} (both
 * terminal for MVP) and {@code SUSPENDED -> ACTIVE}/{@code SUSPENDED ->
 * TRIAL} (no reactivation path defined) - must throw
 * {@link IllegalTenantStatusTransitionException}.
 */
class TenantStatusServiceTest {

	private final TenantStatusService service = new TenantStatusService();

	private static final Map<StatusTransitionPair, TenantStatus> LEGAL_TRANSITIONS = legalTransitions();

	private record StatusTransitionPair(TenantStatus status, TenantTransition transition) {
	}

	private static Map<StatusTransitionPair, TenantStatus> legalTransitions() {
		Map<StatusTransitionPair, TenantStatus> legal = new HashMap<>();
		legal.put(new StatusTransitionPair(TenantStatus.PENDING_APPROVAL, TenantTransition.APPROVE),
				TenantStatus.TRIAL);
		legal.put(new StatusTransitionPair(TenantStatus.PENDING_APPROVAL, TenantTransition.REJECT),
				TenantStatus.REJECTED);
		legal.put(new StatusTransitionPair(TenantStatus.TRIAL, TenantTransition.ACTIVATE_TRIAL_TO_PAID),
				TenantStatus.ACTIVE);
		legal.put(new StatusTransitionPair(TenantStatus.TRIAL, TenantTransition.SUSPEND), TenantStatus.SUSPENDED);
		legal.put(new StatusTransitionPair(TenantStatus.TRIAL, TenantTransition.CANCEL), TenantStatus.CANCELLED);
		legal.put(new StatusTransitionPair(TenantStatus.ACTIVE, TenantTransition.SUSPEND), TenantStatus.SUSPENDED);
		legal.put(new StatusTransitionPair(TenantStatus.ACTIVE, TenantTransition.CANCEL), TenantStatus.CANCELLED);
		legal.put(new StatusTransitionPair(TenantStatus.SUSPENDED, TenantTransition.CANCEL), TenantStatus.CANCELLED);
		return Map.copyOf(legal);
	}

	/** The full 6x5 cross product of every status and every transition. */
	static Stream<Arguments> allStatusTransitionCombinations() {
		return Stream.of(TenantStatus.values())
			.flatMap(status -> Stream.of(TenantTransition.values())
				.map(transition -> Arguments.of(status, transition,
						LEGAL_TRANSITIONS.get(new StatusTransitionPair(status, transition)))));
	}

	@ParameterizedTest(name = "{0} --{1}--> {2}")
	@MethodSource("allStatusTransitionCombinations")
	void everyCombinationMatchesTheDocumentedTransitionGraph(TenantStatus current, TenantTransition transition,
			TenantStatus expectedNextOrNullIfIllegal) {
		if (expectedNextOrNullIfIllegal != null) {
			assertThat(service.nextStatus(current, transition)).isEqualTo(expectedNextOrNullIfIllegal);
		}
		else {
			assertThatThrownBy(() -> service.nextStatus(current, transition))
				.isInstanceOf(IllegalTenantStatusTransitionException.class);
		}
	}

	@Test
	void exactlyEightTransitionsAreLegalPerThePlanSGraph() {
		assertThat(LEGAL_TRANSITIONS).hasSize(8);
	}

	@Test
	void everyTransitionOutOfRejectedIsIllegalBecauseItIsTerminalForMvp() {
		for (TenantTransition transition : TenantTransition.values()) {
			assertThatThrownBy(() -> service.nextStatus(TenantStatus.REJECTED, transition))
				.isInstanceOf(IllegalTenantStatusTransitionException.class);
		}
	}

	@Test
	void everyTransitionOutOfCancelledIsIllegalBecauseItIsTerminalForMvp() {
		for (TenantTransition transition : TenantTransition.values()) {
			assertThatThrownBy(() -> service.nextStatus(TenantStatus.CANCELLED, transition))
				.isInstanceOf(IllegalTenantStatusTransitionException.class);
		}
	}

	@Test
	void suspendedCannotReactivateDirectlyToActive() {
		assertThatThrownBy(() -> service.nextStatus(TenantStatus.SUSPENDED, TenantTransition.ACTIVATE_TRIAL_TO_PAID))
			.isInstanceOf(IllegalTenantStatusTransitionException.class);
	}

	@Test
	void suspendedCannotReactivateDirectlyToTrialBecauseNoApproveEquivalentTransitionAppliesToIt() {
		// There is no TenantTransition that maps SUSPENDED -> TRIAL at all
		// (APPROVE is only legal from PENDING_APPROVAL) - asserting APPROVE
		// specifically from SUSPENDED documents this named case from the
		// task/plan explicitly, on top of the exhaustive cross-product test
		// above.
		assertThatThrownBy(() -> service.nextStatus(TenantStatus.SUSPENDED, TenantTransition.APPROVE))
			.isInstanceOf(IllegalTenantStatusTransitionException.class);
	}

}
