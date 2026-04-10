package com.team01.uber.payment.dto;

import com.team01.uber.payment.model.PaymentMethod;
import com.team01.uber.payment.model.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;
import java.util.Map;

@Getter
@AllArgsConstructor
public class PaymentDetailsDTO {
    private Long paymentId;
    private Long rideId;
    private Long userId;
    private Double originalAmount;
    private PaymentMethod method;
    private PaymentStatus status;
    private Map<String, Object> transactionDetails;
    private List<AppliedCouponDTO> appliedCoupons;
    private Double totalDiscount;
    private Double finalAmount;
}
