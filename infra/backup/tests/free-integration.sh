#!/usr/bin/env bash
set -Eeuo pipefail
source /opt/moneyflow-backup/common.sh
mkdir -p /work/secrets
printf '%s\n' source-test-password >/work/secrets/postgres_password
printf '%s\n' restore-test-password >/work/secrets/restore_db_password
load_source
for migration in /migrations/*.sql; do psql -X -q -v ON_ERROR_STOP=1 -f "$migration"; done
psql -X -q -v ON_ERROR_STOP=1 -f /tests/fixture.sql
# Private keys exist only in this synthetic fixture. Production Actions receives
# only the public recipient; the user's recovery identity stays offline.
age-keygen -o /work/fixture-identity.txt 2>/dev/null
export AGE_RECIPIENT
AGE_RECIPIENT=$(age-keygen -y /work/fixture-identity.txt)
/opt/moneyflow-backup/free-backup.sh
[[ ! -e /work/backup/moneyflow.dump ]] || die 'Plaintext archive survived encryption.'
cd /output
sha256sum -c moneyflow-database.tar.age.sha256
if grep -a -q 'PLAINTEXT_MUST_NOT_APPEAR_IN_BACKUP' moneyflow-database.tar.age; then die 'Plaintext marker survived encryption.'; fi
mkdir -p /work/decrypted
age --decrypt -i /work/fixture-identity.txt moneyflow-database.tar.age | tar -xf - -C /work/decrypted
/opt/moneyflow-backup/restore-archive.sh /work/decrypted
age-keygen -o /work/wrong-identity.txt 2>/dev/null
if age --decrypt -i /work/wrong-identity.txt moneyflow-database.tar.age >/dev/null 2>&1; then die 'Wrong private identity decrypted backup.'; fi
rm -rf -- /work/decrypted
rm -f -- /work/fixture-identity.txt /work/wrong-identity.txt
printf 'PASS: real age encryption/decryption, full PostgreSQL restore, fingerprint comparison, wrong-key denial and plaintext cleanup.\n'
