package com.project.ticket.domain.seat.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ticket.global.exception.BusinessException;
import com.project.ticket.global.exception.ErrorCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SeatMappingTest {

    @Test
    void completesSaleDirectlyFromAvailableAndRejectsResale() {
        Seat seat = Seat.create(null, "A", 1, 1, 10_000);
        seat.completeSale();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.SOLD);
        assertThatThrownBy(seat::completeSale).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.SEAT_NOT_AVAILABLE));
    }

    @Test
    void usesMysqlSafeSeatLocationColumnNames() throws NoSuchFieldException {
        Table table = Seat.class.getAnnotation(Table.class);
        Column rowColumn = Seat.class.getDeclaredField("row").getAnnotation(Column.class);
        Column colColumn = Seat.class.getDeclaredField("col").getAnnotation(Column.class);

        assertThat(Seat.class.getDeclaredField("section").getAnnotation(Column.class).name())
                .isEqualTo("section_name");
        assertThat(table.uniqueConstraints()[0].name()).isEqualTo("uq_seat_position");
        assertThat(rowColumn.name()).isEqualTo("row_no");
        assertThat(colColumn.name()).isEqualTo("seat_no");
        assertThat(Arrays.stream(table.uniqueConstraints())
                .flatMap(uniqueConstraint -> Arrays.stream(uniqueConstraint.columnNames())))
                .contains("row_no", "seat_no")
                .doesNotContain("row_number", "col_number");
    }
}
