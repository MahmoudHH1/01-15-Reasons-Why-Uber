package com.team01.uber.driver.controller;

import com.team01.uber.driver.model.DriverDocument;
import com.team01.uber.driver.service.DriverDocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/drivers/{driverId}/documents")
public class DriverDocumentController {

    private final DriverDocumentService driverDocumentService;

    public DriverDocumentController(DriverDocumentService driverDocumentService) {
        this.driverDocumentService = driverDocumentService;
    }

    @PostMapping
    public ResponseEntity<DriverDocument> createDocument(@PathVariable Long driverId, @RequestBody DriverDocument document) {
        return ResponseEntity.status(HttpStatus.CREATED).body(driverDocumentService.createDocument(driverId, document));
    }

    @GetMapping
    public List<DriverDocument> getDocumentsByDriverId(@PathVariable Long driverId) {
        return driverDocumentService.getDocumentsByDriverId(driverId);
    }

    @GetMapping("/{docId}")
    public DriverDocument getDocumentById(@PathVariable Long driverId, @PathVariable Long docId) {
        return driverDocumentService.getDocumentById(driverId, docId);
    }

    @PutMapping("/{docId}")
    public DriverDocument updateDocument(@PathVariable Long driverId, @PathVariable Long docId, @RequestBody DriverDocument document) {
        return driverDocumentService.updateDocument(driverId, docId, document);
    }

    @DeleteMapping("/{docId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable Long driverId, @PathVariable Long docId) {
        driverDocumentService.deleteDocument(driverId, docId);
        return ResponseEntity.noContent().build();
    }
}
