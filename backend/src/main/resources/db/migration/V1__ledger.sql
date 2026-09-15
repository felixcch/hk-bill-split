CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    display_name VARCHAR(60) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE split_group (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'HKD' CHECK (currency = 'HKD'),
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE group_member (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES split_group(id),
    user_id UUID NOT NULL REFERENCES app_user(id),
    role VARCHAR(10) NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    inactive_at TIMESTAMPTZ,
    UNIQUE (group_id, user_id),
    UNIQUE (id, group_id)
);
CREATE TABLE expense (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES split_group(id),
    payer_member_id UUID NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    description VARCHAR(160) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
    split_method VARCHAR(10) NOT NULL CHECK (split_method IN ('EQUAL', 'EXACT', 'PERCENT')),
    incurred_on DATE NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    voided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (payer_member_id, group_id) REFERENCES group_member(id, group_id),
    UNIQUE (id, group_id)
);
CREATE INDEX expense_group_date ON expense(group_id, incurred_on DESC, created_at DESC);
CREATE TABLE expense_share (
    expense_id UUID NOT NULL,
    group_id UUID NOT NULL,
    member_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor >= 0),
    PRIMARY KEY (expense_id, member_id),
    FOREIGN KEY (expense_id, group_id) REFERENCES expense(id, group_id),
    FOREIGN KEY (member_id, group_id) REFERENCES group_member(id, group_id)
);
CREATE TABLE settlement (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES split_group(id),
    sender_member_id UUID NOT NULL,
    recipient_member_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
    method VARCHAR(10) NOT NULL CHECK (method IN ('FPS', 'PAYME', 'BANK', 'CASH')),
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING'
      CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'CANCELLED')),
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMPTZ,
    CHECK (sender_member_id <> recipient_member_id),
    FOREIGN KEY (sender_member_id, group_id) REFERENCES group_member(id, group_id),
    FOREIGN KEY (recipient_member_id, group_id) REFERENCES group_member(id, group_id)
);
CREATE INDEX settlement_group_date ON settlement(group_id, created_at DESC);
CREATE TABLE invitation (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES split_group(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    redeemed_at TIMESTAMPTZ,
    redeemed_by UUID REFERENCES app_user(id),
    revoked_at TIMESTAMPTZ
);
CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES split_group(id),
    actor_id UUID NOT NULL REFERENCES app_user(id),
    entity_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL,
    detail TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_group_date ON audit_event(group_id, created_at DESC);
CREATE TABLE idempotency (
    actor_id UUID NOT NULL REFERENCES app_user(id),
    operation_key UUID NOT NULL,
    operation VARCHAR(100) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    result_id UUID,
    PRIMARY KEY (actor_id, operation_key)
);
REVOKE ALL ON SCHEMA billsplit FROM PUBLIC;
