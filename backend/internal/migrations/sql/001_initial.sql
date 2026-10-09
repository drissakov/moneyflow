CREATE TABLE users (
    id uuid PRIMARY KEY,
    email text NOT NULL UNIQUE CHECK (length(email) BETWEEN 3 AND 254),
    password_hash text NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CHECK (expires_at > created_at)
);
CREATE INDEX sessions_user_id_idx ON sessions(user_id);
CREATE INDEX sessions_expires_at_idx ON sessions(expires_at);

CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    currency text NOT NULL CHECK (currency IN ('USD', 'EUR', 'KZT', 'JPY', 'KWD')),
    opening_balance_minor bigint NOT NULL CHECK (opening_balance_minor BETWEEN -1000000000000000 AND 1000000000000000),
    created_at timestamptz NOT NULL,
    UNIQUE (id, user_id)
);
CREATE INDEX accounts_user_id_idx ON accounts(user_id, created_at, id);

CREATE TABLE transactions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('expense', 'income')),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000000000000),
    currency text NOT NULL CHECK (currency IN ('USD', 'EUR', 'KZT', 'JPY', 'KWD')),
    note text NOT NULL DEFAULT '' CHECK (length(note) <= 500),
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (account_id, user_id) REFERENCES accounts(id, user_id) ON DELETE CASCADE
);
CREATE INDEX transactions_account_recent_idx ON transactions(user_id, account_id, occurred_at DESC, created_at DESC, id DESC);

CREATE TABLE idempotency_records (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key uuid NOT NULL,
    request_hash bytea NOT NULL CHECK (octet_length(request_hash) = 32),
    response_body bytea NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, key)
);
