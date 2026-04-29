package com.team01.uber.payment.service;

import com.team01.uber.payment.dto.AppliedCouponDTO;
import com.team01.uber.payment.dto.PaymentWithCouponsDTO;
import com.team01.uber.payment.model.Coupon;
import com.team01.uber.payment.model.DiscountType;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentCoupon;
import com.team01.uber.payment.model.PaymentStatus;
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
import java.util.List;

@Service
public class PaymentCouponService {

    private final PaymentCouponRepository paymentCouponRepository;
    private final PaymentRepository paymentRepository;
    private final CouponRepository couponRepository;
    private final CacheInvalidationService cacheInvalidationService;

    public PaymentCouponService(PaymentCouponRepository paymentCouponRepository,
                                PaymentRepository paymentRepository,
                                CouponRepository couponRepository,
                                CacheInvalidationService cacheInvalidationService) {
        this.paymentCouponRepository = paymentCouponRepository;
        this.paymentRepository = paymentRepository;
        this.couponRepository = couponRepository;
        this.cacheInvalidationService = cacheInvalidationService;
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

        cacheInvalidationService.invalidateCouponCaches(couponId);
        cacheInvalidationService.invalidatePattern("payment-service::S5-F8::" + paymentId);

        return buildPaymentWithCouponsDTO(payment);
    }

    private PaymentWithCouponsDTO buildPaymentWithCouponsDTO(Payment payment) {
        PaymentWithCouponsDTO dto = new PaymentWithCouponsDTO();
        dto.setId(payment.getId());
        dto.setRideId(payment.getRideId());
        dto.setUserId(payment.getUserId());
        dto.setAmount(payment.getAmount());
        dto.setMethod(payment.getMethod());
        dto.setStatus(payment.getStatus());
        dto.setTransactionDetails(payment.getTransactionDetails());
        dto.setCreatedAt(payment.getCreatedAt());

        List<AppliedCouponDTO> appliedCoupons = new ArrayList<>();
        if (payment.getPaymentCoupons() != null) {
            for (PaymentCoupon pc : payment.getPaymentCoupons()) {
                AppliedCouponDTO couponDTO = new AppliedCouponDTO(
                    pc.getCoupon().getCode(),
                    pc.getCoupon().getDiscountType(),
                    pc.getDiscountApplied(),
                    pc.getAppliedAt()
                );
                appliedCoupons.add(couponDTO);
            }
        }
        dto.setAppliedCoupons(appliedCoupons);
        return dto;
    }
}
