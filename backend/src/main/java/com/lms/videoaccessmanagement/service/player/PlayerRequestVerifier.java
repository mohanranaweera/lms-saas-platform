package com.lms.videoaccessmanagement.service.player;

import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Proof of possession for AOC Player requests (VIDEO Phase 8, ADR-025 §3 +
 * Amendment 1). A signed request carries {@code X-Player-Installation},
 * {@code X-Player-Timestamp} (epoch seconds), {@code X-Player-Nonce} and
 * {@code X-Player-Signature}: ECDSA P-256 / SHA-256, IEEE P1363 ({@code r||s},
 * what Windows CNG produces), base64url, over
 * {@code METHOD\nPATH\nTIMESTAMP\nNONCE\nbase64url(SHA-256(body))}.
 *
 * <p>The signature is checked first; only then is the nonce spent (Redis
 * {@code SET NX}, so a captured request cannot be replayed). If Redis is
 * unreachable the request is refused - this is an authentication path, so it
 * fails closed.
 */
@Component
public class PlayerRequestVerifier {

	public static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(60);

	private static final Logger log = LoggerFactory.getLogger(PlayerRequestVerifier.class);

	private static final Pattern NONCE = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");

	private static final String NONCE_PREFIX = "player:nonce:";

	private static final ECParameterSpec P256 = p256();

	private final StringRedisTemplate redis;

	public PlayerRequestVerifier(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** The signed parts of one player request, as received. */
	public record SignedRequest(String method, String path, String timestamp, String nonce, String signature,
			byte[] body) {
	}

	/**
	 * @return empty when the request is authentic and fresh; otherwise the
	 * internal reason (for the security log only - never sent to the caller).
	 */
	public Optional<String> verify(SignedRequest request, String installationKey, String nonceScope) {
		if (request.timestamp() == null || request.nonce() == null || request.signature() == null) {
			return Optional.of("missing_signature_headers");
		}
		long epochSeconds;
		try {
			epochSeconds = Long.parseLong(request.timestamp());
		}
		catch (NumberFormatException malformed) {
			return Optional.of("malformed_timestamp");
		}
		if (Duration.between(Instant.ofEpochSecond(epochSeconds), Instant.now()).abs().compareTo(MAX_CLOCK_SKEW) > 0) {
			return Optional.of("clock_skew");
		}
		if (!NONCE.matcher(request.nonce()).matches()) {
			return Optional.of("malformed_nonce");
		}
		if (!signatureMatches(request, installationKey)) {
			return Optional.of("bad_signature");
		}
		try {
			Boolean fresh = redis.opsForValue()
				.setIfAbsent(NONCE_PREFIX + nonceScope + ":" + request.nonce(), "1", MAX_CLOCK_SKEW.multipliedBy(2));
			if (!Boolean.TRUE.equals(fresh)) {
				return Optional.of("nonce_replayed");
			}
		}
		catch (RuntimeException redisDown) {
			log.atWarn().setMessage("player.nonce_store_unavailable")
				.addKeyValue("errorType", redisDown.getClass().getSimpleName())
				.log();
			return Optional.of("nonce_store_unavailable");
		}
		return Optional.empty();
	}

	/**
	 * Parses a base64 X.509 SubjectPublicKeyInfo and accepts only an EC key on
	 * P-256 (secp256r1).
	 */
	public static Optional<PublicKey> parseP256PublicKey(String base64Spki) {
		if (base64Spki == null || base64Spki.length() > 512) {
			return Optional.empty();
		}
		try {
			PublicKey key = KeyFactory.getInstance("EC")
				.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Spki)));
			if (key instanceof ECPublicKey ecKey && sameCurve(ecKey.getParams(), P256)) {
				return Optional.of(key);
			}
			return Optional.empty();
		}
		catch (GeneralSecurityException | IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}

	/** The exact bytes a player signs. */
	public static byte[] canonical(String method, String path, String timestamp, String nonce, byte[] body) {
		String bodyHash;
		try {
			bodyHash = Base64.getUrlEncoder()
				.withoutPadding()
				.encodeToString(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
		}
		catch (GeneralSecurityException impossible) {
			throw new IllegalStateException("SHA-256 unavailable", impossible);
		}
		return (method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash)
			.getBytes(StandardCharsets.UTF_8);
	}

	private static boolean signatureMatches(SignedRequest request, String installationKey) {
		Optional<PublicKey> key = parseP256PublicKey(installationKey);
		if (key.isEmpty()) {
			return false;
		}
		try {
			byte[] signature = Base64.getUrlDecoder().decode(request.signature());
			if (signature.length != 64) {
				return false;
			}
			Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
			verifier.initVerify(key.get());
			verifier.update(canonical(request.method(), request.path(), request.timestamp(), request.nonce(),
					request.body()));
			return verifier.verify(signature);
		}
		catch (GeneralSecurityException | IllegalArgumentException invalid) {
			return false;
		}
	}

	private static boolean sameCurve(ECParameterSpec actual, ECParameterSpec expected) {
		return actual.getCurve().equals(expected.getCurve()) && actual.getGenerator().equals(expected.getGenerator())
				&& actual.getOrder().equals(expected.getOrder()) && actual.getCofactor() == expected.getCofactor();
	}

	private static ECParameterSpec p256() {
		try {
			AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
			parameters.init(new ECGenParameterSpec("secp256r1"));
			return parameters.getParameterSpec(ECParameterSpec.class);
		}
		catch (GeneralSecurityException impossible) {
			throw new IllegalStateException("secp256r1 unavailable", impossible);
		}
	}

}
