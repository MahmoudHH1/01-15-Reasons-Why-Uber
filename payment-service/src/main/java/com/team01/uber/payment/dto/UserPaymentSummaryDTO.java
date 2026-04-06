package com.team01.uber.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@AllArgsConstructor
public class UserPaymentSummaryDTO {

    private Long userId;
    private long totalPayments;
    private double totalAmount;
    private Map<String, Double> methodBreakdown;
}
