package com.team01.uber.payment.dto;

import com.team01.uber.payment.model.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcessPaymentRequest {

    @NotNull(message = "Payment method is required")
    private PaymentMethod method;

    @Pattern(regexp = "^\\d{4}$", message = "cardLastFour must be exactly 4 digits")
    private String cardLastFour;
}
