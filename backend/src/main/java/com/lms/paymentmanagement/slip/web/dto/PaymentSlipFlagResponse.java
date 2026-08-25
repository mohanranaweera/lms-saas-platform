package com.lms.paymentmanagement.slip.web.dto;

import com.lms.paymentmanagement.slip.domain.FlagType;
import java.time.Instant;
import java.util.UUID;

public record PaymentSlipFlagResponse(UUID id, FlagType flagType, Instant detectedAt) {

}
