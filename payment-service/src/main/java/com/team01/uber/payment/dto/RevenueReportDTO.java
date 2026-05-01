package com.team01.uber.payment.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor

@JsonDeserialize(builder = RevenueReportDTO.Builder.class)
public class RevenueReportDTO {

    private Double totalRevenue;
    private Long totalTransactions;
    private Double averagePayment;
    private Double refundedAmount;
    private Long refundCount;

    public static Builder builder() { return new Builder(); }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private Double totalRevenue;
        private Long totalTransactions;
        private Double averagePayment;
        private Double refundedAmount;
        private Long refundCount;

        public Builder totalRevenue(Double totalRevenue)         { this.totalRevenue = totalRevenue; return this; }
        public Builder totalTransactions(Long totalTransactions) { this.totalTransactions = totalTransactions; return this; }
        public Builder averagePayment(Double averagePayment)     { this.averagePayment = averagePayment; return this; }
        public Builder refundedAmount(Double refundedAmount)     { this.refundedAmount = refundedAmount; return this; }
        public Builder refundCount(Long refundCount)             { this.refundCount = refundCount; return this; }

        public RevenueReportDTO build() {
            return new RevenueReportDTO(totalRevenue, totalTransactions, averagePayment, refundedAmount, refundCount);
        }
    }
}