package service

import (
	"strings"
	"testing"
)

func TestImportValidationUsesBankLocalDateAndCanonicalBalances(t *testing.T) {
	opening := "-9223372036854775808"
	input := ImportPreviewInput{AccountID: "8094b783-7d58-4c50-9b04-922f56fcc21d", Bank: "halyk", Currency: "KZT", FileSHA256: strings.Repeat("a", 64), Timezone: "Asia/Qyzylorda", OpeningBalanceMinor: &opening,
		Rows: []ImportRowInput{{RowID: 1, OccurredAt: "2026-01-01T00:00:00+05:00", Kind: "expense", AmountMinor: "1", Note: "Synthetic"}}}
	draft, rows, _, err := validateImportPreview(input)
	if err != nil || draft.OpeningBalanceMinor == nil || *draft.OpeningBalanceMinor != -9223372036854775808 || rows[0].LocalDay != "2026-01-01" || rows[0].OccurredAt.Format("2006-01-02") != "2025-12-31" {
		t.Fatal("bank local date or exact summary amount changed")
	}
	for _, balance := range []string{"01", "-0", "+1", "1.2", "9223372036854775808"} {
		input.OpeningBalanceMinor = &balance
		if _, _, _, err := validateImportPreview(input); err == nil {
			t.Fatal("invalid summary accepted")
		}
	}
	selection, err := validateImportSelection(ImportCommitInput{Rows: []ImportSelection{{RowID: 2}, {RowID: 1, ExcludeFromAnalytics: true}}})
	if err != nil || selection[0].RowID != 1 || !selection[0].ExcludeFromAnalytics {
		t.Fatal("canonical selection lost flags or sorting")
	}
}
