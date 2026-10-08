package com.project.ticket.domain.ticket.service.purchase;

import lombok.Getter;
import lombok.Setter;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ticket")
public class TicketPurchaseProperties {
    private PurchaseStrategy purchaseStrategy = PurchaseStrategy.PESSIMISTIC;
    private Duration seatLockLease = Duration.ofSeconds(10);

}
