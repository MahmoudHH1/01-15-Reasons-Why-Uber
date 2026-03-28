package com.team01.uber.payment.service;

import com.team01.uber.payment.dto.UserPaymentSummaryDTO;
import com.team01.uber.payment.repository.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    public UserPaymentSummaryDTO getUserPaymentSummary(Long userId) {
        if (paymentRepository.countUsersById(userId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        List<Object[]> rows = paymentRepository.findCompletedPaymentsSummaryByUser(userId);

        Map<String, Double> methodBreakdown = new HashMap<>();
        long totalPayments = 0;
        double totalAmount = 0.0;

        for (Object[] row : rows) {
            String method = (String) row[0];
            long count = ((Number) row[1]).longValue();
            double amount = ((Number) row[2]).doubleValue();

            methodBreakdown.put(method, amount);
            totalPayments += count;
            totalAmount += amount;
        }

        return new UserPaymentSummaryDTO(userId, totalPayments, totalAmount, methodBreakdown);
    }
}
