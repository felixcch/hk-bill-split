CREATE TABLE split_group (
    id UUID PRIMARY KEY,
    code_hash CHAR(64) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    emoji VARCHAR(8) NOT NULL DEFAULT '🐻',
    currency VARCHAR(3) NOT NULL DEFAULT 'HKD' CHECK (currency = 'HKD'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE member (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES split_group(id),
    name VARCHAR(40) NOT NULL,
    position INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, group_id)
);
CREATE UNIQUE INDEX member_unique_name ON member(group_id, lower(name));
CREATE UNIQUE INDEX member_unique_position ON member(group_id, position);
CREATE TABLE expense (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES split_group(id),
    payer_member_id UUID NOT NULL,
    description VARCHAR(160) NOT NULL,
    category VARCHAR(20) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
    split_method VARCHAR(10) NOT NULL CHECK (split_method IN ('EQUAL', 'EXACT', 'PERCENT')),
    incurred_on DATE NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (payer_member_id, group_id) REFERENCES member(id, group_id),
    UNIQUE (id, group_id)
);
CREATE INDEX expense_group_date ON expense(group_id, incurred_on DESC, created_at DESC);
CREATE TABLE expense_share (
    expense_id UUID NOT NULL,
    group_id UUID NOT NULL,
    member_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor >= 0),
    PRIMARY KEY (expense_id, member_id),
    FOREIGN KEY (expense_id, group_id) REFERENCES expense(id, group_id) ON DELETE CASCADE,
    FOREIGN KEY (member_id, group_id) REFERENCES member(id, group_id)
);
CREATE TABLE transfer (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES split_group(id),
    from_member_id UUID NOT NULL,
    to_member_id UUID NOT NULL CHECK (to_member_id <> from_member_id),
    amount_minor BIGINT NOT NULL CHECK (amount_minor BETWEEN 1 AND 100000000),
    method VARCHAR(10) NOT NULL CHECK (method IN ('FPS', 'PAYME', 'BANK', 'CASH')),
    incurred_on DATE NOT NULL,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (from_member_id, group_id) REFERENCES member(id, group_id),
    FOREIGN KEY (to_member_id, group_id) REFERENCES member(id, group_id)
);
CREATE INDEX transfer_group ON transfer(group_id, created_at DESC);
CREATE TABLE activity (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES split_group(id),
    actor_member_id UUID,
    entity_id UUID NOT NULL,
    action VARCHAR(40) NOT NULL,
    detail TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX activity_group ON activity(group_id, created_at DESC);
CREATE TABLE idempotency (
    group_id UUID NOT NULL REFERENCES split_group(id),
    key UUID NOT NULL,
    operation VARCHAR(40) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    result_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, key)
);
REVOKE ALL ON SCHEMA public FROM PUBLIC;
