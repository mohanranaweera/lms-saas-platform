package com.lms.integrationmanagement.web.dto;

import com.lms.integrationmanagement.api.OnlinePaymentOption;

/** {@code onlinePayment} is {@code null} when the tenant offers bank-slip only. */
public record CheckoutPaymentOptionsResponse(boolean bankSlip, OnlinePaymentOption onlinePayment) {

}
