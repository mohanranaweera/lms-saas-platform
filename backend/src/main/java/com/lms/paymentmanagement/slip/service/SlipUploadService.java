package com.lms.paymentmanagement.slip.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.service.OrderService;
import com.lms.paymentmanagement.slip.domain.PaymentSlip;
import com.lms.paymentmanagement.slip.domain.PaymentSlipFlag;
import com.lms.paymentmanagement.slip.repository.PaymentSlipFlagRepository;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * SLIP-1: server-side upload validation (MIME/content-sniffing, size,
 * ownership) BEFORE any write, zero partial write on any failure. Mirrors
 * {@code MaterialService.createMaterial}'s transaction-boundary discipline
 * (plan §9 item 1, {@code .claude/rules/backend.md}'s "never span a
 * transaction across an outbound call" rule): this method carries NO {@code
 * @Transactional} annotation - it calls {@link ObjectStorageApi}, an outbound
 * dependency, and the single {@code paymentSlipRepository.save} call is
 * already self-transactional (Spring Data's default per-method transaction),
 * so no explicit transaction needs to span it. The duplicate-check-and-
 * advance step that follows is delegated to {@link SlipDuplicateCheckService},
 * a separate bean whose own {@code @Transactional} method is invoked through
 * the Spring proxy (not a same-class self-invocation, which would silently
 * skip the transaction).
 *
 * <p>The object store is written to BEFORE {@code paymentSlipRepository.save}
 * - if that save fails for any reason (including a
 * {@code uq_payment_slip_tenant_order_active} unique-index violation, mapped
 * to {@code 409 CONFLICT}), the just-stored object would otherwise be
 * orphaned with no corresponding DB row, so a save failure triggers a
 * best-effort compensating {@link ObjectStorageApi#delete} of that object
 * before the original exception is re-thrown unchanged.
 */
@Service
public class SlipUploadService {

	private static final Logger log = LoggerFactory.getLogger(SlipUploadService.class);

	private static final int STREAM_READ_CHUNK_SIZE = 8192;

	private final OrderService orderService;

	private final PaymentSlipRepository paymentSlipRepository;

	private final PaymentSlipFlagRepository paymentSlipFlagRepository;

	private final ObjectStorageApi slipStorageApi;

	private final SlipDuplicateCheckService slipDuplicateCheckService;

	private final UserProvisioningApi userProvisioningApi;

	private final TenantContext tenantContext;

	private final long maxFileSizeBytes;

	public SlipUploadService(OrderService orderService, PaymentSlipRepository paymentSlipRepository,
			PaymentSlipFlagRepository paymentSlipFlagRepository, ObjectStorageApi slipStorageApi,
			SlipDuplicateCheckService slipDuplicateCheckService, UserProvisioningApi userProvisioningApi,
			TenantContext tenantContext,
			@Value("${app.payment.slip.max-file-size-bytes}") long maxFileSizeBytes) {
		this.orderService = orderService;
		this.paymentSlipRepository = paymentSlipRepository;
		this.paymentSlipFlagRepository = paymentSlipFlagRepository;
		this.slipStorageApi = slipStorageApi;
		this.slipDuplicateCheckService = slipDuplicateCheckService;
		this.userProvisioningApi = userProvisioningApi;
		this.tenantContext = tenantContext;
		this.maxFileSizeBytes = maxFileSizeBytes;
	}

	/**
	 * @param orderId the order this slip is evidence of payment for - must be
	 * owned by the currently authenticated student (never a client-supplied
	 * studentId), per {@link OrderService#loadOrderOwnedByCurrentStudent}.
	 */
	public PaymentSlipView uploadSlip(UUID orderId, String referenceNumber, MultipartFile file) {
		StudentOrder order = orderService.loadOrderOwnedByCurrentStudent(orderId);

		// Friendly pre-check for the uq_payment_slip_tenant_order_active
		// unique index (see PaymentSlipRepository#existsActiveSlipForOrder's
		// javadoc for why this is a pre-check, not a substitute for the DB
		// constraint) - runs BEFORE any storage/DB write, so a rejected
		// second upload against an order that already has an active slip
		// never touches the object store at all.
		if (paymentSlipRepository.existsActiveSlipForOrder(orderId)) {
			throw new ConflictException("You already have a payment slip under review for this order");
		}

		// Bounded streaming read (mirrors MaterialService.readBoundedBytes):
		// aborts as soon as the running byte count exceeds maxFileSizeBytes,
		// so an oversized upload is rejected without ever buffering the full
		// file in memory, and BEFORE any storage/DB write happens.
		byte[] bytes = readBoundedBytes(file, maxFileSizeBytes);
		if (bytes.length == 0) {
			throw new PayloadTooLargeException("The uploaded file exceeds the maximum allowed size");
		}
		String sniffedMimeType = SlipContentSniffer.sniff(bytes);
		if (sniffedMimeType == null) {
			throw new UnsupportedMediaTypeException(
					"The uploaded file's content does not match an accepted format (PDF or image)");
		}

		String imageHash = sha256Hex(bytes);
		UUID tenantId = tenantContext.getTenantId();
		String originalFilename = sanitizeFilename(file.getOriginalFilename());

		StoredObject stored = slipStorageApi.store(new StoreObjectCommand(tenantId,
				new ByteArrayInputStream(bytes), sniffedMimeType, bytes.length, originalFilename));

		PaymentSlip slip = new PaymentSlip(tenantId, order.getId(), order.getStudentId(), stored.objectKey(),
				referenceNumber, imageHash);
		try {
			slip = paymentSlipRepository.save(slip);
		}
		catch (RuntimeException saveFailure) {
			// The object already landed in storage before this save was
			// attempted - delete it so it doesn't orphan (best-effort only;
			// a delete failure must never mask the original save failure).
			try {
				slipStorageApi.delete(stored.objectKey());
			}
			catch (RuntimeException deleteFailure) {
				log.warn("Failed to delete orphaned slip storage object '{}' after a payment_slip save failure",
						stored.objectKey(), deleteFailure);
			}
			throw saveFailure;
		}

		slipDuplicateCheckService.runChecksAndAdvance(slip.getId());

		PaymentSlip refreshed = paymentSlipRepository.findById(slip.getId())
			.orElseThrow(() -> new NotFoundException("Payment slip not found"));
		return toView(refreshed, order);
	}

	private PaymentSlipView toView(PaymentSlip slip, StudentOrder order) {
		// runChecksAndAdvance (called just before this) may have inserted
		// DUPLICATE_REFERENCE/DUPLICATE_IMAGE_HASH flag rows - the upload
		// response must reflect them immediately, not report an empty list
		// regardless of what was actually just created.
		List<PaymentSlipFlagView> flagViews = paymentSlipFlagRepository.findAllBySlipId(slip.getId())
			.stream()
			.map(SlipUploadService::toFlagView)
			.toList();
		// reviewerId is always null on a freshly-uploaded slip, so there is
		// never a reviewer id to batch alongside the student id here.
		String studentEmail = userProvisioningApi.findTenantUserSummaries(List.of(slip.getStudentId()))
			.stream()
			.filter(summary -> summary.userId().equals(slip.getStudentId()))
			.map(TenantUserSummary::email)
			.findFirst()
			.orElse(null);
		return new PaymentSlipView(slip.getId(), slip.getOrderId(), slip.getStudentId(), slip.getReferenceNumber(),
				slip.getStatus(), slip.getSubmittedAt(), slip.getReviewerId(), slip.getReviewedAt(), flagViews,
				studentEmail, null, order.getAmount(), order.getCurrency());
	}

	private static PaymentSlipFlagView toFlagView(PaymentSlipFlag flag) {
		return new PaymentSlipFlagView(flag.getId(), flag.getFlagType(), flag.getDetectedAt());
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			// SHA-256 is a JDK-guaranteed algorithm (every conforming JDK
			// implementation ships it) - this branch is unreachable in
			// practice, but must still compile/fail loudly rather than
			// silently proceeding with no hash.
			throw new IllegalStateException("SHA-256 MessageDigest is unavailable", e);
		}
	}

	private static String sanitizeFilename(String rawFilename) {
		String name = rawFilename == null ? "slip" : rawFilename;
		int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (lastSlash >= 0) {
			name = name.substring(lastSlash + 1);
		}
		name = name.chars()
			.filter(c -> c >= 0x20)
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString();
		if (name.isBlank()) {
			name = "slip";
		}
		return name.length() > 255 ? name.substring(0, 255) : name;
	}

	/**
	 * Reads {@code file}'s content incrementally, aborting with {@link
	 * PayloadTooLargeException} as soon as the running byte count exceeds
	 * {@code maxFileSizeBytes} - the file is never fully buffered before the
	 * size check runs, and never trusts {@code file.getSize()}/a
	 * client-declared {@code Content-Length} to decide whether to reject.
	 */
	private static byte[] readBoundedBytes(MultipartFile file, long maxFileSizeBytes) {
		try (InputStream input = file.getInputStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[STREAM_READ_CHUNK_SIZE];
			long totalBytesRead = 0;
			int bytesRead;
			while ((bytesRead = input.read(chunk)) != -1) {
				totalBytesRead += bytesRead;
				if (totalBytesRead > maxFileSizeBytes) {
					throw new PayloadTooLargeException("The uploaded file exceeds the maximum allowed size");
				}
				buffer.write(chunk, 0, bytesRead);
			}
			return buffer.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read uploaded slip file", e);
		}
	}

}
