package com.team01.uber.driver.service;

import com.team01.uber.contracts.dto.UserDTO;
import com.team01.uber.driver.client.UserClient;
import com.team01.uber.driver.cache.CacheInvalidator;
import com.team01.uber.driver.dto.DriverDocumentAlertDTO;
import com.team01.uber.driver.messaging.DriverEventPublisher;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverDocument;
import com.team01.uber.driver.observer.EntityObserver;
import com.team01.uber.driver.observer.MongoEventLogger;
import com.team01.uber.driver.repository.DriverDocumentRepository;
import feign.FeignException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DriverDocumentService {

    private static final Logger log = LoggerFactory.getLogger(DriverDocumentService.class);

    private final DriverDocumentRepository driverDocumentRepository;
    private final DriverService driverService;
    private final CacheInvalidator cacheInvalidator;
    private final MongoEventLogger mongoEventLogger;
    private final UserClient userClient;
    private final DriverEventPublisher driverEventPublisher;
    private final List<EntityObserver> observers = new ArrayList<>();

    public DriverDocumentService(DriverDocumentRepository driverDocumentRepository,
                                 DriverService driverService,
                                 CacheInvalidator cacheInvalidator,
                                 MongoEventLogger mongoEventLogger,
                                 UserClient userClient,
                                 DriverEventPublisher driverEventPublisher) {
        this.driverDocumentRepository = driverDocumentRepository;
        this.driverService = driverService;
        this.cacheInvalidator = cacheInvalidator;
        this.mongoEventLogger = mongoEventLogger;
        this.userClient = userClient;
        this.driverEventPublisher = driverEventPublisher;
    }

    @PostConstruct
    void init() {
        register(mongoEventLogger);
    }

    public void register(EntityObserver observer) {
        observers.add(observer);
    }

    public void unregister(EntityObserver observer) {
        observers.remove(observer);
    }

    public void notifyObservers(String eventType, Object payload) {
        for (EntityObserver observer : observers) {
            observer.onEvent(eventType, payload);
        }
    }

    private void invalidateDocumentFeatureCaches() {
        cacheInvalidator.deleteByPattern("driver-service::S2-F9::*");
    }

    public DriverDocument createDocument(Long driverId, DriverDocument document) {
        Driver driver = driverService.getDriverById(driverId);
        document.setId(null); // Ensure ID is null for new document
        document.setDriver(driver);
        document.setUploadedAt(LocalDateTime.now());
        document.setVerified(false);
        DriverDocument saved = driverDocumentRepository.save(document);
        cacheInvalidator.deleteEntity("driver", driverId) ;
        invalidateDocumentFeatureCaches();
        return saved;
    }

    public List<DriverDocument> getDocumentsByDriverId(Long driverId) {
        driverService.getDriverById(driverId);
        return driverDocumentRepository.findByDriverId(driverId);
    }

    @Cacheable(value = "driver-service::driver-document", key = "#docId")
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
        DriverDocument saved = driverDocumentRepository.save(existing);
        cacheInvalidator.deleteKey("driver-service::driver-document::" + docId);
        cacheInvalidator.deleteEntity("driver", driverId) ;
        invalidateDocumentFeatureCaches();
        return saved;
    }

    public void deleteDocument(Long driverId, Long docId) {
        if (!driverDocumentRepository.existsByIdAndDriverId(docId, driverId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
        driverDocumentRepository.deleteById(docId);
        cacheInvalidator.deleteKey("driver-service::driver-document::" + docId);
        cacheInvalidator.deleteEntity("driver", driverId);
        invalidateDocumentFeatureCaches();
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

        UserDTO verifier = userClient.getUser(verifiedBy);
        if (!"ADMIN".equals(verifier.role())) {
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

        notifyObservers("DOCUMENT_VERIFIED", Map.of(
                "driverId", driverId,
                "details", Map.of(
                        "verifiedBy", verifiedBy,
                        "documentId", documentId
                )
        ));

        cacheInvalidator.deleteKey("driver-service::driver-document::" + documentId);
        cacheInvalidator.deleteEntity("driver", driverId);
        invalidateDocumentFeatureCaches();

        driverEventPublisher.publishDocumentVerified(driverId, documentId, verifiedBy);

        // initialize the lazy collection within the transaction before returning
        driver.getDriverDocuments().size();
        return driver;
    }

    @Cacheable(value = "driver-service::S2-F9")
    @Transactional(readOnly = true)
    public List<DriverDocumentAlertDTO> getDriversWithExpiredDocuments() {
        List<DriverDocument> expired = driverDocumentRepository.findByExpiryDateBefore(LocalDate.now());

        Map<Driver, List<DriverDocument>> byDriver = expired.stream()
                .collect(Collectors.groupingBy(DriverDocument::getDriver));

        return byDriver.entrySet().stream()
                .map(e -> {
                    List<DriverDocument> docs = e.getValue();
                    return DriverDocumentAlertDTO.builder()
                            .driverId(e.getKey().getId())
                            .driverName(e.getKey().getName())
                            .driverStatus(e.getKey().getStatus())
                            .expiredDocuments(docs)
                            .expiredCount(docs.size())
                            .build();
                })
                .collect(Collectors.toList());
    }
}
