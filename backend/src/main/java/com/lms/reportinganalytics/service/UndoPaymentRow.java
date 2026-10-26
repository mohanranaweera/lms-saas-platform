package com.lms.reportinganalytics.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record UndoPaymentRow(UUID refundId, UUID studentId, String studentName, String indexNumber, UUID courseId,
		String courseName, String undoBy, Instant undoDate, BigDecimal amount, Instant paymentDate, String reason) {
}
