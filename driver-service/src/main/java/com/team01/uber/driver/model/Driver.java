package com.team01.uber.driver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Entity
@Table(name = "drivers")
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required")
    @Column(nullable = false)
    private String name;

    @NotBlank(message = "Email is required")
    @Column(nullable = false, unique = true)
    private String email;

    @NotBlank(message = "Phone is required")
    @Column(nullable = false, unique = true)
    private String phone;

    @NotBlank(message = "License number is required")
    @Column(nullable = false, unique = true)
    private String licenseNumber;

    @NotNull(message = "Status is required")
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private DriverStatus status;

    private Double rating = 0.0;

    private Integer totalRatings = 0;

    @Column(name = "total_completed_rides", nullable = false)
    private Integer totalCompletedRides = 0;

    @Column(name = "total_earnings", nullable = false)
    private Double totalEarnings = 0.0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reversed_ride_ids", columnDefinition = "jsonb")
    private List<Long> reversedRideIds = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> vehicleDetails;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    // Driver is the inverse side; DriverDocument is the owning side (holds the FK)
    @OneToMany(mappedBy = "driver", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<DriverDocument> driverDocuments;
}
