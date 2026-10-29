-- Alerts: where each app's status changes are sent, what was last sent, and a log of every message.

-- SLACK: an incoming-webhook URL (a secret: never returned whole). WHATSAPP: a phone number, international digits only.
CREATE TABLE alert_channel (
    id               BIGSERIAL PRIMARY KEY,
    app_id           BIGINT       NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    kind             VARCHAR(10)  NOT NULL CHECK (kind IN ('SLACK', 'WHATSAPP')),
    target           VARCHAR(400) NOT NULL,
    label            VARCHAR(80),
    include_degraded BOOLEAN      NOT NULL DEFAULT TRUE,   -- false: only Down and its recovery
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_alert_channel_app ON alert_channel (app_id);

-- the status last alerted for, so each change is sent once; a new status must hold for a while (pending) before it counts
CREATE TABLE alert_state (
    app_id         BIGINT       PRIMARY KEY REFERENCES app (id) ON DELETE CASCADE,
    status         VARCHAR(10)  NOT NULL,
    since          TIMESTAMPTZ  NOT NULL,
    pending_status VARCHAR(10),
    pending_since  TIMESTAMPTZ,
    last_sent_at   TIMESTAMPTZ
);

CREATE TABLE alert_log (
    id         BIGSERIAL PRIMARY KEY,
    app_id     BIGINT       NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    channel_id BIGINT       REFERENCES alert_channel (id) ON DELETE SET NULL,
    at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    kind       VARCHAR(10)  NOT NULL,
    status     VARCHAR(10)  NOT NULL,
    ok         BOOLEAN      NOT NULL,
    message    VARCHAR(600) NOT NULL,
    error      VARCHAR(600)
);
CREATE INDEX ix_alert_log_app ON alert_log (app_id, at DESC);

-- alerts held during planned work
ALTER TABLE app ADD COLUMN muted_until TIMESTAMPTZ;
