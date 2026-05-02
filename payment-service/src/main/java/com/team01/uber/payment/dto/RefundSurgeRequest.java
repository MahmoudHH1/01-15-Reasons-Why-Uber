package com.team01.uber.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RefundSurgeRequest {

    @NotBlank(message = "Reason is required")
    private String reason;

    private boolean refundSurge;
}
