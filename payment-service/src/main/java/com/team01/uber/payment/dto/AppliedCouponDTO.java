package com.team01.uber.payment.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class AppliedCouponDTO {

    private Long couponId;
    private String couponCode;
    private String discountType;
    private Double discountApplied;
    private LocalDateTime appliedAt;
}
