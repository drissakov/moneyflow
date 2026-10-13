package service

import (
	"strconv"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"gorm.io/gorm"
)

type importCandidate struct {
	LocalDay      string
	Kind          string
	AmountMinor   int64
	MatchingCount int64
	CandidateHash string
}

func candidateKey(day, kind string, amount int64) string {
	return day + ":" + kind + ":" + strconv.FormatInt(amount, 10)
}

func importCandidates(tx *gorm.DB, draft model.StatementImport, rows []model.StatementImportRow) (map[string]importCandidate, error) {
	result := make(map[string]importCandidate)
	if len(rows) == 0 {
		return result, nil
	}
	location, err := time.LoadLocation(draft.Timezone)
	if err != nil {
		return nil, err
	}
	first, last := rows[0].LocalDay, rows[0].LocalDay
	for _, row := range rows {
		if row.LocalDay < first {
			first = row.LocalDay
		}
		if row.LocalDay > last {
			last = row.LocalDay
		}
	}
	start, err := time.ParseInLocation(time.DateOnly, first, location)
	if err != nil {
		return nil, err
	}
	end, err := time.ParseInLocation(time.DateOnly, last, location)
	if err != nil {
		return nil, err
	}
	end = end.AddDate(0, 0, 1)
	var groups []importCandidate
	err = tx.Raw(`SELECT (occurred_at AT TIME ZONE ?)::date::text AS local_day, kind, amount_minor,
		COUNT(*) AS matching_count,
		encode(sha256(convert_to(string_agg(id::text, ',' ORDER BY id), 'UTF8')), 'hex') AS candidate_hash
		FROM transactions WHERE user_id = ? AND account_id = ? AND occurred_at >= ? AND occurred_at < ?
		GROUP BY (occurred_at AT TIME ZONE ?)::date, kind, amount_minor`,
		draft.Timezone, draft.UserID, draft.AccountID, start.UTC(), end.UTC(), draft.Timezone).Scan(&groups).Error
	if err != nil {
		return nil, err
	}
	for _, group := range groups {
		result[candidateKey(group.LocalDay, group.Kind, group.AmountMinor)] = group
	}
	return result, nil
}

func importExactRows(tx *gorm.DB, draft model.StatementImport, rows []model.StatementImportRow) (map[int]bool, error) {
	result := make(map[int]bool)
	ids := make([]int, 0, len(rows))
	for _, row := range rows {
		ids = append(ids, row.RowID)
	}
	var sources []model.TransactionImportSource
	err := tx.Select("row_id").Where("user_id = ? AND account_id = ? AND file_sha256 = ? AND row_id IN ?", draft.UserID, draft.AccountID, draft.FileSHA256, ids).Find(&sources).Error
	if err != nil {
		return nil, err
	}
	for _, source := range sources {
		result[source.RowID] = true
	}
	return result, nil
}
