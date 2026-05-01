package com.team01.uber.user.model.mongo;

import com.team01.uber.user.mongo.MongoEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "auth_events")
public class AuthEvent implements MongoEvent {

    @Id
    private String id;

    private Long userId;
    private String action;
    private LocalDateTime timestamp;
    private Map<String, Object> details;

    public AuthEvent(Long userId, String action, LocalDateTime timestamp, Map<String, Object> details) {
        this.userId = userId;
        this.action = action;
        this.timestamp = timestamp;
        this.details = details;
    }

    // Action constants
    public static final String ACTION_REGISTERED          = "REGISTERED";
    public static final String ACTION_LOGGED_IN           = "LOGGED_IN";
    public static final String ACTION_ROLE_CHANGED        = "ROLE_CHANGED";
    public static final String ACTION_USER_UPDATED        = "USER_UPDATED";
    public static final String ACTION_USER_DEACTIVATED    = "USER_DEACTIVATED";
    public static final String ACTION_DEFAULT_ADDRESS_SET = "DEFAULT_ADDRESS_SET";
    public static final String ACTION_USER_CREATED        = "USER_CREATED";
    public static final String ACTION_USER_DELETED        = "USER_DELETED";
}