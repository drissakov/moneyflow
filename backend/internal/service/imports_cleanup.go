package service

import (
	"context"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

// CleanupExpiredImports runs only when this owner accesses an import endpoint.
// A separate, bounded transaction takes no account locks and skips drafts whose
// parent is already locked by a commit. Metadata and committed audit rows remain.
func (s *Service) CleanupExpiredImports(ctx context.Context, userID string) error {
	return s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		now := time.Now().UTC()
		var drafts []model.StatementImport
		if err := tx.Select("id").Clauses(clause.Locking{Strength: "UPDATE", Options: "SKIP LOCKED"}).
			Where("user_id=? AND status='draft' AND expires_at<=? AND EXISTS(SELECT 1 FROM statement_import_rows r WHERE r.import_id=statement_imports.id)", userID, now).
			Order("expires_at,id").Limit(20).Find(&drafts).Error; err != nil {
			return err
		}
		if len(drafts) == 0 {
			return nil
		}
		ids := make([]string, 0, len(drafts))
		for _, draft := range drafts {
			ids = append(ids, draft.ID)
		}
		return tx.Exec(`DELETE FROM statement_import_rows r USING statement_imports i
			WHERE r.import_id=i.id AND i.id IN ? AND i.user_id=? AND i.status='draft' AND i.expires_at<=?`, ids, userID, now).Error
	})
}
