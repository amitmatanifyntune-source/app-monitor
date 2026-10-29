-- Fyntune App Monitor: monitored applications, their agents, and what the agents report (see PROTOCOL.md).

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(60)  NOT NULL UNIQUE,
    display_name  VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(12)  NOT NULL CHECK (role IN ('ADMIN', 'VIEWER')),  -- ADMIN sees and manages every app
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE app (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL UNIQUE,          -- used in URLs, e.g. proton-core-local
    name        VARCHAR(120) NOT NULL,
    environment VARCHAR(20)  NOT NULL DEFAULT 'production',
    description VARCHAR(400),
    slow_ms     INT          NOT NULL DEFAULT 2000,     -- sent to its agents
    mask        JSONB        NOT NULL DEFAULT '[]',     -- extra masking patterns, sent to its agents
    route_rules JSONB        NOT NULL DEFAULT '[]',     -- extra path normalising rules, sent to its agents
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- which viewers may see which apps (administrators see all)
CREATE TABLE app_access (
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    app_id  BIGINT NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, app_id)
);

CREATE TABLE agent (
    id            VARCHAR(24)  PRIMARY KEY,             -- ag_ + random, sent as X-Agent-Id
    app_id        BIGINT       NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    name          VARCHAR(80)  NOT NULL,
    key_hash      CHAR(64)     NOT NULL,                -- SHA-256 of the key; the key itself is shown once
    host          VARCHAR(120),
    version       VARCHAR(20),
    last_seq      BIGINT       NOT NULL DEFAULT 0,      -- batches at or below this were already stored
    last_seen     TIMESTAMPTZ,
    revoked       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_agent_app ON agent (app_id);

-- per-minute traffic for each agent; hist uses the buckets in PROTOCOL.md
CREATE TABLE minute_stat (
    app_id    BIGINT      NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    agent_id  VARCHAR(24) NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    minute    BIGINT      NOT NULL,                      -- minutes since the epoch, UTC
    requests  BIGINT      NOT NULL,
    errors    BIGINT      NOT NULL,
    refused   BIGINT      NOT NULL,
    slow      BIGINT      NOT NULL,
    total_ms  BIGINT      NOT NULL,
    max_ms    BIGINT      NOT NULL,
    hist      BIGINT[]    NOT NULL,
    PRIMARY KEY (app_id, minute, agent_id)
);

CREATE TABLE endpoint_minute (
    app_id    BIGINT       NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    minute    BIGINT       NOT NULL,
    method    VARCHAR(8)   NOT NULL,
    pattern   VARCHAR(300) NOT NULL,
    requests  BIGINT       NOT NULL,
    errors    BIGINT       NOT NULL,
    refused   BIGINT       NOT NULL,
    slow      BIGINT       NOT NULL,
    total_ms  BIGINT       NOT NULL,
    max_ms    BIGINT       NOT NULL,
    hist      BIGINT[]     NOT NULL,
    PRIMARY KEY (app_id, minute, method, pattern)
);

CREATE TABLE event (
    id          BIGSERIAL PRIMARY KEY,
    app_id      BIGINT       NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    agent_id    VARCHAR(24),
    at          TIMESTAMPTZ  NOT NULL,
    type        VARCHAR(12)  NOT NULL,                   -- ERROR | REFUSED | SLOW | LOG_ERROR | CHECK_FAIL | APP
    source      VARCHAR(80),
    request_id  VARCHAR(64),
    method      VARCHAR(8),
    path        VARCHAR(300),
    status      INT,
    ms          BIGINT,
    actor       VARCHAR(120),
    message     VARCHAR(600),
    detail      TEXT
);
CREATE INDEX ix_event_app_at ON event (app_id, at DESC);
CREATE INDEX ix_event_request ON event (app_id, request_id) WHERE request_id IS NOT NULL;

-- checks the agents run: HTTP (health, key APIs, SSL) and SQL (business checks)
CREATE TABLE check_def (
    id           BIGSERIAL PRIMARY KEY,
    app_id       BIGINT       NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    name         VARCHAR(80)  NOT NULL,
    kind         VARCHAR(8)   NOT NULL CHECK (kind IN ('HTTP', 'SQL')),
    config       JSONB        NOT NULL,                  -- url/expectStatus/maxMs/contains, or sql/refColumn/warnAbove
    interval_sec INT          NOT NULL DEFAULT 60,
    is_health    BOOLEAN      NOT NULL DEFAULT FALSE,    -- a failing health check marks the app DOWN
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (app_id, name)
);

CREATE TABLE check_result (
    id        BIGSERIAL PRIMARY KEY,
    app_id    BIGINT       NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    agent_id  VARCHAR(24),
    name      VARCHAR(80)  NOT NULL,
    kind      VARCHAR(8)   NOT NULL,
    at        TIMESTAMPTZ  NOT NULL,
    ok        BOOLEAN      NOT NULL,
    ms        BIGINT,
    value     DOUBLE PRECISION,
    message   VARCHAR(600),
    refs      JSONB
);
CREATE INDEX ix_check_result_app ON check_result (app_id, name, at DESC);

CREATE TABLE db_sample (
    id        BIGSERIAL PRIMARY KEY,
    app_id    BIGINT       NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    agent_id  VARCHAR(24),
    at        TIMESTAMPTZ  NOT NULL,
    engine    VARCHAR(12)  NOT NULL,
    version   VARCHAR(40),
    snapshot  JSONB        NOT NULL
);
CREATE INDEX ix_db_sample_app ON db_sample (app_id, at DESC);

CREATE TABLE host_sample (
    id        BIGSERIAL PRIMARY KEY,
    app_id    BIGINT       NOT NULL REFERENCES app (id) ON DELETE CASCADE,
    agent_id  VARCHAR(24)  NOT NULL,
    at        TIMESTAMPTZ  NOT NULL,
    sample    JSONB        NOT NULL
);
CREATE INDEX ix_host_sample_app ON host_sample (app_id, agent_id, at DESC);

CREATE TABLE audit_log (
    id        BIGSERIAL PRIMARY KEY,
    at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    username  VARCHAR(60),
    action    VARCHAR(40)  NOT NULL,                     -- LOGIN, LOGIN_FAILED, VIEW_APP, APP_CREATED, AGENT_REGISTERED, ...
    target    VARCHAR(120),
    detail    VARCHAR(600)
);
CREATE INDEX ix_audit_at ON audit_log (at DESC);
