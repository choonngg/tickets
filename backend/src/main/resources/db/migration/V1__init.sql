CREATE TABLE `users` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `google_sub`  VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `email`       VARCHAR(255) NOT NULL,
    `name`        VARCHAR(100) NOT NULL,
    `role`        ENUM('FAN', 'ARTIST') NOT NULL DEFAULT 'FAN',
    `deleted_at`  DATETIME(6) NULL,
    `created_at`  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at`  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_users_google_sub` UNIQUE (`google_sub`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `auth_session` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT NOT NULL,
    `expires_at`  DATETIME(6) NOT NULL,
    `revoked_at`  DATETIME(6) NULL,
    `created_at`  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    KEY `ix_auth_session_user_fk` (`user_id`),
    KEY `ix_auth_session_expires_at` (`expires_at`),
    CONSTRAINT `fk_auth_session_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
    CONSTRAINT `ck_auth_session_expiration` CHECK (`expires_at` > `created_at`),
    CONSTRAINT `ck_auth_session_revocation` CHECK (`revoked_at` IS NULL OR `revoked_at` >= `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `refresh_token` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT,
    `session_id`  BIGINT NOT NULL,
    `token_hash`  BINARY(32) NOT NULL,
    `consumed_at` DATETIME(6) NULL,
    -- VIRTUAL: MySQL은 STORED 생성 컬럼의 기반 컬럼(session_id) FK에 ON DELETE CASCADE를 허용하지 않는다(1215). 05 문서 3(b)
    `unconsumed_session_id` BIGINT GENERATED ALWAYS AS (
        CASE WHEN `consumed_at` IS NULL THEN `session_id` ELSE NULL END
    ) VIRTUAL,
    `created_at`  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    CONSTRAINT `uq_refresh_token_hash` UNIQUE (`token_hash`),
    CONSTRAINT `uq_refresh_token_unconsumed_session` UNIQUE (`unconsumed_session_id`),
    KEY `ix_refresh_token_session_fk` (`session_id`),
    CONSTRAINT `fk_refresh_token_session` FOREIGN KEY (`session_id`) REFERENCES `auth_session` (`id`) ON DELETE CASCADE,
    CONSTRAINT `ck_refresh_token_consumed` CHECK (`consumed_at` IS NULL OR `consumed_at` >= `created_at`)
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
