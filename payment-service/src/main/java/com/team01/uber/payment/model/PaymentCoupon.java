package com.team01.uber.payment.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "payment_coupons")
public class PaymentCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull(message = "Discount applied is required")
    @Column(nullable = false)
    private Double discountApplied;

    @NotNull(message = "Applied at is required")
    @Column(nullable = false)
    private LocalDateTime appliedAt;

    // PaymentCoupon is the owning side — holds the FK column (payment_id)
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    // PaymentCoupon is the owning side — holds the FK column (coupon_id)
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;
}
