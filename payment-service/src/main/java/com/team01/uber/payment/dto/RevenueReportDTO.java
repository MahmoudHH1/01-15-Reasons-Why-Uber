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
}
