package service

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
	"math"
	"time"
)

func lockedSchedule(tx *gorm.DB, userID, id string) (model.Schedule, error) {
	var s model.Schedule
	err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("id=? AND user_id=?", id, userID).First(&s).Error
	return s, planningNotFound(err)
}

func occurrenceRecord(tx *gorm.DB, userID string, s model.Schedule, date time.Time, expectedTemplate, expectedOccurrence int64) (model.PlannedOccurrence, error) {
	if err := checkVersion(s.Version, expectedTemplate); err != nil {
		return model.PlannedOccurrence{}, err
	}
	if expectedOccurrence < 0 {
		return model.PlannedOccurrence{}, Invalid("Expected_occurrence_version must be nonnegative.")
	}
	index := occurrenceIndex(s, date)
	if generated := occurrenceAt(s, index); generated.IsZero() || !generated.Equal(date) || (s.EndDate != nil && date.After(*s.EndDate)) {
		return model.PlannedOccurrence{}, &Error{404, "occurrence_not_found", "Date does not belong to this schedule."}
	}
	o := model.PlannedOccurrence{ScheduleID: s.ID, UserID: userID, DueDate: date, Status: "pending", CreatedAt: time.Now().UTC()}
	err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("schedule_id=? AND due_date=? AND user_id=?", s.ID, date, userID).First(&o).Error
	if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
		return o, err
	}
	if o.Version != expectedOccurrence || o.Version == math.MaxInt64 {
		return o, &Error{409, "version_conflict", "This occurrence changed. Refresh before trying again."}
	}
	if o.Status == "posted" {
		return o, &Error{409, "occurrence_posted", "This occurrence already has a ledger transaction. Restore it in history if deleted."}
	}
	return o, nil
}

func (s *Service) ChangeOccurrenceState(ctx context.Context, userID, id, dateRaw, key string, input OccurrenceInput, skip bool) ([]byte, error) {
	date, err := planningDate(dateRaw)
	if err != nil {
		return nil, err
	}
	action := "unskip"
	if skip {
		action = "skip"
	}
	return s.mutate(ctx, userID, key, "POST /api/v1/schedules/"+id+"/occurrences/"+dateRaw+"/"+action, input, func(tx *gorm.DB) (any, error) {
		schedule, err := lockedSchedule(tx, userID, id)
		if err != nil {
			return nil, err
		}
		o, err := occurrenceRecord(tx, userID, schedule, date, input.ExpectedTemplateVersion, input.ExpectedOccurrenceVersion)
		if err != nil {
			return nil, err
		}
		if skip && o.Status != "pending" || !skip && o.Status != "skipped" {
			return nil, &Error{409, "occurrence_state", "The occurrence is already in this state."}
		}
		blocked, err := scheduleBlocked(tx, userID, schedule)
		if err != nil {
			return nil, err
		}
		v := pendingOccurrence(schedule, date, blocked)
		o.Version++
		o.TemplateVersion = schedule.Version
		if skip {
			o.Status = "skipped"
		} else {
			o.Status = "pending"
		}
		v.Version, v.Status = o.Version, o.Status
		snapshot, _ := json.Marshal(v)
		o.Snapshot = string(snapshot)
		if err := tx.Save(&o).Error; err != nil {
			return nil, err
		}
		return v, nil
	})
}

func (s *Service) PostOccurrence(ctx context.Context, userID, id, dateRaw, key string, input PostOccurrenceInput) ([]byte, error) {
	date, err := planningDate(dateRaw)
	if err != nil {
		return nil, err
	}
	amount, err := ParseMinor(input.AmountMinor, true)
	if err != nil {
		return nil, err
	}
	categoryID, err := ParseOptionalID(input.CategoryID)
	if err != nil {
		return nil, err
	}
	return s.mutate(ctx, userID, key, "POST /api/v1/schedules/"+id+"/occurrences/"+dateRaw+"/post", input, func(tx *gorm.DB) (any, error) {
		schedule, err := lockedSchedule(tx, userID, id)
		if err != nil {
			return nil, err
		}
		o, err := occurrenceRecord(tx, userID, schedule, date, input.ExpectedTemplateVersion, input.ExpectedOccurrenceVersion)
		if err != nil {
			return nil, err
		}
		if o.Status != "pending" {
			return nil, &Error{409, "occurrence_state", "Unskip this occurrence before posting it."}
		}
		blocked, err := scheduleBlocked(tx, userID, schedule)
		if err != nil {
			return nil, err
		}
		if blocked != nil {
			return nil, &Error{409, *blocked, "Activate the schedule, account and category before posting."}
		}
		actual, err := ValidateTransaction(schedule.AccountID, schedule.Kind, input.AmountMinor, input.Note, input.OccurredAt)
		if err != nil {
			return nil, err
		}
		if err := validateCategoryAssignment(tx, userID, schedule.Kind, categoryID); err != nil {
			return nil, err
		}
		accounts, err := lockAccounts(tx, userID, []string{schedule.AccountID}, true)
		if err != nil {
			return nil, err
		}
		transaction := model.Transaction{ID: uuid.NewString(), UserID: userID, AccountID: schedule.AccountID, Kind: schedule.Kind, Currency: accounts[schedule.AccountID].Currency, AmountMinor: amount, CategoryID: categoryID, Note: actual.Note, OccurredAt: actual.OccurredAt, CreatedAt: s.planningNow().UTC().Truncate(time.Microsecond), Version: 1}
		if err := tx.Create(&transaction).Error; err != nil {
			return nil, err
		}
		if err := ensureAccountBalances(tx, accounts); err != nil {
			return nil, err
		}
		// Save an immutable due-date snapshot alongside the actual transaction link.
		// Edits/deletes to ledger history never reopen this unique due-date identity.
		v := pendingOccurrence(schedule, date, nil)
		v.AmountMinor, v.Note, v.CategoryID = input.AmountMinor, input.Note, categoryID
		v.Status, v.Version, v.TransactionID = "posted", o.Version+1, &transaction.ID
		o.Status, o.Version, o.TemplateVersion, o.TransactionID = v.Status, v.Version, schedule.Version, &transaction.ID
		snapshot, _ := json.Marshal(v)
		o.Snapshot = string(snapshot)
		if err := tx.Save(&o).Error; err != nil {
			return nil, err
		}
		return v, nil
	})
}
