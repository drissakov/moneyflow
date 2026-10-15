ALTER TABLE accounts ADD COLUMN type text NOT NULL DEFAULT 'card' CHECK(type IN ('card','cash','savings','credit','other')),
 ADD COLUMN archived boolean NOT NULL DEFAULT false,
 ADD COLUMN include_in_balance boolean NOT NULL DEFAULT true,
 ADD COLUMN sort_order integer NOT NULL DEFAULT 0,
 ADD COLUMN version bigint NOT NULL DEFAULT 1 CHECK(version > 0);
ALTER TABLE categories ADD COLUMN archived boolean NOT NULL DEFAULT false,
 ADD COLUMN sort_order integer NOT NULL DEFAULT 0,
 ADD COLUMN version bigint NOT NULL DEFAULT 1 CHECK(version > 0);
CREATE TABLE category_overrides (
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 category_id uuid NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
 name text NOT NULL CHECK(length(name) BETWEEN 1 AND 100),
 archived boolean NOT NULL DEFAULT false, sort_order integer NOT NULL DEFAULT 0,
 version bigint NOT NULL DEFAULT 1 CHECK(version > 0), PRIMARY KEY(user_id,category_id)
);
CREATE TABLE transfers (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 from_account_id uuid NOT NULL, to_account_id uuid NOT NULL,
 from_amount_minor bigint NOT NULL CHECK(from_amount_minor BETWEEN 1 AND 1000000000000000),
 to_amount_minor bigint NOT NULL CHECK(to_amount_minor BETWEEN 1 AND 1000000000000000),
 fee_account_id uuid, fee_amount_minor bigint NOT NULL DEFAULT 0 CHECK(fee_amount_minor BETWEEN 0 AND 1000000000000000),
 note text NOT NULL DEFAULT '' CHECK(length(note)<=500),
 occurred_at timestamptz NOT NULL, created_at timestamptz NOT NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), deleted_at timestamptz,
 UNIQUE(id,user_id), CHECK(from_account_id<>to_account_id),
 CHECK((fee_amount_minor=0 AND fee_account_id IS NULL) OR (fee_amount_minor>0 AND fee_account_id IS NOT NULL)),
 FOREIGN KEY(from_account_id,user_id) REFERENCES accounts(id,user_id),
 FOREIGN KEY(to_account_id,user_id) REFERENCES accounts(id,user_id),
 FOREIGN KEY(fee_account_id,user_id) REFERENCES accounts(id,user_id)
);
ALTER TABLE transactions ADD COLUMN version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
 ADD COLUMN deleted_at timestamptz,
 ADD COLUMN transfer_id uuid,
 ADD COLUMN transfer_role text,
 ADD CONSTRAINT transactions_transfer_fk FOREIGN KEY(transfer_id,user_id) REFERENCES transfers(id,user_id),
 ADD CONSTRAINT transactions_transfer_role_check CHECK((transfer_id IS NULL AND transfer_role IS NULL) OR (transfer_id IS NOT NULL AND transfer_role IN ('from','to','fee')));
CREATE UNIQUE INDEX transactions_transfer_role_idx ON transactions(transfer_id,transfer_role) WHERE transfer_id IS NOT NULL;
CREATE INDEX transactions_history_idx ON transactions(user_id,occurred_at DESC,created_at DESC,id DESC);
CREATE INDEX transfers_history_idx ON transfers(user_id,occurred_at DESC,created_at DESC,id DESC);
DROP INDEX categories_name_scope_idx;
CREATE UNIQUE INDEX categories_name_scope_idx ON categories(
 COALESCE(user_id,'00000000-0000-0000-0000-000000000000'::uuid),kind,
 COALESCE(parent_id,'00000000-0000-0000-0000-000000000000'::uuid),
 translate(lower(name),'АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ','абвгдеёжзийклмнопрстуфхцчшщъыьэюя')
) WHERE NOT archived;

-- Account currencies cannot be changed after ledger creation, even by another
-- writer. A transfer is one entity: deferred guards validate its final state,
-- allowing its two/three legs to be updated atomically in any statement order.
CREATE FUNCTION guard_account_currency() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.currency IS DISTINCT FROM OLD.currency THEN
  RAISE EXCEPTION 'account currency is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER accounts_currency_guard BEFORE UPDATE OF currency ON accounts
 FOR EACH ROW EXECUTE FUNCTION guard_account_currency();

CREATE FUNCTION check_transfer_integrity(transfer_uuid uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE tr transfers%ROWTYPE; expected integer; matching integer; total integer; source_currency text; target_currency text;
BEGIN
 IF transfer_uuid IS NULL THEN RETURN; END IF;
 SELECT * INTO tr FROM transfers WHERE id=transfer_uuid;
 IF NOT FOUND THEN RETURN; END IF;
 SELECT currency INTO source_currency FROM accounts WHERE id=tr.from_account_id AND user_id=tr.user_id;
 SELECT currency INTO target_currency FROM accounts WHERE id=tr.to_account_id AND user_id=tr.user_id;
 IF source_currency=target_currency AND tr.from_amount_minor<>tr.to_amount_minor THEN
  RAISE EXCEPTION 'same currency transfer amounts must match' USING ERRCODE='23514';
 END IF;
 expected:=CASE WHEN tr.fee_amount_minor>0 THEN 3 ELSE 2 END;
 SELECT count(*) INTO total FROM transactions WHERE transfer_id=tr.id;
 SELECT count(*) INTO matching FROM transactions l JOIN accounts a ON a.id=l.account_id AND a.user_id=l.user_id
 WHERE l.transfer_id=tr.id AND l.user_id=tr.user_id AND l.currency=a.currency
 AND l.note=tr.note AND l.occurred_at=tr.occurred_at AND l.deleted_at IS NOT DISTINCT FROM tr.deleted_at
 AND ((l.transfer_role='from' AND l.account_id=tr.from_account_id AND l.amount_minor=tr.from_amount_minor
       AND l.kind='expense' AND l.exclude_from_analytics AND l.category_id IS NULL)
   OR (l.transfer_role='to' AND l.account_id=tr.to_account_id AND l.amount_minor=tr.to_amount_minor
       AND l.kind='income' AND l.exclude_from_analytics AND l.category_id IS NULL)
   OR (l.transfer_role='fee' AND l.account_id=tr.fee_account_id AND l.amount_minor=tr.fee_amount_minor
       AND l.kind='expense' AND NOT l.exclude_from_analytics));
 IF total<>expected OR matching<>expected THEN
  RAISE EXCEPTION 'transfer legs must match their atomic group' USING ERRCODE='23514';
 END IF;
END;
$$;
CREATE FUNCTION guard_transfer_integrity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='transfers' THEN
  IF TG_OP='DELETE' THEN PERFORM check_transfer_integrity(OLD.id);
  ELSE PERFORM check_transfer_integrity(NEW.id); END IF;
 ELSE
  IF TG_OP<>'INSERT' THEN PERFORM check_transfer_integrity(OLD.transfer_id); END IF;
  IF TG_OP<>'DELETE' THEN PERFORM check_transfer_integrity(NEW.transfer_id); END IF;
 END IF;
 RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER transfers_integrity_guard AFTER INSERT OR UPDATE OR DELETE ON transfers
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION guard_transfer_integrity();
CREATE CONSTRAINT TRIGGER transactions_transfer_integrity_guard AFTER INSERT OR UPDATE OR DELETE ON transactions
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION guard_transfer_integrity();
