package com.team01.uber.payment.controller;

import com.team01.uber.payment.dto.RefundRequest;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.service.PaymentService;
import jakarta.validation.Valid;
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

    @PutMapping("/{id}/refund")
    public Payment refund(@PathVariable Long id, @Valid @RequestBody RefundRequest request) {
        return paymentService.processRefund(id, request.getReason());
    }
}
