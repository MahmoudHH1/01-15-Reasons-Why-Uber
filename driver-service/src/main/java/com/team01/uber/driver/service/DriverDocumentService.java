package com.team01.uber.driver.service;

import com.team01.uber.driver.dto.DriverDocumentAlertDTO;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverDocument;
import com.team01.uber.driver.repository.DriverDocumentRepository;

import org.springframework.transaction.annotation.Transactional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DriverDocumentService {

    private final DriverDocumentRepository driverDocumentRepository;
    private final DriverService driverService;

    public DriverDocumentService(DriverDocumentRepository driverDocumentRepository, DriverService driverService) {
        this.driverDocumentRepository = driverDocumentRepository;
        this.driverService = driverService;
    }

    public DriverDocument createDocument(Long driverId, DriverDocument document) {
        Driver driver = driverService.getDriverById(driverId);
        document.setId(null); // Ensure ID is null for new document
        document.setDriver(driver);
        document.setUploadedAt(LocalDateTime.now());
        document.setVerified(false);
        return driverDocumentRepository.save(document);
    }

    public List<DriverDocument> getDocumentsByDriverId(Long driverId) {
        driverService.getDriverById(driverId);
        return driverDocumentRepository.findByDriverId(driverId);
    }

    public DriverDocument getDocumentById(Long driverId, Long docId) {
        return driverDocumentRepository.findByIdAndDriverId(docId, driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    public DriverDocument updateDocument(Long driverId, Long docId, DriverDocument updated) {
        DriverDocument existing = driverDocumentRepository.findByIdAndDriverId(docId, driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        existing.setType(updated.getType());
        existing.setDocumentUrl(updated.getDocumentUrl());
        existing.setExpiryDate(updated.getExpiryDate());
        existing.setMetadata(updated.getMetadata());
        return driverDocumentRepository.save(existing);
    }

    public void deleteDocument(Long driverId, Long docId) {
        if (!driverDocumentRepository.existsByIdAndDriverId(docId, driverId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
        driverDocumentRepository.deleteById(docId);
    }


    @Transactional(readOnly = true)
    public List<DriverDocumentAlertDTO> getDriversWithExpiredDocuments() {
        List<DriverDocument> expired = driverDocumentRepository.findByExpiryDateBefore(LocalDate.now());

        Map<Driver, List<DriverDocument>> byDriver = expired.stream()
                .collect(Collectors.groupingBy(DriverDocument::getDriver));

        return byDriver.entrySet().stream()
                .map(e -> new DriverDocumentAlertDTO(
                        e.getKey().getId(),
                        e.getKey().getName(),
                        e.getKey().getStatus(),
                        e.getValue()))
                .collect(Collectors.toList());
    }
}
