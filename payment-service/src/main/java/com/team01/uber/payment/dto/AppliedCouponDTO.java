package com.team01.uber.payment.dto;

import com.team01.uber.payment.model.DiscountType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class AppliedCouponDTO {
    private String couponCode;
    private DiscountType discountType;
    private Double discountApplied;
    private LocalDateTime appliedAt;
}