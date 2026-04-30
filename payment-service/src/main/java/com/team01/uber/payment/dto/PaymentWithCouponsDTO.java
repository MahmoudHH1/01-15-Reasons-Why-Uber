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

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private Long rideId;
        private Long userId;
        private Double amount;
        private PaymentMethod method;
        private PaymentStatus status;
        private Map<String, Object> transactionDetails;
        private LocalDateTime createdAt;
        private List<AppliedCouponDTO> appliedCoupons;

        public Builder id(Long id)                                                { this.id = id; return this; }
        public Builder rideId(Long rideId)                                        { this.rideId = rideId; return this; }
        public Builder userId(Long userId)                                        { this.userId = userId; return this; }
        public Builder amount(Double amount)                                      { this.amount = amount; return this; }
        public Builder method(PaymentMethod method)                               { this.method = method; return this; }
        public Builder status(PaymentStatus status)                               { this.status = status; return this; }
        public Builder transactionDetails(Map<String, Object> transactionDetails) { this.transactionDetails = transactionDetails; return this; }
        public Builder createdAt(LocalDateTime createdAt)                         { this.createdAt = createdAt; return this; }
        public Builder appliedCoupons(List<AppliedCouponDTO> appliedCoupons)      { this.appliedCoupons = appliedCoupons; return this; }

        public PaymentWithCouponsDTO build() {
            // uses no-args constructor + setters — preserves Jackson deserialisability from Redis
            PaymentWithCouponsDTO dto = new PaymentWithCouponsDTO();
            dto.setId(id);
            dto.setRideId(rideId);
            dto.setUserId(userId);
            dto.setAmount(amount);
            dto.setMethod(method);
            dto.setStatus(status);
            dto.setTransactionDetails(transactionDetails);
            dto.setCreatedAt(createdAt);
            dto.setAppliedCoupons(appliedCoupons);
            return dto;
        }
    }
}