package com.team01.uber.payment.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@AllArgsConstructor
@JsonDeserialize(builder = UserPaymentSummaryDTO.Builder.class)
public class UserPaymentSummaryDTO {

    private Long userId;
    private long totalPayments;
    private double totalAmount;
    private Map<String, Double> methodBreakdown;

    public static Builder builder() { return new Builder(); }
    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private Long userId;
        private long totalPayments;
        private double totalAmount;
        private Map<String, Double> methodBreakdown;

        public Builder userId(Long userId)                               { this.userId = userId; return this; }
        public Builder totalPayments(long totalPayments)                 { this.totalPayments = totalPayments; return this; }
        public Builder totalAmount(double totalAmount)                   { this.totalAmount = totalAmount; return this; }
        public Builder methodBreakdown(Map<String, Double> methodBreakdown) { this.methodBreakdown = methodBreakdown; return this; }

        public UserPaymentSummaryDTO build() {
            // delegates to the @AllArgsConstructor Lombok generates
            return new UserPaymentSummaryDTO(userId, totalPayments, totalAmount, methodBreakdown);
        }
    }
}