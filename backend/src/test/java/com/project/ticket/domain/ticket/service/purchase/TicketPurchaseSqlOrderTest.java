package com.project.ticket.domain.ticket.service.purchase;

import com.project.ticket.support.TestUsers;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ticket.domain.concert.entity.Concert;
import com.project.ticket.domain.concert.repository.ConcertRepository;
import com.project.ticket.domain.seat.entity.Seat;
import com.project.ticket.domain.seat.repository.SeatRepository;
import com.project.ticket.domain.ticket.repository.TicketRepository;
import com.project.ticket.domain.user.entity.User;
import com.project.ticket.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.project.ticket.domain.ticket.service.purchase.TicketPurchaseSqlOrderTest$SqlRecorder"
})
class TicketPurchaseSqlOrderTest {
    @Autowired Clock clock;
    @Autowired OptimisticTicketPurchaseStrategy optimistic;
    @Autowired UserRepository users;
    @Autowired ConcertRepository concerts;
    @Autowired SeatRepository seats;
    @Autowired TicketRepository tickets;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void optimisticSeatUpdateExecutesBeforeTicketInsert() {
        User artist = users.save(TestUsers.artist("SQL Artist"));
        var now = clock.instant();
        Concert concert = concerts.save(Concert.create(artist, "SQL Concert", "Venue",
                now.plus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS)));
        Seat seat = seats.save(Seat.create(concert, "A", 1, 1, 10_000));
        User fan = users.save(TestUsers.fan("SQL Fan"));
        SqlRecorder recorder = (SqlRecorder) entityManagerFactory.unwrap(SessionFactory.class)
                .getSessionFactoryOptions().getStatementInspector();
        recorder.statements.clear();

        optimistic.purchase(fan.getId(), seat.getId());

        assertThat(recorder.statements).hasSize(2);
        assertThat(recorder.statements.get(0)).startsWith("update seat ").contains("version=?");
        assertThat(recorder.statements.get(1)).startsWith("insert into ticket ");
        assertThat(tickets.countBySeatId(seat.getId())).isEqualTo(1);
    }

    public static class SqlRecorder implements StatementInspector {
        final List<String> statements = new ArrayList<>();

        @Override
        public String inspect(String sql) {
            if (sql.startsWith("update seat ") || sql.startsWith("insert into ticket ")) {
                statements.add(sql);
            }
            return sql;
        }
    }
}
