package com.team01.uber.payment.service;

import com.team01.uber.payment.dto.ProcessPaymentRequest;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentStatus;
import com.team01.uber.payment.repository.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    public Payment createPayment(Payment payment) {
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
        existing.setCreatedAt(payment.getCreatedAt());
        return paymentRepository.save(existing);
    }

    public void deletePayment(Long id) {
        getPaymentById(id);
        paymentRepository.deleteById(id);
    }

    @Transactional
    public Payment processPaymentForRide(Long rideId, ProcessPaymentRequest request) {
        String rideStatus = paymentRepository.findRideStatusById(rideId);
        if (rideStatus == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
        }
        if (!"COMPLETED".equals(rideStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride is not COMPLETED");
        }

        if (paymentRepository.existsByRideIdAndStatus(rideId, PaymentStatus.COMPLETED)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "already paid");
        }

        Payment payment = paymentRepository.findByRideIdAndStatus(rideId, PaymentStatus.PENDING)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No pending payment found for this ride"));

        payment.setMethod(request.getMethod());
        payment.setStatus(PaymentStatus.COMPLETED);

        Map<String, Object> details = payment.getTransactionDetails() != null
                ? payment.getTransactionDetails()
                : new HashMap<>();
        details.put("gatewayResponse", "approved");
        if (request.getCardLastFour() != null) {
            details.put("cardLastFour", request.getCardLastFour());
        }
        payment.setTransactionDetails(details);

        return paymentRepository.save(payment);
    }
}
