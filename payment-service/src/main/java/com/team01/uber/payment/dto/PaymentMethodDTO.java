package com.team01.uber.payment.dto;

public class PaymentMethodDTO {

    private String method;
    private long successCount;
    private long failureCount;
    private double successRate;
    private double totalAmount;

    public PaymentMethodDTO() {}

    public PaymentMethodDTO(String method, long successCount, long failureCount,
                             double successRate, double totalAmount) {
        this.method = method;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.successRate = successRate;
        this.totalAmount = totalAmount;
    }

    public static Builder builder() { return new Builder(); }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public long getSuccessCount() { return successCount; }
    public void setSuccessCount(long successCount) { this.successCount = successCount; }

    public long getFailureCount() { return failureCount; }
    public void setFailureCount(long failureCount) { this.failureCount = failureCount; }

    public double getSuccessRate() { return successRate; }
    public void setSuccessRate(double successRate) { this.successRate = successRate; }

    public double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(double totalAmount) { this.totalAmount = totalAmount; }

    public static class Builder {
        private String method;
        private long successCount;
        private long failureCount;
        private double successRate;
        private double totalAmount;

        public Builder method(String method) { this.method = method; return this; }
        public Builder successCount(long successCount) { this.successCount = successCount; return this; }
        public Builder failureCount(long failureCount) { this.failureCount = failureCount; return this; }
        public Builder successRate(double successRate) { this.successRate = successRate; return this; }
        public Builder totalAmount(double totalAmount) { this.totalAmount = totalAmount; return this; }

        public PaymentMethodDTO build() {
            return new PaymentMethodDTO(method, successCount, failureCount, successRate, totalAmount);
        }
    }
}
