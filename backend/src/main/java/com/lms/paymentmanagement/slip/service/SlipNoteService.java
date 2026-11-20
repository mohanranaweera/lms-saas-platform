package com.lms.paymentmanagement.slip.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.paymentmanagement.slip.domain.PaymentSlipNote;
import com.lms.paymentmanagement.slip.repository.PaymentSlipNoteRepository;
import com.lms.paymentmanagement.slip.repository.PaymentSlipRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PAY-HUB-01: internal staff notes on a payment slip (Klass "Add Note").
 * Staff-only on both paths - reading needs {@code PAYMENTS_SLIPS}/{@code VIEW},
 * writing needs {@code PAYMENTS_SLIPS}/{@code CREATE_EDIT} (Tenant Admin and
 * Finance Staff). A student - even the slip's owner - is refused by the
 * permission check before the slip is looked up. The slip is resolved
 * through the tenant-scoped repository, so another tenant's slip id is a 404.
 * Notes are append-only and never touch the slip's review state.
 */
@Service
public class SlipNoteService {

	static final int MAX_NOTES_PER_SLIP = 200;

	private final PaymentSlipRepository paymentSlipRepository;

	private final PaymentSlipNoteRepository paymentSlipNoteRepository;

	private final PermissionCheckService permissionCheckService;

	private final UserProvisioningApi userProvisioningApi;

	private final TenantContext tenantContext;

	public SlipNoteService(PaymentSlipRepository paymentSlipRepository,
			PaymentSlipNoteRepository paymentSlipNoteRepository, PermissionCheckService permissionCheckService,
			UserProvisioningApi userProvisioningApi, TenantContext tenantContext) {
		this.paymentSlipRepository = paymentSlipRepository;
		this.paymentSlipNoteRepository = paymentSlipNoteRepository;
		this.permissionCheckService = permissionCheckService;
		this.userProvisioningApi = userProvisioningApi;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public List<SlipNoteView> listNotes(UUID slipId) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
		requireSlip(slipId);
		List<PaymentSlipNote> notes = paymentSlipNoteRepository.findAllBySlipIdOldestFirst(slipId,
				MAX_NOTES_PER_SLIP);
		if (notes.isEmpty()) {
			return List.of();
		}
		Map<UUID, String> emails = emailsOf(notes.stream().map(PaymentSlipNote::getAuthorUserId).distinct().toList());
		return notes.stream().map(note -> toView(note, emails)).toList();
	}

	@Transactional
	public SlipNoteView addNote(UUID slipId, String body) {
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.CREATE_EDIT);
		String trimmed = body == null ? "" : body.strip();
		if (trimmed.isEmpty() || trimmed.length() > PaymentSlipNote.MAX_BODY_LENGTH) {
			throw new FieldValidationException("Invalid note", List
				.of(new FieldError("body", "must be 1 to " + PaymentSlipNote.MAX_BODY_LENGTH + " characters")));
		}
		requireSlip(slipId);
		UUID authorUserId = AuthenticatedPrincipalHolder.get().userId();
		PaymentSlipNote note = paymentSlipNoteRepository
			.save(new PaymentSlipNote(tenantContext.getTenantId(), slipId, authorUserId, trimmed));
		return toView(note, emailsOf(List.of(authorUserId)));
	}

	private void requireSlip(UUID slipId) {
		// Tenant-scoped existsById: another tenant's slip is indistinguishable from a missing one.
		if (!paymentSlipRepository.existsById(slipId)) {
			throw new NotFoundException("Payment slip not found");
		}
	}

	private Map<UUID, String> emailsOf(Collection<UUID> userIds) {
		return userProvisioningApi.findTenantUserSummaries(userIds)
			.stream()
			.filter(summary -> summary.email() != null)
			.collect(Collectors.toMap(TenantUserSummary::userId, TenantUserSummary::email, (a, b) -> a));
	}

	private static SlipNoteView toView(PaymentSlipNote note, Map<UUID, String> emails) {
		return new SlipNoteView(note.getId(), note.getSlipId(), note.getBody(), note.getAuthorUserId(),
				emails.get(note.getAuthorUserId()), note.getCreatedAt());
	}

}
