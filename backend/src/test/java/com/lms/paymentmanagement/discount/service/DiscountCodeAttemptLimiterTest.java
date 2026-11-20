package com.lms.paymentmanagement.discount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.paymentmanagement.discount.config.DiscountProperties;
import com.lms.paymentmanagement.discount.service.DiscountCodeAttemptLimiter.DiscountCodeRateLimitedException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

/** DISC-01 code-probing limiter: window counting, 429 shape, per-(tenant, student) keys, fail-open on Redis loss. */
class DiscountCodeAttemptLimiterTest {

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> ops = mock(ValueOperations.class);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

	private final DiscountProperties properties = new DiscountProperties();

	private final DiscountCodeAttemptLimiter limiter = new DiscountCodeAttemptLimiter(redis, properties);

	private final UUID tenant = UUID.randomUUID();

	private final UUID student = UUID.randomUUID();

	private String key() {
		return "discount:code-invalid:" + tenant + ":" + student;
	}

	@Test
	void defaultsAreTenInvalidAttemptsPerFifteenMinutes() {
		assertThat(properties.getCodeAttempts().getMaxInvalid()).isEqualTo(10);
		assertThat(properties.getCodeAttempts().getWindow()).isEqualTo(Duration.ofMinutes(15));
		assertThat(properties.getGatewayHoldTtl()).isEqualTo(Duration.ofHours(48));
	}

	@Test
	void theFirstInvalidAttemptStartsTheWindowAndAttemptsUpToTheLimitPass() {
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.increment(key())).thenReturn(1L);
		assertThatCode(() -> limiter.recordInvalid(tenant, student)).doesNotThrowAnyException();
		verify(redis).expire(key(), Duration.ofMinutes(15));

		when(ops.increment(key())).thenReturn(10L);
		assertThatCode(() -> limiter.recordInvalid(tenant, student)).doesNotThrowAnyException();
		when(ops.get(key())).thenReturn("9");
		assertThatCode(() -> limiter.requireNotLimited(tenant, student)).doesNotThrowAnyException();
	}

	@Test
	void reachingTheLimitLocksEveryCodeAttemptWith429() {
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.get(key())).thenReturn("10");
		assertThatThrownBy(() -> limiter.requireNotLimited(tenant, student))
			.isInstanceOfSatisfying(DiscountCodeRateLimitedException.class, ex -> {
				assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
				assertThat(ex.getErrorCode()).isEqualTo("DISCOUNT_CODE_RATE_LIMITED");
			});
		when(ops.increment(key())).thenReturn(11L);
		assertThatThrownBy(() -> limiter.recordInvalid(tenant, student))
			.isInstanceOf(DiscountCodeRateLimitedException.class);
	}

	@Test
	void theLimitIsConfigurable() {
		properties.getCodeAttempts().setMaxInvalid(2);
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.get(key())).thenReturn("2");
		assertThatThrownBy(() -> limiter.requireNotLimited(tenant, student))
			.isInstanceOf(DiscountCodeRateLimitedException.class);
		when(ops.get(key())).thenReturn("1");
		assertThatCode(() -> limiter.requireNotLimited(tenant, student)).doesNotThrowAnyException();
	}

	@Test
	void anotherStudentOrTenantHasItsOwnCounter() {
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.get(key())).thenReturn("10");
		assertThatCode(() -> limiter.requireNotLimited(tenant, UUID.randomUUID())).doesNotThrowAnyException();
		assertThatCode(() -> limiter.requireNotLimited(UUID.randomUUID(), student)).doesNotThrowAnyException();
	}

	@Test
	void aRedisOutageFailsOpen() {
		when(redis.opsForValue()).thenReturn(ops);
		when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
		when(ops.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));
		assertThatCode(() -> limiter.requireNotLimited(tenant, student)).doesNotThrowAnyException();
		assertThatCode(() -> limiter.recordInvalid(tenant, student)).doesNotThrowAnyException();
		verify(redis, never()).expire(anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
	}

}
