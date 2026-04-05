package com.team01.uber.payment.service;

import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentStatus;
import com.team01.uber.payment.repository.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    public Payment createPayment(Payment payment) {
        payment.setCreatedAt(LocalDateTime.now());
        if (payment.getStatus() == null) {
            payment.setStatus(PaymentStatus.PENDING);
        }
            return paymentRepository.save(payment);
        }
    @Transactional
    public Payment processRefund(Long id, String reason) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only COMPLETED payments can be refunded");
        }

        payment.setStatus(PaymentStatus.REFUNDED);

        if (payment.getTransactionDetails() == null) {
            payment.setTransactionDetails(new HashMap<>());
        }
        payment.getTransactionDetails().put("refundReason", reason);
        payment.getTransactionDetails().put("refundedAt", LocalDateTime.now().toString());

        return paymentRepository.save(payment);
    }

    public Payment getPaymentById(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    public List<Payment> getAllPayments() {
        return paymentRepository.findAll();
    }

    public Payment updatePayment(Long id, Payment payment) {
        Payment existing = getPaymentById(id);
        existing.setRideId(payment.getRideId());
        existing.setUserId(payment.getUserId());
        existing.setAmount(payment.getAmount());
        existing.setMethod(payment.getMethod());
        existing.setStatus(payment.getStatus());
        existing.setTransactionDetails(payment.getTransactionDetails());
        return paymentRepository.save(existing);
    }

    public void deletePayment(Long id) {
        if (!paymentRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
        paymentRepository.deleteById(id);
    }

    public List<Payment> searchPayments(PaymentStatus status, LocalDateTime startDate, LocalDateTime endDate) {
        String statusStr = status != null ? status.name() : null;
        return paymentRepository.findByStatusAndDateRange(statusStr, startDate, endDate);
    }
}
