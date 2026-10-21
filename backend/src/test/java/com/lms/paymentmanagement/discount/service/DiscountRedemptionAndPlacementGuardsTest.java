package com.lms.paymentmanagement.discount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.paymentmanagement.discount.config.DiscountProperties;
import com.lms.paymentmanagement.discount.domain.DiscountRedemption;
import com.lms.paymentmanagement.discount.domain.DiscountRule;
import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.domain.DiscountValueType;
import com.lms.paymentmanagement.discount.domain.RedemptionStatus;
import com.lms.paymentmanagement.discount.domain.SelectionMode;
import com.lms.paymentmanagement.discount.repository.DiscountRedemptionRepository;
import com.lms.paymentmanagement.discount.repository.DiscountRuleRepository;
import com.lms.paymentmanagement.discount.repository.DiscountRuleVersionCourseRepository;
import com.lms.paymentmanagement.discount.repository.DiscountRuleVersionRepository;
import com.lms.paymentmanagement.discount.repository.StudentOrderDiscountRepository;
import com.lms.paymentmanagement.discount.service.DiscountPricing.AppliedDiscount;
import com.lms.paymentmanagement.discount.service.DiscountPricing.Qualification;
import com.lms.paymentmanagement.discount.service.DiscountRedemptionService.DiscountNoLongerAvailableException;
import com.lms.paymentmanagement.discount.service.DiscountRedemptionService.EvidenceKind;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit coverage (no database) of the DISC-01 hardening guards: the bounded
 * gateway hold vs the open-ended slip hold, unconditional consumption, and the
 * placement-time one-use-per-student and allocation invariants.
 */
class DiscountRedemptionAndPlacementGuardsTest {

	private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

	private final DiscountRedemptionRepository redemptionRepository = mock(DiscountRedemptionRepository.class);

	private final DiscountRuleRepository ruleRepository = mock(DiscountRuleRepository.class);

	private final DiscountRuleVersionRepository versionRepository = mock(DiscountRuleVersionRepository.class);

	private final StudentOrderDiscountRepository orderDiscountRepository = mock(StudentOrderDiscountRepository.class);

	private final DiscountClock clock = mock(DiscountClock.class);

	private final DiscountProperties properties = new DiscountProperties();

	private final DiscountRedemptionService redemptionService = new DiscountRedemptionService(redemptionRepository,
			ruleRepository, versionRepository, orderDiscountRepository, clock, properties);

	private final DiscountCheckoutService checkoutService = new DiscountCheckoutService(ruleRepository,
			mock(DiscountRuleLoader.class), mock(DiscountRuleVersionCourseRepository.class), redemptionRepository,
			orderDiscountRepository, mock(ObjectMapper.class));

	private static DiscountRedemption hold(UUID orderId, UUID ruleId, UUID studentId, Instant expiresAt) {
		DiscountRedemption redemption = DiscountRedemption.reserve(UUID.randomUUID(), ruleId, orderId, studentId,
				NOW.minus(Duration.ofDays(1)), expiresAt);
		ReflectionTestUtils.setField(redemption, "id", UUID.randomUUID());
		return redemption;
	}

	private static RuleSnapshot snapshot(UUID ruleId) {
		return snapshot(ruleId, DiscountType.DISCOUNT_CODE, null);
	}

	private static RuleSnapshot snapshot(UUID ruleId, DiscountType type, Integer maxUses) {
		return new RuleSnapshot(ruleId, UUID.randomUUID(), type, "Rule",
				type == DiscountType.DISCOUNT_CODE ? "TENOFF" : null, SelectionMode.ANY, null,
				DiscountValueType.FIXED, new BigDecimal("100.00"), null, LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 12, 31), maxUses, null, "LKR", List.of(UUID.randomUUID()), List.of());
	}

	@Test
	void oneUsePerStudentAppliesToCodeAndCappedRulesOnly() {
		UUID ruleId = UUID.randomUUID();
		assertThat(snapshot(ruleId, DiscountType.DISCOUNT_CODE, null).oneUsePerStudent()).isTrue();
		assertThat(snapshot(ruleId, DiscountType.BUNDLE_AMOUNT_OFF, 5).oneUsePerStudent()).isTrue();
		assertThat(snapshot(ruleId, DiscountType.BUNDLE_FREE_COURSE, 5).oneUsePerStudent()).isTrue();
		assertThat(snapshot(ruleId, DiscountType.BUNDLE_AMOUNT_OFF, null).oneUsePerStudent()).isFalse();
		assertThat(snapshot(ruleId, DiscountType.ADVANCE_PAYMENT, null).oneUsePerStudent()).isFalse();
		assertThat(snapshot(ruleId, DiscountType.BUNDLE_FREE_COURSE, null).oneUsePerStudent()).isFalse();
	}

	@Test
	void placementNeverChecksOrLocksAnUncappedAutomaticRulePerStudent() {
		UUID ruleId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		when(orderDiscountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		AppliedDiscount applied = new AppliedDiscount(snapshot(ruleId, DiscountType.BUNDLE_AMOUNT_OFF, null),
				new BigDecimal("100.00"), Map.of(1, new BigDecimal("100.00")),
				new Qualification(List.of(), List.of(), List.of(), List.of()));

		checkoutService.persistOrderDiscounts(UUID.randomUUID(), UUID.randomUUID(), studentId, List.of(applied), NOW);
		verify(ruleRepository, never()).lockById(any());
		verify(redemptionRepository, never()).findAllByStudentIdAndRuleIdIn(any(), anyCollection());
		verify(redemptionRepository).save(any());
	}

	@Test
	void aGatewayPaymentHoldsForTheConfiguredTtlAndASlipHoldsOpenEnded() {
		when(clock.now()).thenReturn(NOW);
		UUID orderId = UUID.randomUUID();
		DiscountRedemption gateway = hold(orderId, UUID.randomUUID(), UUID.randomUUID(), NOW.plus(Duration.ofHours(10)));
		when(redemptionRepository.findAllByOrderId(orderId)).thenReturn(List.of(gateway));
		redemptionService.holdForEvidence(orderId, EvidenceKind.GATEWAY);
		assertThat(gateway.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));

		properties.setGatewayHoldTtl(Duration.ofHours(6));
		redemptionService.holdForEvidence(orderId, EvidenceKind.GATEWAY);
		assertThat(gateway.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(6)));

		redemptionService.holdForEvidence(orderId, EvidenceKind.SLIP);
		assertThat(gateway.getExpiresAt()).isNull();
		// A live hold is never re-validated (no rule lock needed).
		verify(ruleRepository, never()).lockById(any());
	}

	@Test
	void consumeIsUnconditionalForALapsedHoldAndIdempotent() {
		when(clock.now()).thenReturn(NOW);
		UUID orderId = UUID.randomUUID();
		DiscountRedemption lapsed = hold(orderId, UUID.randomUUID(), UUID.randomUUID(), NOW.minus(Duration.ofHours(1)));
		when(redemptionRepository.findAllByOrderId(orderId)).thenReturn(List.of(lapsed));

		redemptionService.consume(orderId);
		assertThat(lapsed.getStatus()).isEqualTo(RedemptionStatus.CONSUMED);
		assertThat(lapsed.getExpiresAt()).isNull();
		assertThat(lapsed.getConsumedAt()).isEqualTo(NOW);
		redemptionService.consume(orderId);
		assertThat(lapsed.getConsumedAt()).isEqualTo(NOW);
		// A consumed use never moves again (no hold / restart).
		redemptionService.holdForEvidence(orderId, EvidenceKind.GATEWAY);
		redemptionService.restartHold(orderId);
		assertThat(lapsed.getExpiresAt()).isNull();
	}

	@Test
	void aLapsedHoldOfAMissingRuleIsRefused() {
		when(clock.now()).thenReturn(NOW);
		when(clock.today()).thenReturn(LocalDate.of(2026, 10, 21));
		UUID orderId = UUID.randomUUID();
		UUID ruleId = UUID.randomUUID();
		when(redemptionRepository.findAllByOrderId(orderId))
			.thenReturn(List.of(hold(orderId, ruleId, UUID.randomUUID(), NOW.minus(Duration.ofHours(1)))));
		when(orderDiscountRepository.findAllByOrderId(orderId)).thenReturn(List.of());
		when(ruleRepository.lockById(ruleId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> redemptionService.holdForEvidence(orderId, EvidenceKind.GATEWAY))
			.isInstanceOf(DiscountNoLongerAvailableException.class);
		when(ruleRepository.findById(ruleId)).thenReturn(Optional.of(mock(DiscountRule.class)));
		// No snapshot version recorded for the rule -> refused too (never falls back to "unlimited").
		assertThatThrownBy(() -> redemptionService.requireHoldRetakable(orderId))
			.isInstanceOf(DiscountNoLongerAvailableException.class);
	}

	@Test
	void placementRefusesARuleTheStudentAlreadyHoldsUnderTheRuleLock() {
		UUID ruleId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		when(ruleRepository.lockById(ruleId)).thenReturn(Optional.of(mock(DiscountRule.class)));
		when(redemptionRepository.findAllByStudentIdAndRuleIdIn(any(), anyCollection()))
			.thenReturn(List.of(hold(UUID.randomUUID(), ruleId, studentId, NOW.plus(Duration.ofHours(1)))));
		AppliedDiscount applied = new AppliedDiscount(snapshot(ruleId), new BigDecimal("100.00"),
				Map.of(1, new BigDecimal("100.00")), new Qualification(List.of(), List.of(), List.of(), List.of()));

		assertThatThrownBy(() -> checkoutService.persistOrderDiscounts(UUID.randomUUID(), UUID.randomUUID(),
				studentId, List.of(applied), NOW))
			.isInstanceOf(DiscountNoLongerAvailableException.class);
		verify(ruleRepository).lockById(ruleId);
		verify(redemptionRepository, never()).save(any());
		verify(orderDiscountRepository, never()).save(any());
	}

	@Test
	void aLapsedHoldOfTheStudentDoesNotBlockPlacement() {
		UUID ruleId = UUID.randomUUID();
		UUID studentId = UUID.randomUUID();
		DiscountRedemption lapsed = hold(UUID.randomUUID(), ruleId, studentId, Instant.now().minusSeconds(60));
		assertThat(checkoutService.countingRuleIds(studentId, List.of(ruleId), Instant.now())).isEmpty();
		when(redemptionRepository.findAllByStudentIdAndRuleIdIn(studentId, List.of(ruleId)))
			.thenReturn(List.of(lapsed));
		assertThat(checkoutService.countingRuleIds(studentId, List.of(ruleId), Instant.now())).isEmpty();
		lapsed.consume(Instant.now());
		assertThat(checkoutService.countingRuleIds(studentId, List.of(ruleId), Instant.now())).containsExactly(ruleId);
	}

	@Test
	void placementFailsClosedWhenAnAllocationDoesNotSumToItsAmount() {
		UUID ruleId = UUID.randomUUID();
		when(ruleRepository.lockById(ruleId)).thenReturn(Optional.of(mock(DiscountRule.class)));
		when(redemptionRepository.findAllByStudentIdAndRuleIdIn(any(), anyCollection())).thenReturn(List.of());
		AppliedDiscount broken = new AppliedDiscount(snapshot(ruleId), new BigDecimal("100.00"),
				Map.of(1, new BigDecimal("60.00"), 2, new BigDecimal("39.99")),
				new Qualification(List.of(), List.of(), List.of(), List.of()));

		assertThatThrownBy(() -> checkoutService.persistOrderDiscounts(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), List.of(broken), NOW))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("allocation");
		verify(orderDiscountRepository, never()).save(any());
	}

}
