package com.team01.uber.driver.dto;

import jakarta.validation.constraints.NotNull;

public class VerifyDocumentRequest {

    @NotNull(message = "verifiedBy is required")
    private Long verifiedBy;

    public Long getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(Long verifiedBy) {
        this.verifiedBy = verifiedBy;
    }
}
