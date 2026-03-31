package com.team01.uber.driver.service;

import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverDocument;
import com.team01.uber.driver.repository.DriverDocumentRepository;
import com.team01.uber.driver.repository.DriverRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DriverDocumentService {

    private final DriverDocumentRepository driverDocumentRepository;
    private final DriverRepository driverRepository;
    private final DriverService driverService;
    private final RestTemplate restTemplate;

    @Value("${user.service.url}")
    private String userServiceUrl;

    public DriverDocumentService(DriverDocumentRepository driverDocumentRepository,
                                 DriverRepository driverRepository,
                                 DriverService driverService,
                                 RestTemplate restTemplate) {
        this.driverDocumentRepository = driverDocumentRepository;
        this.driverRepository = driverRepository;
        this.driverService = driverService;
        this.restTemplate = restTemplate;
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

    @Transactional
    public Driver verifyDocument(Long driverId, Long documentId, Long verifiedBy) {
        Driver driver = driverService.getDriverById(driverId);

        DriverDocument document = driverDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        if (!document.getDriver().getId().equals(driverId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document does not belong to this driver");
        }

        if (!document.getExpiryDate().isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document is expired");
        }

        try {
            Map<?, ?> user = restTemplate.getForObject(userServiceUrl + "/api/users/" + verifiedBy, Map.class);
            if (user == null || !"ADMIN".equals(user.get("role"))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "verifiedBy user is not an admin");
            }
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "verifiedBy user is not an admin");
        }

        document.setVerified(true);

        Map<String, Object> metadata = document.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put("verifiedAt", LocalDateTime.now().toString());
        metadata.put("verifiedBy", verifiedBy);
        document.setMetadata(metadata);

        driverDocumentRepository.save(document);

        // initialize the lazy collection within the transaction before returning
        driver.getDriverDocuments().size();
        return driver;
    }
}
