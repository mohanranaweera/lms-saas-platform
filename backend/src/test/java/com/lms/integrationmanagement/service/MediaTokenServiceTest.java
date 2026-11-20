package com.lms.integrationmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.MediaProviderConnection;
import com.lms.integrationmanagement.gateway.media.MediaProviderException;
import com.lms.integrationmanagement.gateway.media.OAuthProviderClient;
import com.lms.integrationmanagement.gateway.media.OAuthTokens;
import com.lms.integrationmanagement.service.MediaCredentialsCodec.StoredCredentials;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * MEDIA-INT-01 security review S5: a request that finds another request
 * refreshing the same token never blocks its thread.
 */
@ExtendWith(MockitoExtension.class)
class MediaTokenServiceTest {

	private static final UUID CONNECTION_ID = UUID.randomUUID();

	@Mock
	private MediaConnectionStore store;

	@Mock
	private MediaCredentialsCodec codec;

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	@Mock
	private OAuthProviderClient zoomClient;

	private MediaTokenService service;

	@BeforeEach
	void setUp() {
		when(zoomClient.provider()).thenReturn(MediaProvider.ZOOM);
		service = new MediaTokenService(store, codec, redisTemplate, List.of(zoomClient));
	}

	private MediaProviderConnection zoomConnection(Instant expiresAt) {
		MediaProviderConnection connection = MediaProviderConnection.teacherOAuth(UUID.randomUUID(), MediaProvider.ZOOM,
				UUID.randomUUID(), "zoom-user", "teacher@example.test", "LICENSED", "v1:sealed", expiresAt, "scope",
				Instant.now());
		when(store.require(CONNECTION_ID)).thenReturn(connection);
		lenient().when(codec.open(connection)).thenReturn(StoredCredentials.oauth("current-access", "current-refresh"));
		return connection;
	}

	private void lockHeldByAnotherRequest() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
	}

	@Test
	void usesTheStillValidTokenInsteadOfWaitingWhileAnotherRequestRefreshes() {
		zoomConnection(Instant.now().plus(Duration.ofMinutes(2)));
		lockHeldByAnotherRequest();

		assertThat(service.validAccessToken(CONNECTION_ID)).isEqualTo("current-access");
		verify(zoomClient, never()).refresh(anyString());
	}

	@Test
	void failsFastInsteadOfSleepingWhenTheTokenHasAlreadyExpired() {
		zoomConnection(Instant.now().minus(Duration.ofMinutes(1)));
		lockHeldByAnotherRequest();
		long started = System.nanoTime();

		assertThatThrownBy(() -> service.validAccessToken(CONNECTION_ID)).isInstanceOf(MediaProviderException.class);

		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
		verify(zoomClient, never()).refresh(anyString());
	}

	@Test
	void refreshesUnderTheLockAndReleasesIt() {
		MediaProviderConnection connection = zoomConnection(Instant.now().plus(Duration.ofMinutes(2)));
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
		when(codec.open(eq("v1:sealed"), any())).thenReturn(StoredCredentials.oauth("current-access", "current-refresh"));
		when(zoomClient.refresh("current-refresh"))
			.thenReturn(new OAuthTokens("new-access", "new-refresh", Instant.now().plusSeconds(3600), "scope"));
		when(codec.seal(any(), any())).thenReturn("v1:resealed");

		assertThat(service.validAccessToken(CONNECTION_ID)).isEqualTo("new-access");
		verify(store).storeRefreshedTokens(eq(CONNECTION_ID), eq("v1:resealed"), any());
		verify(redisTemplate).delete("media:refresh-lock:" + CONNECTION_ID);
		assertThat(connection.getProvider()).isEqualTo(MediaProvider.ZOOM);
	}

}
