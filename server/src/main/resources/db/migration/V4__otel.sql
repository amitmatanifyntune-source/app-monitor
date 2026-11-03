-- OpenTelemetry: outbound calls per minute (databases, HTTP APIs, queues) and the traces the agents kept.

CREATE TABLE dependency_minute (
    app_id     BIGINT       NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    minute     BIGINT       NOT NULL,
    kind       VARCHAR(12)  NOT NULL,                    -- db | http | queue | other
    target     VARCHAR(200) NOT NULL,                    -- "mysql claims @ 127.0.0.1", "api.razorpay.com"
    operation  VARCHAR(200) NOT NULL,                    -- "SELECT policies", "POST /v1/orders/{id}"
    calls      BIGINT       NOT NULL,
    errors     BIGINT       NOT NULL,
    total_ms   BIGINT       NOT NULL,
    max_ms     BIGINT       NOT NULL,
    hist       BIGINT[]     NOT NULL,
    PRIMARY KEY (app_id, minute, kind, target, operation)
);

CREATE TABLE trace (
    id           BIGSERIAL PRIMARY KEY,
    app_id       BIGINT       NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    agent_id     VARCHAR(24),
    trace_id     VARCHAR(32)  NOT NULL,
    at           TIMESTAMPTZ  NOT NULL,
    name         VARCHAR(300) NOT NULL,
    service      VARCHAR(120),
    duration_ms  BIGINT       NOT NULL,
    error        BOOLEAN      NOT NULL,
    reason       VARCHAR(12)  NOT NULL,                  -- error | slow | sample
    status       INT,
    request_id   VARCHAR(64),
    span_count   INT          NOT NULL,
    spans        JSONB        NOT NULL
);
CREATE INDEX ix_trace_app_at ON trace (app_id, at DESC);
CREATE INDEX ix_trace_ids ON trace (app_id, trace_id);
CREATE INDEX ix_trace_request ON trace (app_id, request_id) WHERE request_id IS NOT NULL;
