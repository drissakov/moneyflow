package service

import (
	"regexp"
	"sort"
	"strconv"
	"time"
	_ "time/tzdata"
	"unicode/utf8"

	"github.com/drissakov/moneyflow/backend/internal/model"
)

const ImportMaxRows = 2000
const importDraftTTL = 24 * time.Hour

var fileHashPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

type ImportRowInput struct {
	RowID       int    `json:"row_id"`
	OccurredAt  string `json:"occurred_at"`
	Kind        string `json:"kind"`
	AmountMinor string `json:"amount_minor"`
	Note        string `json:"note"`
}

type ImportPreviewInput struct {
	AccountID           string           `json:"account_id"`
	Bank                string           `json:"bank"`
	Currency            string           `json:"currency"`
	FileSHA256          string           `json:"file_sha256"`
	Timezone            string           `json:"timezone"`
	OpeningBalanceMinor *string          `json:"opening_balance_minor"`
	ClosingBalanceMinor *string          `json:"closing_balance_minor"`
	Rows                []ImportRowInput `json:"rows"`
}

type ImportSelection struct {
	RowID                  int     `json:"row_id"`
	CategoryID             *string `json:"category_id"`
	AllowPossibleDuplicate bool    `json:"allow_possible_duplicate"`
	ExcludeFromAnalytics   bool    `json:"exclude_from_analytics"`
}

type ImportCommitInput struct {
	Rows []ImportSelection `json:"rows"`
}

func optionalImportBalance(raw *string) (*int64, error) {
	if raw == nil {
		return nil, nil
	}
	value, err := strconv.ParseInt(*raw, 10, 64)
	if err != nil || strconv.FormatInt(value, 10) != *raw {
		return nil, Invalid("Statement balances must be canonical signed 64-bit integer strings.")
	}
	return &value, nil
}

func validateImportPreview(input ImportPreviewInput) (model.StatementImport, []model.StatementImportRow, *time.Location, error) {
	var draft model.StatementImport
	id, err := ParseID(input.AccountID)
	if err != nil {
		return draft, nil, nil, err
	}
	if input.Bank != "kaspi" && input.Bank != "halyk" {
		return draft, nil, nil, Invalid("Bank must be kaspi or halyk.")
	}
	if input.Currency != "KZT" {
		return draft, nil, nil, Invalid("PDF statement import currently supports KZT only.")
	}
	if !fileHashPattern.MatchString(input.FileSHA256) {
		return draft, nil, nil, Invalid("File_sha256 must be 64 lowercase hexadecimal characters.")
	}
	if input.Timezone == "" || input.Timezone == "Local" || len(input.Timezone) > 100 || !utf8.ValidString(input.Timezone) {
		return draft, nil, nil, Invalid("Timezone must be a valid IANA identifier.")
	}
	location, err := time.LoadLocation(input.Timezone)
	if err != nil {
		return draft, nil, nil, Invalid("Timezone must be a valid IANA identifier.")
	}
	opening, err := optionalImportBalance(input.OpeningBalanceMinor)
	if err != nil {
		return draft, nil, nil, err
	}
	closing, err := optionalImportBalance(input.ClosingBalanceMinor)
	if err != nil {
		return draft, nil, nil, err
	}
	if len(input.Rows) == 0 || len(input.Rows) > ImportMaxRows {
		return draft, nil, nil, Invalid("Preview must contain between 1 and 2000 rows.")
	}
	seen := make(map[int]bool, len(input.Rows))
	rows := make([]model.StatementImportRow, 0, len(input.Rows))
	for _, raw := range input.Rows {
		if raw.RowID < 1 || raw.RowID > 2147483647 || seen[raw.RowID] {
			return draft, nil, nil, Invalid("Row identifiers must be positive, unique 32-bit integers.")
		}
		seen[raw.RowID] = true
		t, err := ValidateTransaction(id, raw.Kind, raw.AmountMinor, raw.Note, raw.OccurredAt)
		if err != nil {
			return draft, nil, nil, err
		}
		local := t.OccurredAt.In(location)
		if local.Year() < 1 || local.Year() > 9999 {
			return draft, nil, nil, Invalid("Statement local date must be between years 0001 and 9999.")
		}
		rows = append(rows, model.StatementImportRow{RowID: raw.RowID, OccurredAt: t.OccurredAt, LocalDay: local.Format(time.DateOnly), Kind: t.Kind, AmountMinor: t.Amount, Note: t.Note})
	}
	draft = model.StatementImport{AccountID: id, Bank: input.Bank, Currency: input.Currency, FileSHA256: input.FileSHA256, Timezone: input.Timezone, OpeningBalanceMinor: opening, ClosingBalanceMinor: closing, TotalCount: len(rows)}
	return draft, rows, location, nil
}

func validateImportSelection(input ImportCommitInput) ([]ImportSelection, error) {
	if len(input.Rows) < 1 || len(input.Rows) > ImportMaxRows {
		return nil, Invalid("Select between 1 and 2000 rows to commit.")
	}
	rows := append([]ImportSelection(nil), input.Rows...)
	sort.Slice(rows, func(i, j int) bool { return rows[i].RowID < rows[j].RowID })
	for i := range rows {
		if rows[i].RowID < 1 || rows[i].RowID > 2147483647 || i > 0 && rows[i].RowID == rows[i-1].RowID {
			return nil, Invalid("Selected row identifiers must be positive and unique.")
		}
		category, err := ParseOptionalID(rows[i].CategoryID)
		if err != nil {
			return nil, err
		}
		rows[i].CategoryID = category
	}
	return rows, nil
}
