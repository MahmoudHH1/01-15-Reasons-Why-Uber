package com.team01.uber.user.controller;

import com.team01.uber.user.model.SavedAddress;
import com.team01.uber.user.service.SavedAddressService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users/{userId}/addresses")
public class SavedAddressController {

    private final SavedAddressService savedAddressService;

    public SavedAddressController(SavedAddressService savedAddressService) {
        this.savedAddressService = savedAddressService;
    }

    @PostMapping
    public ResponseEntity<SavedAddress> createAddress(@PathVariable Long userId, @RequestBody SavedAddress address) {
        return ResponseEntity.status(HttpStatus.CREATED).body(savedAddressService.createAddress(userId, address));
    }

    @GetMapping
    public List<SavedAddress> getAllAddresses(@PathVariable Long userId) {
        return savedAddressService.getAllAddresses(userId);
    }

    @GetMapping("/{id}")
    public SavedAddress getAddressById(@PathVariable Long userId, @PathVariable Long id) {
        return savedAddressService.getAddressById(userId, id);
    }

    @PutMapping("/{id}")
    public SavedAddress updateAddress(@PathVariable Long userId, @PathVariable Long id, @RequestBody SavedAddress address) {
        return savedAddressService.updateAddress(userId, id, address);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAddress(@PathVariable Long userId, @PathVariable Long id) {
        savedAddressService.deleteAddress(userId, id);
        return ResponseEntity.noContent().build();
    }
}