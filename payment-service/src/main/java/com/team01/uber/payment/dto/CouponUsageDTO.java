package com.team01.uber.payment.dto;

import com.team01.uber.payment.model.DiscountType;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CouponUsageDTO {
    private Long couponId;
    private String code;
    private DiscountType discountType;
    private Double discountValue;
    private Integer timesUsed;
    private Double totalDiscountGiven;
    private Boolean active;
    private Boolean expired;
}
