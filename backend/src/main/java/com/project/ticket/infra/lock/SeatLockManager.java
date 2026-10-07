package com.project.ticket.infra.lock;

import java.util.function.Supplier;
import java.util.List;

public interface SeatLockManager {
    default <T> T executeWithLock(List<Long> seatIds, Supplier<T> supplier) {
        List<Long> sorted = seatIds.stream().sorted().toList();
        return lockNext(sorted, 0, supplier);
    }

    private <T> T lockNext(List<Long> ids, int index, Supplier<T> supplier) {
        return index == ids.size() ? supplier.get()
                : executeWithLock(ids.get(index), () -> lockNext(ids, index + 1, supplier));
    }

    <T> T executeWithLock(Long seatId, Supplier<T> supplier);
}
