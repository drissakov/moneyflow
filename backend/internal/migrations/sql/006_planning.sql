CREATE TABLE budgets (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 name text NOT NULL CHECK(length(name) BETWEEN 1 AND 100), category_id uuid NOT NULL REFERENCES categories(id),
 currency text NOT NULL, limit_minor bigint NOT NULL CHECK(limit_minor BETWEEN 1 AND 1000000000000000),
 period_start_day integer NOT NULL CHECK(period_start_day BETWEEN 1 AND 31),
 archived boolean NOT NULL DEFAULT false, version bigint NOT NULL DEFAULT 1 CHECK(version>0), created_at timestamptz NOT NULL
);
CREATE INDEX budgets_owner_idx ON budgets(user_id);
CREATE TABLE schedules (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 name text NOT NULL CHECK(length(name) BETWEEN 1 AND 100), account_id uuid NOT NULL, category_id uuid REFERENCES categories(id),
 kind text NOT NULL CHECK(kind IN ('income','expense')), currency text NOT NULL,
 amount_minor bigint NOT NULL CHECK(amount_minor BETWEEN 1 AND 1000000000000000), note text NOT NULL CHECK(length(note)<=500),
 start_date date NOT NULL, end_date date, frequency text NOT NULL CHECK(frequency IN ('once','weekly','monthly','yearly')),
 interval integer NOT NULL CHECK(interval BETWEEN 1 AND 12), mandatory boolean NOT NULL, primary_income boolean NOT NULL,
 paused boolean NOT NULL DEFAULT false, archived boolean NOT NULL DEFAULT false,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), created_at timestamptz NOT NULL,
 UNIQUE(id,user_id), FOREIGN KEY(account_id,user_id) REFERENCES accounts(id,user_id),
 CHECK(end_date IS NULL OR end_date>=start_date), CHECK(NOT mandatory OR kind='expense'), CHECK(NOT primary_income OR kind='income')
);
CREATE INDEX schedules_owner_idx ON schedules(user_id);
ALTER TABLE transactions ADD CONSTRAINT transactions_planning_owner_unique UNIQUE(id,user_id);
CREATE TABLE planned_occurrences (
 schedule_id uuid NOT NULL, user_id uuid NOT NULL, due_date date NOT NULL,
 version bigint NOT NULL CHECK(version>0), template_version bigint NOT NULL CHECK(template_version>0),
 status text NOT NULL CHECK(status IN ('pending','skipped','posted')), snapshot jsonb NOT NULL,
 transaction_id uuid, created_at timestamptz NOT NULL,
 PRIMARY KEY(schedule_id,due_date), FOREIGN KEY(schedule_id,user_id) REFERENCES schedules(id,user_id),
 FOREIGN KEY(transaction_id,user_id) REFERENCES transactions(id,user_id),
 CHECK((status='posted')=(transaction_id IS NOT NULL))
);
CREATE UNIQUE INDEX planned_occurrences_transaction_idx ON planned_occurrences(transaction_id) WHERE transaction_id IS NOT NULL;
CREATE INDEX planned_occurrences_owner_idx ON planned_occurrences(user_id,due_date);
CREATE TABLE planning_settings (
 user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 reserve_minor bigint NOT NULL CHECK(reserve_minor BETWEEN 0 AND 1000000000000000), reserve_currency text NOT NULL,
 account_ids jsonb NOT NULL CHECK(jsonb_typeof(account_ids)='array'), version bigint NOT NULL CHECK(version>0)
);
