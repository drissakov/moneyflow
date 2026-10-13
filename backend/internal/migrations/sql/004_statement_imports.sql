-- Drafts contain normalized rows only. Original PDFs never reach this database.
ALTER TABLE transactions ADD COLUMN exclude_from_analytics boolean NOT NULL DEFAULT false;

CREATE TABLE statement_imports (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    account_id uuid NOT NULL,
    bank text NOT NULL CHECK (bank IN ('kaspi', 'halyk')),
    currency text NOT NULL CHECK (currency = 'KZT'),
    file_sha256 text NOT NULL CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    timezone text NOT NULL CHECK (length(timezone) BETWEEN 1 AND 100),
    status text NOT NULL CHECK (status IN ('draft', 'committed', 'undone')),
    opening_balance_minor bigint,
    closing_balance_minor bigint,
    balance_minor bigint NOT NULL,
    total_count integer NOT NULL CHECK (total_count BETWEEN 1 AND 2000),
    imported_count integer NOT NULL DEFAULT 0 CHECK (imported_count BETWEEN 0 AND total_count),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    committed_at timestamptz,
    undone_at timestamptz,
    FOREIGN KEY (account_id, user_id) REFERENCES accounts(id, user_id) ON DELETE CASCADE,
    UNIQUE (id, user_id, account_id),
    CHECK (expires_at > created_at),
    CHECK ((status = 'draft' AND committed_at IS NULL AND undone_at IS NULL AND imported_count = 0)
        OR (status = 'committed' AND committed_at IS NOT NULL AND undone_at IS NULL AND imported_count > 0)
        OR (status = 'undone' AND committed_at IS NOT NULL AND undone_at IS NOT NULL AND imported_count > 0))
);
CREATE INDEX statement_imports_history_idx ON statement_imports(user_id, created_at DESC, id DESC);
CREATE INDEX statement_imports_expired_drafts_idx ON statement_imports(user_id, expires_at, id) WHERE status = 'draft';

CREATE TABLE statement_import_rows (
    import_id uuid NOT NULL REFERENCES statement_imports(id) ON DELETE CASCADE,
    row_id integer NOT NULL CHECK (row_id > 0),
    occurred_at timestamptz NOT NULL,
    local_day date NOT NULL,
    kind text NOT NULL CHECK (kind IN ('income', 'expense')),
    amount_minor bigint NOT NULL CHECK (amount_minor BETWEEN 1 AND 1000000000000000),
    note text NOT NULL CHECK (length(note) <= 500),
    matching_count bigint NOT NULL CHECK (matching_count >= 0),
    candidate_hash text NOT NULL CHECK (candidate_hash = '' OR candidate_hash ~ '^[0-9a-f]{64}$'),
    -- Preserve the original created ID for audit even when undo removes its ledger row.
    created_transaction_id uuid,
    PRIMARY KEY (import_id, row_id)
);

ALTER TABLE transactions ADD CONSTRAINT transactions_import_owner_unique UNIQUE(id, user_id, account_id);
CREATE TABLE transaction_import_sources (
    transaction_id uuid PRIMARY KEY,
    import_id uuid NOT NULL,
    user_id uuid NOT NULL,
    account_id uuid NOT NULL,
    file_sha256 text NOT NULL CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    row_id integer NOT NULL CHECK (row_id > 0),
    FOREIGN KEY (transaction_id, user_id, account_id) REFERENCES transactions(id, user_id, account_id) ON DELETE CASCADE,
    FOREIGN KEY (import_id, user_id, account_id) REFERENCES statement_imports(id, user_id, account_id) ON DELETE CASCADE,
    FOREIGN KEY (import_id, row_id) REFERENCES statement_import_rows(import_id, row_id) ON DELETE CASCADE,
    UNIQUE (account_id, file_sha256, row_id)
);
CREATE INDEX transaction_import_sources_row_idx ON transaction_import_sources(import_id, row_id);
