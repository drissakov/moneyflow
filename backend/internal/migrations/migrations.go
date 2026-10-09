// Package migrations applies immutable, versioned SQL files using a database lock.
// It deliberately runs separately from the API; the API never changes its schema.
package migrations

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/hex"
	"fmt"
	"sort"
)

//go:embed sql/*.sql
var files embed.FS

func Apply(ctx context.Context, db *sql.DB) error {
	conn, err := db.Conn(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()
	// The connection lock also serializes schema_migrations initialization.
	if _, err := conn.ExecContext(ctx, "SELECT pg_advisory_lock(625648446711)"); err != nil {
		return err
	}
	defer conn.ExecContext(context.Background(), "SELECT pg_advisory_unlock(625648446711)")
	if _, err := conn.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now()
	)`); err != nil {
		return err
	}
	entries, err := files.ReadDir("sql")
	if err != nil {
		return err
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	for _, entry := range entries {
		script, err := files.ReadFile("sql/" + entry.Name())
		if err != nil {
			return err
		}
		hash := sha256.Sum256(script)
		checksum := hex.EncodeToString(hash[:])
		var existing string
		err = conn.QueryRowContext(ctx, "SELECT checksum FROM schema_migrations WHERE version = $1", entry.Name()).Scan(&existing)
		if err == nil {
			if existing != checksum {
				return fmt.Errorf("migration %s changed after being applied; create a new migration instead", entry.Name())
			}
			continue
		}
		if err != sql.ErrNoRows {
			return err
		}
		tx, err := conn.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, string(script)); err == nil {
			_, err = tx.ExecContext(ctx, "INSERT INTO schema_migrations(version, checksum) VALUES ($1, $2)", entry.Name(), checksum)
		}
		if err != nil {
			tx.Rollback()
			return fmt.Errorf("migration %s failed: %w", entry.Name(), err)
		}
		if err := tx.Commit(); err != nil {
			return fmt.Errorf("commit migration %s: %w", entry.Name(), err)
		}
	}
	return nil
}
