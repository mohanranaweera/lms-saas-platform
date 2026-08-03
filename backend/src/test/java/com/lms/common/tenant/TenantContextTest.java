package com.lms.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

	private final TenantContextHolder tenantContext = new TenantContextHolder();

	@AfterEach
	void clear() {
		TenantContextHolder.clear();
	}

	@Test
	void throwsWhenReadBeforePopulated() {
		assertThatThrownBy(tenantContext::getTenantId).isInstanceOf(TenantContextNotResolvedException.class);
	}

	@Test
	void returnsExplicitlySetTenantId() {
		UUID tenantId = UUID.randomUUID();
		TenantContextHolder.set(tenantId);

		assertThat(tenantContext.getTenantId()).isEqualTo(tenantId);
	}

	@Test
	void clearRemovesPopulatedValue() {
		TenantContextHolder.set(UUID.randomUUID());
		TenantContextHolder.clear();

		assertThatThrownBy(tenantContext::getTenantId).isInstanceOf(TenantContextNotResolvedException.class);
	}

	@Test
	void rejectsNullTenantId() {
		assertThatThrownBy(() -> TenantContextHolder.set(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void isolatedAcrossThreads() throws InterruptedException {
		UUID tenantId = UUID.randomUUID();
		TenantContextHolder.set(tenantId);

		boolean[] otherThreadSawUnset = { false };
		Thread other = new Thread(() -> otherThreadSawUnset[0] = !TenantContextHolder.isSet());
		other.start();
		other.join();

		assertThat(otherThreadSawUnset[0]).isTrue();
		assertThat(tenantContext.getTenantId()).isEqualTo(tenantId);
	}

}
