package com.team01.uber.driver.dto;

import com.team01.uber.driver.model.DriverDocument;
import com.team01.uber.driver.model.DriverStatus;

import java.util.List;

public class DriverDocumentAlertDTO {

    private Long driverId;
    private String driverName;
    private DriverStatus driverStatus;
    private List<DriverDocument> expiredDocuments;
    private int expiredCount;

    public DriverDocumentAlertDTO(Long driverId, String driverName, DriverStatus driverStatus, List<DriverDocument> expiredDocuments) {
        this.driverId = driverId;
        this.driverName = driverName;
        this.driverStatus = driverStatus;
        this.expiredDocuments = expiredDocuments;
        this.expiredCount = expiredDocuments.size();
    }

    public Long getDriverId() { return driverId; }
    public String getDriverName() { return driverName; }
    public DriverStatus getDriverStatus() { return driverStatus; }
    public List<DriverDocument> getExpiredDocuments() { return expiredDocuments; }
    public int getExpiredCount() { return expiredCount; }
}
