package com.lms.paymentmanagement.discount.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContextNotResolvedException;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.paymentmanagement.discount.domain.DiscountCourseRole;
import com.lms.paymentmanagement.discount.domain.DiscountRule;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Defense-in-depth for the DISC-01 repository methods that bypass the
 * structural {@code Specification} tenant filter ({@code @Query}-backed
 * {@code *Unchecked} methods taking an explicit tenant id), mirroring {@code
 * PaymentSlipRepositoryTenantGuardTest}: the public {@code default} wrappers
 * must pass the tenant from the trusted {@link TenantContextHolder} - never a
 * caller value - and refuse to run without a resolved tenant. No Spring
 * context: the real {@code default} methods run via {@link
 * org.mockito.Mockito#CALLS_REAL_METHODS} while the {@code *Unchecked} methods
 * are stubbed.
 */
@Tag("cross-tenant")
class DiscountRepositoryTenantGuardTest {

	private final DiscountRuleRepository ruleRepository = mock(DiscountRuleRepository.class, CALLS_REAL_METHODS);

	private final DiscountRuleVersionCourseRepository courseRepository = mock(
			DiscountRuleVersionCourseRepository.class, CALLS_REAL_METHODS);

	@AfterEach
	void clearTenantContext() {
		TenantContextHolder.clear();
	}

	@Test
	void lockByIdPassesTheContextTenant() {
		UUID tenantId = UUID.randomUUID();
		TenantContextHolder.set(tenantId);
		UUID id = UUID.randomUUID();
		DiscountRule rule = mock(DiscountRule.class);
		when(ruleRepository.lockByIdUnchecked(id, tenantId)).thenReturn(Optional.of(rule));

		assertThat(ruleRepository.lockById(id)).contains(rule);
		verify(ruleRepository).lockByIdUnchecked(id, tenantId);
	}

	@Test
	void lockByIdRefusesWithoutAResolvedTenant() {
		assertThatThrownBy(() -> ruleRepository.lockById(UUID.randomUUID()))
			.isInstanceOf(TenantContextNotResolvedException.class);
		verify(ruleRepository, never()).lockByIdUnchecked(any(), any());
	}

	@Test
	void lockRuleIdsForCartPassesTheContextTenantAndNeverAnEmptyCourseList() {
		UUID tenantId = UUID.randomUUID();
		TenantContextHolder.set(tenantId);
		Set<UUID> courses = Set.of(UUID.randomUUID());
		UUID locked = UUID.randomUUID();
		when(ruleRepository.lockRuleIdsForCartUnchecked(tenantId, courses, "SAVE10")).thenReturn(List.of(locked));

		assertThat(ruleRepository.lockRuleIdsForCart(courses, "SAVE10")).containsExactly(locked);
		verify(ruleRepository).lockRuleIdsForCartUnchecked(tenantId, courses, "SAVE10");

		assertThat(ruleRepository.lockRuleIdsForCart(Set.of(), "SAVE10")).isEmpty();
		ruleRepository.lockRuleIdsForCart(courses, null);
		verify(ruleRepository).lockRuleIdsForCartUnchecked(tenantId, courses, "");
	}

	@Test
	void lockRuleIdsForCartRefusesWithoutAResolvedTenant() {
		assertThatThrownBy(() -> ruleRepository.lockRuleIdsForCart(Set.of(UUID.randomUUID()), "SAVE10"))
			.isInstanceOf(TenantContextNotResolvedException.class);
		verify(ruleRepository, never()).lockRuleIdsForCartUnchecked(any(), anyCollection(), anyString());
	}

	@Test
	void findRewardWindowsPassesTheContextTenantAndTheRewardRole() {
		UUID tenantId = UUID.randomUUID();
		TenantContextHolder.set(tenantId);
		Set<UUID> rules = Set.of(UUID.randomUUID());
		List<Object[]> rows = List.<Object[]>of(new Object[] { UUID.randomUUID(), 2 });
		when(courseRepository.findRewardWindowsUnchecked(tenantId, rules, DiscountCourseRole.REWARD)).thenReturn(rows);

		assertThat(courseRepository.findRewardWindows(rules)).isSameAs(rows);
		verify(courseRepository).findRewardWindowsUnchecked(tenantId, rules, DiscountCourseRole.REWARD);
		assertThat(courseRepository.findRewardWindows(Set.of())).isEmpty();
	}

	@Test
	void findRewardWindowsRefusesWithoutAResolvedTenant() {
		assertThatThrownBy(() -> courseRepository.findRewardWindows(Set.of(UUID.randomUUID())))
			.isInstanceOf(TenantContextNotResolvedException.class);
		verify(courseRepository, never()).findRewardWindowsUnchecked(any(), anyCollection(), any());
	}

}
