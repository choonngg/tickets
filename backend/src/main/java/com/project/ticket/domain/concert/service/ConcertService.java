package com.project.ticket.domain.concert.service;

import com.project.ticket.domain.concert.dto.ConcertCreateRequest;
import com.project.ticket.domain.concert.dto.ConcertResponse;
import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.entity.UserRole;
import com.project.ticket.domain.user.repository.UserRepository;
import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class ConcertService {
    private static final List<String> DEFAULT_SECTIONS = List.of(
            "A", "B",
            "C", "D", "E", "F",
            "G", "H", "I", "J"
    );
    private static final int DEFAULT_SECTION_ROW_COUNT = 50;
    private static final int DEFAULT_SECTION_COL_COUNT = 30;

    private final ConcertRepository concertRepository;
    private final JdbcTemplate jdbcTemplate;
    private final int seatBatchSize;
    private final UserRepository userRepository;
    private final Clock clock;

    public ConcertService(
            ConcertRepository concertRepository,
            JdbcTemplate jdbcTemplate,
            UserRepository userRepository,
            Clock clock,
            @Value("${ticket.seat-batch-size:1000}") int seatBatchSize
    ) {
        this.concertRepository = concertRepository;
        Assert.isTrue(seatBatchSize > 0, "ticket.seat-batch-size must be positive");
        this.jdbcTemplate = jdbcTemplate;
        this.seatBatchSize = seatBatchSize;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public ConcertResponse createConcert(Long artistId, ConcertCreateRequest request) {
        User artist = userRepository.findById(artistId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (artist.getRole() != UserRole.ARTIST) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        Concert concert = concertRepository.save(Concert.create(
                artist,
                request.title(),
                request.venue(),
                request.concertDate(),
                request.ticketOpenDate(),
                request.ticketCloseDate()
        ));
        jdbcTemplate.batchUpdate("""
                INSERT INTO seat (concert_id, section_name, row_no, seat_no, price, status, version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, createSeats(concert, request), seatBatchSize, (statement, seat) -> {
            statement.setLong(1, concert.getId());
            statement.setString(2, seat.getSection());
            statement.setInt(3, seat.getRow());
            statement.setInt(4, seat.getCol());
            statement.setInt(5, seat.getPrice());
            statement.setString(6, seat.getStatus().name());
            statement.setLong(7, 0L);
        });
        return ConcertResponse.from(concert, clock);
    }

    @Transactional(readOnly = true)
    public List<ConcertResponse> findConcerts() {
        return concertRepository.findAll().stream()
                .map(concert -> ConcertResponse.from(concert, clock))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConcertResponse findConcert(Long concertId) {
        Concert concert = concertRepository.findById(concertId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONCERT_NOT_FOUND));
        return ConcertResponse.from(concert, clock);
    }

    private List<Seat> createSeats(Concert concert, ConcertCreateRequest request) {
        List<Seat> seats = new ArrayList<>();
        for (String section : DEFAULT_SECTIONS) {
            for (int row = 1; row <= DEFAULT_SECTION_ROW_COUNT; row++) {
                for (int col = 1; col <= DEFAULT_SECTION_COL_COUNT; col++) {
                    seats.add(Seat.create(concert, section, row, col, request.price()));
                }
            }
        }
        return seats;
    }
}
