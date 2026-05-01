package com.team01.uber.payment.service;

import com.team01.uber.payment.dto.CouponUsageDTO;
import com.team01.uber.payment.model.Coupon;
import com.team01.uber.payment.service.CacheInvalidationService;
import com.team01.uber.payment.model.DiscountType;
import com.team01.uber.payment.repository.CouponRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CouponService {

    private final CouponRepository couponRepository;
    private final CacheInvalidationService cacheInvalidationService;

    public CouponService(CouponRepository couponRepository,
                         CacheInvalidationService cacheInvalidationService) {
        this.couponRepository = couponRepository;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    public Coupon createCoupon(Coupon coupon) {
        return couponRepository.save(coupon);
    }

    @Cacheable(value = "payment-service::coupon", key = "#id")
    public Coupon getCouponById(Long id) {
        return couponRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found"));
    }

    public List<Coupon> getAllCoupons() {
        return couponRepository.findAll();
    }

    public Coupon updateCoupon(Long id, Coupon coupon) {
        Coupon existing = getCouponById(id);
        existing.setCode(coupon.getCode());
        existing.setDiscountType(coupon.getDiscountType());
        existing.setDiscountValue(coupon.getDiscountValue());
        existing.setMaxUses(coupon.getMaxUses());
        existing.setCurrentUses(coupon.getCurrentUses());
        existing.setExpiryDate(coupon.getExpiryDate());
        existing.setActive(coupon.getActive());
        existing.setMetadata(coupon.getMetadata());
        Coupon saved = couponRepository.save(existing);
        cacheInvalidationService.invalidateCouponCaches(id);
        return saved;
    }

    public void deleteCoupon(Long id) {
        if (!couponRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found");
        }
        couponRepository.deleteById(id);
        cacheInvalidationService.invalidateCouponCaches(id);
    }

    @Cacheable(value = "payment-service::S5-F3", key = "#limit")
    public List<CouponUsageDTO> getMostUsedCoupons(int limit) {
        return couponRepository.findTopUsedCoupons(limit).stream()
                .map(row -> new CouponUsageDTO(
                        ((Number) row[0]).longValue(),
                        (String) row[1],
                        DiscountType.valueOf((String) row[2]),
                        ((Number) row[3]).doubleValue(),
                        ((Number) row[4]).intValue(),
                        ((Number) row[5]).doubleValue(),
                        (Boolean) row[6],
                        ((LocalDateTime) row[7]).isBefore(LocalDateTime.now())
                ))
                .collect(Collectors.toList());
    }
}
