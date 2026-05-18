package com.team01.uber.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/*
 * Local-DB ledger of completed rides per the saga's compensation contract.
 *
 * RideCancelledEvent has no fare field, so the cancellation consumer needs a
 * local lookup to know how much to subtract from User.totalSpent. A row is
 * inserted by the ride.completed consumer (after bumping User stats) and
 * deleted by the ride.cancelled consumer (after reversing them). Doubles
 * as a state-based idempotency guard for at-least-once delivery: both
 * consumers no-op if the row's presence/absence already reflects the
 * desired post-state.
 */
@Entity
@Table(name = "user_ride_completions")
@Getter
@Setter
public class UserRideCompletion {

    @Id
    private Long rideId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Double fare;

    @Column(nullable = false)
    private LocalDateTime completedAt;
}
