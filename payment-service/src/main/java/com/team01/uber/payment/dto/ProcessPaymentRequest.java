package com.team01.uber.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcessPaymentRequest {

    @NotBlank(message = "Payment method is required")
    private String method;

    @Pattern(regexp = "^\\d{4}$", message = "cardLastFour must be exactly 4 digits")
    private String cardLastFour;
}
