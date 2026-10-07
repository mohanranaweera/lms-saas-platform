package com.lms.videoaccessmanagement.service.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** VIDEO Phase 8: request signing, accepted key types and version comparison for the AOC Player. */
class PlayerCryptoTest {

	@Test
	void onlyP256EcKeysAreAccepted() throws Exception {
		assertThat(PlayerRequestVerifier.parseP256PublicKey(spki(ecKeys("secp256r1")))).isPresent();
		assertThat(PlayerRequestVerifier.parseP256PublicKey(spki(ecKeys("secp384r1")))).isEmpty();
		KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
		rsa.initialize(2048);
		assertThat(PlayerRequestVerifier.parseP256PublicKey(spki(rsa.generateKeyPair()))).isEmpty();
		assertThat(PlayerRequestVerifier.parseP256PublicKey("not base64!")).isEmpty();
		assertThat(PlayerRequestVerifier.parseP256PublicKey(null)).isEmpty();
	}

	@Test
	void aSignatureCoversMethodPathTimeNonceAndBody() throws Exception {
		KeyPair keys = ecKeys("secp256r1");
		PlayerRequestVerifier verifier = new PlayerRequestVerifier(redisAccepting());
		String now = String.valueOf(Instant.now().getEpochSecond());
		byte[] body = "{\"launchCode\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
		String signature = sign(keys, "POST", "/api/v1/player/exchange", now, "nonce-0123456789ab", body);

		assertThat(verifier.verify(request("/api/v1/player/exchange", now, "nonce-0123456789ab", signature, body),
				spki(keys), "inst")).isEmpty();
		assertThat(verifier.verify(request("/api/v1/player/exchange", now, "nonce-0123456789ab", signature,
				"{\"launchCode\":\"abd\"}".getBytes(StandardCharsets.UTF_8)), spki(keys), "inst"))
			.contains("bad_signature");
		assertThat(verifier.verify(request("/api/v1/player/sessions/x/end", now, "nonce-0123456789ab", signature, body),
				spki(keys), "inst"))
			.contains("bad_signature");
		assertThat(verifier.verify(request("/api/v1/player/exchange", now, "nonce-0123456789ab", signature, body),
				spki(ecKeys("secp256r1")), "inst"))
			.contains("bad_signature");
		String stale = String.valueOf(Instant.now().minus(Duration.ofMinutes(5)).getEpochSecond());
		assertThat(verifier.verify(request("/api/v1/player/exchange", stale, "nonce-0123456789ab",
				sign(keys, "POST", "/api/v1/player/exchange", stale, "nonce-0123456789ab", body), body), spki(keys),
				"inst"))
			.contains("clock_skew");
	}

	@Test
	void anUnavailableNonceStoreFailsClosed() throws Exception {
		KeyPair keys = ecKeys("secp256r1");
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
		PlayerRequestVerifier verifier = new PlayerRequestVerifier(redis);
		String now = String.valueOf(Instant.now().getEpochSecond());
		byte[] body = new byte[0];

		assertThat(verifier.verify(request("/p", now, "nonce-0123456789ab",
				sign(keys, "POST", "/p", now, "nonce-0123456789ab", body), body), spki(keys), "inst"))
			.contains("nonce_store_unavailable");
	}

	@Test
	void playerVersionsCompareNumerically() {
		assertThat(PlayerVersion.isAtLeast("1.0.0", "0.1.0")).isTrue();
		assertThat(PlayerVersion.isAtLeast("0.10.0", "0.9.9")).isTrue();
		assertThat(PlayerVersion.isAtLeast("0.1.0", "0.1.0")).isTrue();
		assertThat(PlayerVersion.isAtLeast("0.0.9", "0.1.0")).isFalse();
		assertThat(PlayerVersion.isAtLeast("1.2.3-beta.1", "1.2.3")).isTrue();
		assertThat(PlayerVersion.isAtLeast("latest", "0.1.0")).isFalse();
		assertThat(PlayerVersion.isAtLeast(null, "0.1.0")).isFalse();
	}

	private static PlayerRequestVerifier.SignedRequest request(String path, String timestamp, String nonce,
			String signature, byte[] body) {
		return new PlayerRequestVerifier.SignedRequest("POST", path, timestamp, nonce, signature, body);
	}

	@SuppressWarnings("unchecked")
	private static StringRedisTemplate redisAccepting() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), eq("1"), org.mockito.ArgumentMatchers.any(Duration.class)))
			.thenReturn(true);
		return redis;
	}

	private static KeyPair ecKeys(String curve) throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec(curve));
		return generator.generateKeyPair();
	}

	private static String spki(KeyPair keys) {
		return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
	}

	private static String sign(KeyPair keys, String method, String path, String timestamp, String nonce, byte[] body)
			throws Exception {
		Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
		signer.initSign(keys.getPrivate());
		signer.update(PlayerRequestVerifier.canonical(method, path, timestamp, nonce, body));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
	}

}
