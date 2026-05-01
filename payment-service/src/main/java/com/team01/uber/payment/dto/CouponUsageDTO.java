package com.team01.uber.payment.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.team01.uber.payment.model.DiscountType;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonDeserialize(builder = CouponUsageDTO.Builder.class)
public class CouponUsageDTO {
    private Long couponId;
    private String code;
    private DiscountType discountType;
    private Double discountValue;
    private Integer timesUsed;
    private Double totalDiscountGiven;
    private Boolean active;
    private Boolean expired;

    public static Builder builder() { return new Builder(); }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private Long couponId;
        private String code;
        private DiscountType discountType;
        private Double discountValue;
        private Integer timesUsed;
        private Double totalDiscountGiven;
        private Boolean active;
        private Boolean expired;

        public Builder couponId(Long couponId)                       { this.couponId = couponId; return this; }
        public Builder code(String code)                             { this.code = code; return this; }
        public Builder discountType(DiscountType discountType)       { this.discountType = discountType; return this; }
        public Builder discountValue(Double discountValue)           { this.discountValue = discountValue; return this; }
        public Builder timesUsed(Integer timesUsed)                  { this.timesUsed = timesUsed; return this; }
        public Builder totalDiscountGiven(Double d)                  { this.totalDiscountGiven = d; return this; }
        public Builder active(Boolean active)                        { this.active = active; return this; }
        public Builder expired(Boolean expired)                      { this.expired = expired; return this; }

        public CouponUsageDTO build() {
            return new CouponUsageDTO(couponId, code, discountType, discountValue,
                    timesUsed, totalDiscountGiven, active, expired);
        }
    }
}
