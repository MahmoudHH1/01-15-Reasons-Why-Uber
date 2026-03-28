package com.team01.uber.payment.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Entity
@Table(name = "coupons")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Code is required")
    @Column(nullable = false, unique = true)
    private String code;

    @NotNull(message = "Discount type is required")
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private DiscountType discountType;

    @NotNull(message = "Discount value is required")
    @Column(nullable = false)
    private Double discountValue;

    @NotNull(message = "Max uses is required")
    @Column(nullable = false)
    private Integer maxUses;

    private Integer currentUses = 0;

    @NotNull(message = "Expiry date is required")
    @Column(nullable = false)
    private LocalDateTime expiryDate;

    private Boolean active = true;

    // Eligible ride types, minimum fare requirement, terms and conditions, applicable regions
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    // Coupon is the inverse side; PaymentCoupon is the owning side (holds the FK)
    @JsonIgnore
    @OneToMany(mappedBy = "coupon", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PaymentCoupon> paymentCoupons;
}
