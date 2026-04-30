package com.team01.uber.payment.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RevenueReportDTO {

    private Double totalRevenue;
    private Long totalTransactions;
    private Double averagePayment;
    private Double refundedAmount;
    private Long refundCount;

    public static Builder builder() { return new Builder(); }

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
            // uses no-args constructor + setters — preserves Jackson deserialisability from Redis
            RevenueReportDTO dto = new RevenueReportDTO();
            dto.setTotalRevenue(totalRevenue);
            dto.setTotalTransactions(totalTransactions);
            dto.setAveragePayment(averagePayment);
            dto.setRefundedAmount(refundedAmount);
            dto.setRefundCount(refundCount);
            return dto;
        }
    }
}