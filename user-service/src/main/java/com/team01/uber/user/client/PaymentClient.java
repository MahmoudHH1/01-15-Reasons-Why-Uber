package com.team01.uber.user.client;

import com.team01.uber.contracts.feign.PaymentServiceClient;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

@Component
@Slf4j
public class PaymentClient {

    private final PaymentServiceClient feignClient;

    public PaymentClient(PaymentServiceClient feignClient) {
        this.feignClient = feignClient;
    }

    public BigDecimal getUserTotalPayments(Long userId, String startDate, String endDate) {
        try {
            log.info("Calling payment-service.getUserTotalPayments for userId={}", userId);
            return feignClient.getUserTotalPayments(userId, startDate, endDate);
        } catch (FeignException.NotFound e) {
            return BigDecimal.ZERO;
        } catch (FeignException e) {
            log.warn("payment-service unavailable for getUserTotalPayments userId={}: {}", userId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Payment service unavailable");
        }
    }
}
