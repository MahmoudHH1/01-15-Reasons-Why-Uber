package com.team01.uber.payment.dto;

import com.team01.uber.payment.model.PaymentMethod;
import com.team01.uber.payment.model.PaymentStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class PaymentWithCouponsDTO {

    private Long id;
    private Long rideId;
    private Long userId;
    private Double amount;
    private PaymentMethod method;
    private PaymentStatus status;
    private Map<String, Object> transactionDetails;
    private LocalDateTime createdAt;
    private List<AppliedCouponDTO> appliedCoupons;
}
