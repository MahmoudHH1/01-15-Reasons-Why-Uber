package com.team01.uber.driver.controller;

import com.team01.uber.driver.dto.VerifyDocumentRequest;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverDocument;
import com.team01.uber.driver.security.JwtService;
import com.team01.uber.driver.service.DriverDocumentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/drivers/{driverId}/documents")
public class DriverDocumentController {

    private final DriverDocumentService driverDocumentService;
    private final JwtService jwtService;

    public DriverDocumentController(DriverDocumentService driverDocumentService, JwtService jwtService) {
        this.driverDocumentService = driverDocumentService;
        this.jwtService = jwtService;
    }

    @PostMapping
    public ResponseEntity<DriverDocument> createDocument(@PathVariable Long driverId, @Valid @RequestBody DriverDocument document) {
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
    public DriverDocument updateDocument(@PathVariable Long driverId, @PathVariable Long docId, @Valid @RequestBody DriverDocument document) {
        return driverDocumentService.updateDocument(driverId, docId, document);
    }

    @DeleteMapping("/{docId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable Long driverId, @PathVariable Long docId) {
        driverDocumentService.deleteDocument(driverId, docId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{docId}/verify")
    public Driver verifyDocument(@PathVariable Long driverId, @PathVariable Long docId,
                                 @RequestBody(required = false) VerifyDocumentRequest request,
                                 HttpServletRequest httpRequest) {
        Long verifiedBy = (request != null && request.getVerifiedBy() != null)
                ? request.getVerifiedBy()
                : extractUidFromJwt(httpRequest);
        return driverDocumentService.verifyDocument(driverId, docId, verifiedBy);
    }

    private Long extractUidFromJwt(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "verifiedBy required");
        }
        String token = header.substring(7);
        Long uid = jwtService.extractUserId(token);
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "verifiedBy could not be resolved from token");
        }
        return uid;
    }
}
