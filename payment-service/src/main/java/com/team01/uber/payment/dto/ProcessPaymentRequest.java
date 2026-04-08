package com.team01.uber.payment.dto;

import com.team01.uber.payment.model.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcessPaymentRequest {

    @NotNull(message = "Payment method is required")
    private PaymentMethod method;

    private String cardLastFour;
}
