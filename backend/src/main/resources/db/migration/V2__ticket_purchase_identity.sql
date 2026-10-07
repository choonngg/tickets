ALTER TABLE ticket ADD COLUMN purchase_key VARCHAR(64) NULL,
                   ADD COLUMN admission_id VARCHAR(64) NULL;
CREATE INDEX ix_ticket_user_purchase ON ticket (user_id, purchase_key);
