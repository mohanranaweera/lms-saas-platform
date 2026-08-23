package com.lms.paymentmanagement.payment.service;

import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import com.lms.paymentmanagement.payment.repository.PaymentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link PaymentStatusApi} - see that interface's javadoc. Every
 * read here goes through {@link PaymentRepository#findById}/{@link
 * StudentOrderRepository}'s inherited tenant-scoped finders, exactly like
 * every other read in this module.
 */
@Service
@Transactional(readOnly = true)
public class PaymentStatusApiImpl implements PaymentStatusApi {

	private final PaymentRepository paymentRepository;

	private final StudentOrderRepository studentOrderRepository;

	public PaymentStatusApiImpl(PaymentRepository paymentRepository, StudentOrderRepository studentOrderRepository) {
		this.paymentRepository = paymentRepository;
		this.studentOrderRepository = studentOrderRepository;
	}

	@Override
	public boolean isConfirmedForCurrentTenant(UUID paymentId) {
		return paymentRepository.findById(paymentId).map(payment -> payment.getStatus() == PaymentStatus.CONFIRMED)
			.orElse(false);
	}

	@Override
	public List<UUID> findOrderIdsForStudent(UUID studentId) {
		return studentOrderRepository.findAllByStudentId(studentId).stream().map(StudentOrder::getId).toList();
	}

}
