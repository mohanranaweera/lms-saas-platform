package com.lms.paymentmanagement.slip.service;

import com.lms.paymentmanagement.slip.domain.FlagType;
import java.time.Instant;
import java.util.UUID;

public record PaymentSlipFlagView(UUID id, FlagType flagType, Instant detectedAt) {

}
