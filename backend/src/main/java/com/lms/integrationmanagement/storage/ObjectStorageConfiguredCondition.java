package com.lms.integrationmanagement.storage;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Matches only when EVERY property {@link S3ObjectStorageApi} actually needs
 * to authenticate against a real provider - {@code bucket}, {@code
 * access-key}, AND {@code secret-key} - has a non-blank value. Used to select
 * {@link S3ObjectStorageApi} as the active {@code ObjectStorageApi} bean.
 *
 * <p><b>Security-review fix (Wave 5 content-security review):</b> an earlier
 * version of this condition checked {@code bucket} alone. A partially-
 * configured environment (e.g. {@code OBJECT_STORAGE_BUCKET} set but the
 * access/secret key env vars left blank) would then select {@code
 * S3ObjectStorageApi} - not the fail-closed {@link
 * UnavailableObjectStorageApi} - even though every real call would still
 * degrade to a 503 at request time (blank credentials reliably fail the SDK
 * call). Checking the full required set here makes that fail-closed boundary
 * itself correct, not just its eventual runtime behavior. {@code endpoint} is
 * intentionally NOT required here - it's blank for real AWS S3 (only a
 * non-AWS endpoint like the dev MinIO instance needs an override) and {@code
 * S3ObjectStorageApi} already treats a blank endpoint as "use the SDK
 * default", per its own javadoc.
 *
 * <p>Deliberately a hand-written {@link Condition} rather than {@code
 * @ConditionalOnProperty} - that annotation only distinguishes "present vs.
 * absent"/"equals a specific value", not "present but blank", and this
 * codebase's blank-default-fail-closed convention (see {@code
 * PaymentGatewayProperties}) needs exactly the latter.
 */
final class ObjectStorageConfiguredCondition implements Condition {

	static final String BUCKET_PROPERTY = "object-storage.bucket";

	static final String ACCESS_KEY_PROPERTY = "object-storage.access-key";

	static final String SECRET_KEY_PROPERTY = "object-storage.secret-key";

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return isConfigured(context);
	}

	static boolean isConfigured(ConditionContext context) {
		return StringUtils.hasText(context.getEnvironment().getProperty(BUCKET_PROPERTY, ""))
				&& StringUtils.hasText(context.getEnvironment().getProperty(ACCESS_KEY_PROPERTY, ""))
				&& StringUtils.hasText(context.getEnvironment().getProperty(SECRET_KEY_PROPERTY, ""));
	}

}
