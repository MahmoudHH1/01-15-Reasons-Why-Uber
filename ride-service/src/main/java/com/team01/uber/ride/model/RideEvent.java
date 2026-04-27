package com.team01.uber.ride.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ride_events")
public class RideEvent implements MongoEvent {

    @Id
    private String id;

    private Long rideId;

    private String action;

    private LocalDateTime timestamp;

    private Map<String, Object> details;
}
