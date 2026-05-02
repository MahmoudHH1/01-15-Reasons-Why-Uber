package com.team01.uber.driver.dto;

import com.team01.uber.driver.model.DriverDocument;
import com.team01.uber.driver.model.DriverStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverDocumentAlertDTO {

    private Long driverId;
    private String driverName;
    private DriverStatus driverStatus;
    private List<DriverDocument> expiredDocuments;
    private int expiredCount;
}
