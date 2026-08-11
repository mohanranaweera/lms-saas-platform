package com.lms.common.config;

import java.time.Duration;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis is a cache/ephemeral-state layer only, never a source of truth (per
 * .claude/rules/architecture.md) - nothing here should be relied on to
 * survive a flush. Cache names are prefixed "lms:" so Redis key space stays
 * legible if other tooling ever shares the same instance. Tenant-scoped key
 * prefixing is left to individual cache usages once a real tenant-owned
 * cache exists (none does yet); documented here so the convention is
 * visible before the first cache is added.
 *
 * <p>This is the only place {@code @EnableCaching}/{@code @Cacheable}-style
 * declarative caching gets configured. {@code
 * com.lms.identityaccessservice.service.DeviceSessionCacheService} does NOT
 * go through this - it talks to Redis directly via a plain {@code
 * StringRedisTemplate}, because its per-entry TTL is dynamic (bounded by each
 * session's own remaining lifetime, not a fixed duration a {@code
 * RedisCacheConfiguration} can express). Both share the same
 * autoconfigured {@code RedisConnectionFactory} (from {@code
 * spring.data.redis.*}) - there is exactly one Redis connection, two access
 * patterns for two different needs, not two independent configurations.
 */
@Configuration
@EnableCaching
public class CacheConfig {

	@Bean
	public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
		RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
			.prefixCacheNameWith("lms:")
			.entryTtl(Duration.ofMinutes(10))
			.serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
			.serializeValuesWith(
					RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))
			.disableCachingNullValues();

		return builder -> builder.cacheDefaults(configuration);
	}

}
