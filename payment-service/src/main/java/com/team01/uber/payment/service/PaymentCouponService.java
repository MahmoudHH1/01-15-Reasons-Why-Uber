package com.team01.uber.payment.service;

import com.team01.uber.payment.dto.AppliedCouponDTO;
import com.team01.uber.payment.dto.PaymentWithCouponsDTO;
import com.team01.uber.payment.model.Coupon;
import com.team01.uber.payment.model.DiscountType;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentCoupon;
import com.team01.uber.payment.model.PaymentStatus;
import com.team01.uber.payment.observer.EntityObserver;
import com.team01.uber.payment.repository.CouponRepository;
import com.team01.uber.payment.repository.PaymentCouponRepository;
import com.team01.uber.payment.repository.PaymentRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentCouponService {

    private final PaymentCouponRepository paymentCouponRepository;
    private final PaymentRepository paymentRepository;
    private final CouponRepository couponRepository;
    private final CacheInvalidationService cacheInvalidationService;

    private final List<EntityObserver> observers = new ArrayList<>();

    public PaymentCouponService(PaymentCouponRepository paymentCouponRepository,
                                PaymentRepository paymentRepository,
                                CouponRepository couponRepository,
                                CacheInvalidationService cacheInvalidationService) {
        this.paymentCouponRepository = paymentCouponRepository;
        this.paymentRepository = paymentRepository;
        this.couponRepository = couponRepository;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    public void register(EntityObserver observer) {
        observers.add(observer);
    }

    public void unregister(EntityObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(String eventType, Object payload) {
        for (EntityObserver observer : observers) {
            observer.onEvent(eventType, payload);
        }
    }

    public PaymentCoupon createPaymentCoupon(Long paymentId, Long couponId, PaymentCoupon paymentCoupon) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found"));
        paymentCoupon.setPayment(payment);
        paymentCoupon.setCoupon(coupon);
        return paymentCouponRepository.save(paymentCoupon);
    }

    @Cacheable(value = "payment-service::payment-coupon", key = "#id")
    public PaymentCoupon getPaymentCouponById(Long id) {
        return paymentCouponRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentCoupon not found"));
    }

    public List<PaymentCoupon> getAllPaymentCoupons() {
        return paymentCouponRepository.findAll();
    }

    public PaymentCoupon updatePaymentCoupon(Long paymentId, Long couponId, Long id, PaymentCoupon paymentCoupon) {
        PaymentCoupon existing = getPaymentCouponById(id);
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found"));
        existing.setDiscountApplied(paymentCoupon.getDiscountApplied());
        existing.setAppliedAt(paymentCoupon.getAppliedAt());
        existing.setPayment(payment);
        existing.setCoupon(coupon);
        PaymentCoupon saved = paymentCouponRepository.save(existing);
        cacheInvalidationService.invalidatePaymentCouponCaches(id);
        return saved;
    }

    public void deletePaymentCoupon(Long id) {
        if (!paymentCouponRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentCoupon not found");
        }
        paymentCouponRepository.deleteById(id);
        cacheInvalidationService.invalidatePaymentCouponCaches(id);
    }

    @Transactional
    public PaymentWithCouponsDTO applyCouponToPayment(Long paymentId, Long couponId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

        if (payment.getStatus() == PaymentStatus.COMPLETED || payment.getStatus() == PaymentStatus.REFUNDED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cannot apply coupon to a completed/cancelled payment");
        }

        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found"));

        if (!Boolean.TRUE.equals(coupon.getActive())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coupon is not active");
        }
        if (coupon.getExpiryDate().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coupon has expired");
        }
        int currentUses = coupon.getCurrentUses() != null ? coupon.getCurrentUses() : 0;
        int maxUses = coupon.getMaxUses() != null ? coupon.getMaxUses() : Integer.MAX_VALUE;
        if (currentUses >= maxUses) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coupon usage limit reached");
        }

        if (paymentCouponRepository.existsByPayment_IdAndCoupon_Id(paymentId, couponId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "coupon already applied");
        }

        double discount;
        if (coupon.getDiscountType() == DiscountType.PERCENTAGE) {
            discount = payment.getAmount() * coupon.getDiscountValue() / 100;
        } else {
            discount = coupon.getDiscountValue();
        }
        if (discount > payment.getAmount()) {
            discount = payment.getAmount();
        }

        PaymentCoupon paymentCoupon = new PaymentCoupon();
        paymentCoupon.setPayment(payment);
        paymentCoupon.setCoupon(coupon);
        paymentCoupon.setDiscountApplied(discount);
        paymentCoupon.setAppliedAt(LocalDateTime.now());
        paymentCouponRepository.save(paymentCoupon);

        coupon.setCurrentUses(currentUses + 1);
        couponRepository.save(coupon);

        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentId", paymentId);
        notifyObservers("COUPON_APPLIED", payload);

        cacheInvalidationService.invalidateCouponCaches(couponId);
        cacheInvalidationService.invalidatePattern("payment-service::S5-F8::" + paymentId);

        Payment freshPayment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        return buildPaymentWithCouponsDTO(freshPayment);
    }

    private PaymentWithCouponsDTO buildPaymentWithCouponsDTO(Payment payment) {
        List<AppliedCouponDTO> appliedCoupons = new ArrayList<>();
        if (payment.getPaymentCoupons() != null) {
            for (PaymentCoupon pc : payment.getPaymentCoupons()) {
                appliedCoupons.add(new AppliedCouponDTO(
                    pc.getCoupon().getCode(),
                    pc.getCoupon().getDiscountType(),
                    pc.getDiscountApplied(),
                    pc.getAppliedAt()
                ));
            }
        }

        return PaymentWithCouponsDTO.builder()
                .id(payment.getId())
                .rideId(payment.getRideId())
                .userId(payment.getUserId())
                .amount(payment.getAmount())
                .method(payment.getMethod())
                .status(payment.getStatus())
                .transactionDetails(payment.getTransactionDetails())
                .createdAt(payment.getCreatedAt())
                .appliedCoupons(appliedCoupons)
                .build();
    }
}
