package com.project.ticket.domain.ticket.service.purchase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TicketPurchaseStrategyRouterTest {

    private final TicketPurchaseStrategy optimistic = (userId, seatIds, purchaseKey, admissionId) -> null;
    private final TicketPurchaseStrategy pessimistic = (userId, seatIds, purchaseKey, admissionId) -> null;
    private final TicketPurchaseStrategy distributed = (userId, seatIds, purchaseKey, admissionId) -> null;

    @Test
    void routesToOptimisticStrategy() {
        TicketPurchaseStrategyRouter router = router(PurchaseStrategy.OPTIMISTIC);

        assertThat(router.current()).isSameAs(optimistic);
    }

    @Test
    void routesToPessimisticStrategy() {
        TicketPurchaseStrategyRouter router = router(PurchaseStrategy.PESSIMISTIC);

        assertThat(router.current()).isSameAs(pessimistic);
    }

    @Test
    void routesToDistributedStrategy() {
        TicketPurchaseStrategyRouter router = router(PurchaseStrategy.DISTRIBUTED);

        assertThat(router.current()).isSameAs(distributed);
    }

    private TicketPurchaseStrategyRouter router(PurchaseStrategy strategy) {
        TicketPurchaseProperties properties = new TicketPurchaseProperties();
        properties.setPurchaseStrategy(strategy);
        return new TicketPurchaseStrategyRouter(properties, optimistic, pessimistic, distributed);
    }
}
