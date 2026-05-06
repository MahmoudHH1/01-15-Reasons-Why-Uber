package com.team01.uber.driver.dto;

import com.team01.uber.driver.model.DriverDocument;
import com.team01.uber.driver.model.DriverStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DriverDocumentAlertDTO {

    private Long driverId;
    private String driverName;
    private DriverStatus driverStatus;
    private List<DriverDocument> expiredDocuments;
    private int expiredCount;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long driverId;
        private String driverName;
        private DriverStatus driverStatus;
        private List<DriverDocument> expiredDocuments;
        private int expiredCount;

        public Builder driverId(Long driverId) { this.driverId = driverId; return this; }
        public Builder driverName(String driverName) { this.driverName = driverName; return this; }
        public Builder driverStatus(DriverStatus driverStatus) { this.driverStatus = driverStatus; return this; }
        public Builder expiredDocuments(List<DriverDocument> expiredDocuments) { this.expiredDocuments = expiredDocuments; return this; }
        public Builder expiredCount(int expiredCount) { this.expiredCount = expiredCount; return this; }

        public DriverDocumentAlertDTO build() {
            return new DriverDocumentAlertDTO(driverId, driverName, driverStatus, expiredDocuments, expiredCount);
        }
    }
}
