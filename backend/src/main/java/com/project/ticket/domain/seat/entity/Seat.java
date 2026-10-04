package com.project.ticket.domain.seat.entity;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(
        name = "seat",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_seat_position",
                        columnNames = {"concert_id", "section_name", "row_no", "seat_no"}
                )
        }
)
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "concert_id", nullable = false)
    private Concert concert;

    @Column(name = "section_name", nullable = false, length = 50)
    private String section;

    @Column(name = "row_no", nullable = false)
    private int row;

    @Column(name = "seat_no", nullable = false)
    private int col;

    @Column(nullable = false)
    private int price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, insertable = false, updatable = false,
            columnDefinition = "DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)")
    private Instant createdAt;

    @Column(nullable = false, insertable = false, updatable = false,
            columnDefinition = "DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)")
    private Instant updatedAt;

    protected Seat() {
    }

    public static Seat create(Concert concert, String section, int row, int col, int price) {
        Seat seat = new Seat();
        seat.concert = concert;
        seat.section = section;
        seat.row = row;
        seat.col = col;
        seat.price = price;
        seat.status = SeatStatus.AVAILABLE;
        return seat;
    }

    public void completeSale() {
        if (status != SeatStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
        }
        status = SeatStatus.SOLD;
    }
}
