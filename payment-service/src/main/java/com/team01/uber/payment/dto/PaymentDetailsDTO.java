package com.team01.uber.payment.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.team01.uber.payment.model.PaymentMethod;
import com.team01.uber.payment.model.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@AllArgsConstructor
@JsonDeserialize(builder = PaymentDetailsDTO.Builder.class)
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

    public static Builder builder() { return new Builder(); }
    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
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

        public Builder paymentId(Long paymentId)                                  { this.paymentId = paymentId; return this; }
        public Builder rideId(Long rideId)                                        { this.rideId = rideId; return this; }
        public Builder userId(Long userId)                                        { this.userId = userId; return this; }
        public Builder originalAmount(Double originalAmount)                      { this.originalAmount = originalAmount; return this; }
        public Builder method(PaymentMethod method)                               { this.method = method; return this; }
        public Builder status(PaymentStatus status)                               { this.status = status; return this; }
        public Builder transactionDetails(Map<String, Object> transactionDetails) { this.transactionDetails = transactionDetails; return this; }
        public Builder appliedCoupons(List<AppliedCouponDTO> appliedCoupons)      { this.appliedCoupons = appliedCoupons; return this; }
        public Builder totalDiscount(Double totalDiscount)                        { this.totalDiscount = totalDiscount; return this; }
        public Builder finalAmount(Double finalAmount)                            { this.finalAmount = finalAmount; return this; }

        public PaymentDetailsDTO build() {
            // delegates to the @AllArgsConstructor Lombok generates
            return new PaymentDetailsDTO(paymentId, rideId, userId, originalAmount,
                    method, status, transactionDetails, appliedCoupons, totalDiscount, finalAmount);
        }
    }
}