-- users/login stay on the current password-login model until Phase 3.
CREATE TABLE `users` (
    `id`      BIGINT NOT NULL AUTO_INCREMENT,
    `name`    VARCHAR(50) NOT NULL,
    `role`    ENUM('FAN', 'ARTIST', 'ADMIN') NOT NULL,
    `deleted` BIT NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `login` (
    `id`       BIGINT NOT NULL AUTO_INCREMENT,
    `user_id`  BIGINT NOT NULL,
    `email`    VARCHAR(100) NOT NULL,
    `password` VARCHAR(255) NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_login_email` UNIQUE (`email`),
    CONSTRAINT `uq_login_user` UNIQUE (`user_id`),
    CONSTRAINT `fk_login_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `concert` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `artist_id`        BIGINT       NOT NULL,
    `title`            VARCHAR(100) NOT NULL,
    `venue`            VARCHAR(255) NOT NULL,
    `concert_at`       DATETIME(6)  NOT NULL,
    `ticket_open_at`   DATETIME(6)  NOT NULL,
    `ticket_close_at`  DATETIME(6)  NOT NULL,
    `cancelled_at`     DATETIME(6)  NULL,
    `created_at`       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                    ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (`id`),

    KEY `ix_concert_artist_fk` (`artist_id`),

    CONSTRAINT `fk_concert_artist`
        FOREIGN KEY (`artist_id`) REFERENCES `users` (`id`),

    CONSTRAINT `ck_concert_ticket_window`
        CHECK (
            `ticket_open_at` < `ticket_close_at`
            AND `ticket_close_at` <= `concert_at`
        )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `seat` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `concert_id`    BIGINT      NOT NULL,
    `section_name`  VARCHAR(50) NOT NULL,
    `row_no`        INT         NOT NULL,
    `seat_no`       INT         NOT NULL,
    `price`         INT         NOT NULL,
    `status`        ENUM('AVAILABLE', 'SOLD') NOT NULL DEFAULT 'AVAILABLE',
    `version`       BIGINT      NOT NULL DEFAULT 0,
    `created_at`    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (`id`),

    CONSTRAINT `uq_seat_position`
        UNIQUE (`concert_id`, `section_name`, `row_no`, `seat_no`),

    CONSTRAINT `fk_seat_concert`
        FOREIGN KEY (`concert_id`) REFERENCES `concert` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ticket` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT      NOT NULL,
    `seat_id`         BIGINT      NOT NULL,
    `purchase_price`  INT         NOT NULL,
    `created_at`      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (`id`),

    CONSTRAINT `uq_ticket_seat`
        UNIQUE (`seat_id`),

    KEY `ix_ticket_user_fk` (`user_id`),

    CONSTRAINT `fk_ticket_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),

    CONSTRAINT `fk_ticket_seat`
        FOREIGN KEY (`seat_id`) REFERENCES `seat` (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;
