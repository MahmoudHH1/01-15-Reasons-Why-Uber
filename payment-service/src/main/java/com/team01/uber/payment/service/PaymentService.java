package com.team01.uber.payment.service;

import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentStatus;
import com.team01.uber.payment.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    @Autowired
    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    public List<Payment> searchPayments(PaymentStatus status, LocalDateTime startDate, LocalDateTime endDate) {
        return paymentRepository.findByStatusAndDateRange(status, startDate, endDate);
    }
}
