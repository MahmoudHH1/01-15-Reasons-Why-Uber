package com.team01.uber.payment.controller;

import com.team01.uber.payment.dto.UserPaymentSummaryDTO;
import com.team01.uber.payment.service.PaymentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @GetMapping("/user/{userId}/summary")
    public UserPaymentSummaryDTO getUserPaymentSummary(@PathVariable Long userId) {
        return paymentService.getUserPaymentSummary(userId);
    }
}
