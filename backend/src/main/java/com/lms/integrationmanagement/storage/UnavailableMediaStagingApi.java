package com.lms.integrationmanagement.storage;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.integrationmanagement.api.MediaStagingApi;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Fail-closed {@link MediaStagingApi} while object storage is not configured
 * - the same conditional pair as {@link UnavailableObjectStorageApi}: video
 * uploads answer 503 instead of silently doing nothing.
 */
@Component
@Conditional(ObjectStorageNotConfiguredCondition.class)
public class UnavailableMediaStagingApi implements MediaStagingApi {

	private static final String MESSAGE = "Uploads aren't available right now. Please try again later.";

	@Override
	public StagingUpload initiate(String objectKey, String contentType) {
		throw new ServiceUnavailableException(MESSAGE);
	}

	@Override
	public List<PresignedPart> presignParts(StagingUpload upload, Collection<Integer> partNumbers, Duration ttl) {
		throw new ServiceUnavailableException(MESSAGE);
	}

	@Override
	public void complete(StagingUpload upload, List<CompletedPart> parts) {
		throw new ServiceUnavailableException(MESSAGE);
	}

	@Override
	public void abort(StagingUpload upload) {
		throw new ServiceUnavailableException(MESSAGE);
	}

	@Override
	public long size(String objectKey) {
		throw new ServiceUnavailableException(MESSAGE);
	}

	@Override
	public InputStream openRange(String objectKey, long fromInclusive, long toInclusive) {
		throw new ServiceUnavailableException(MESSAGE);
	}

	@Override
	public void delete(String objectKey) {
		throw new ServiceUnavailableException(MESSAGE);
	}

}
