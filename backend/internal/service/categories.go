package service

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type CategoryView struct {
	ID        string  `json:"id"`
	Kind      string  `json:"kind"`
	Name      string  `json:"name"`
	ParentID  *string `json:"parent_id"`
	IsDefault bool    `json:"is_default"`
}

func categoryView(c model.Category) CategoryView {
	return CategoryView{ID: c.ID, Kind: c.Kind, Name: c.Name, ParentID: c.ParentID, IsDefault: c.IsDefault}
}

func ParseOptionalID(raw *string) (*string, error) {
	if raw == nil {
		return nil, nil
	}
	id, err := ParseID(*raw)
	if err != nil {
		return nil, err
	}
	return &id, nil
}

func ValidateCategory(kind, name string, parentID *string) (string, *string, error) {
	if kind != "expense" && kind != "income" {
		return "", nil, Invalid("Kind must be expense or income.")
	}
	if !utf8.ValidString(name) {
		return "", nil, Invalid("Category name must contain 1 to 100 characters.")
	}
	for _, r := range name {
		if unicode.IsControl(r) && !unicode.IsSpace(r) {
			return "", nil, Invalid("Category name must not contain control characters.")
		}
	}
	name = strings.Join(strings.Fields(name), " ")
	if utf8.RuneCountInString(name) < 1 || utf8.RuneCountInString(name) > 100 {
		return "", nil, Invalid("Category name must contain 1 to 100 characters.")
	}
	parentID, err := ParseOptionalID(parentID)
	if err != nil {
		return "", nil, err
	}
	if kind == "income" && parentID != nil {
		return "", nil, Invalid("Income categories cannot have a parent.")
	}
	return name, parentID, nil
}

func (s *Service) Categories(ctx context.Context, userID string) ([]CategoryView, error) {
	records := make([]model.Category, 0)
	err := s.DB.WithContext(ctx).Where("user_id IS NULL OR user_id = ?", userID).
		Order("kind, is_default DESC, created_at, id").Find(&records).Error
	if err != nil {
		return nil, err
	}
	views := make([]CategoryView, 0, len(records))
	for _, c := range records {
		views = append(views, categoryView(c))
	}
	return views, nil
}

func findVisibleCategory(tx *gorm.DB, userID, id string) (model.Category, error) {
	var c model.Category
	err := tx.Where("id = ? AND (user_id IS NULL OR user_id = ?)", id, userID).First(&c).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return c, &Error{404, "category_not_found", "Category was not found."}
	}
	return c, err
}

func (s *Service) CreateCategory(ctx context.Context, userID, kind, name string, parentID *string) (CategoryView, error) {
	var view CategoryView
	name, parentID, err := ValidateCategory(kind, name, parentID)
	if err != nil {
		return view, err
	}
	lockHash := sha256.Sum256([]byte("categories:" + userID))
	err = s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		// Serialize a user's category creation so duplicate checks include other
		// concurrent requests. Shared defaults are immutable.
		if err := tx.Exec("SELECT pg_advisory_xact_lock(?)", int64(binary.BigEndian.Uint64(lockHash[:8]))).Error; err != nil {
			return err
		}
		if parentID != nil {
			parent, err := findVisibleCategory(tx, userID, *parentID)
			if err != nil {
				return err
			}
			if parent.Kind != "expense" || parent.ParentID != nil {
				return Invalid("An expense subcategory must belong to a root expense category.")
			}
		}
		var siblings []model.Category
		if err := tx.Model(&model.Category{}).
			Select("name").Where("(user_id IS NULL OR user_id = ?) AND kind = ? AND parent_id IS NOT DISTINCT FROM ?::uuid", userID, kind, parentID).
			Find(&siblings).Error; err != nil {
			return err
		}
		// PostgreSQL lower() depends on the server's locale: Windows test databases
		// using the C locale do not fold Cyrillic. EqualFold is Unicode aware.
		for _, sibling := range siblings {
			if strings.EqualFold(sibling.Name, name) {
				return &Error{409, "category_exists", "A category with this name already exists under this parent."}
			}
		}
		c := model.Category{ID: uuid.NewString(), UserID: &userID, Kind: kind, Name: name, ParentID: parentID,
			CreatedAt: time.Now().UTC().Truncate(time.Microsecond)}
		if err := tx.Create(&c).Error; err != nil {
			return err
		}
		view = categoryView(c)
		return nil
	})
	return view, err
}

func validateCategoryAssignment(tx *gorm.DB, userID, kind string, id *string) error {
	if id == nil {
		return nil
	}
	c, err := findVisibleCategory(tx, userID, *id)
	if err != nil {
		return err
	}
	if c.Kind != kind {
		return Invalid("Category kind must match transaction kind.")
	}
	return nil
}

func (s *Service) SetTransactionCategory(ctx context.Context, userID, transactionID string, categoryID *string) (TransactionView, error) {
	var view TransactionView
	transactionID, err := ParseID(transactionID)
	if err != nil {
		return view, err
	}
	categoryID, err = ParseOptionalID(categoryID)
	if err != nil {
		return view, err
	}
	err = s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var t model.Transaction
		err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("id = ? AND user_id = ?", transactionID, userID).First(&t).Error
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return &Error{404, "transaction_not_found", "Transaction was not found."}
		}
		if err != nil {
			return err
		}
		if err := validateCategoryAssignment(tx, userID, t.Kind, categoryID); err != nil {
			return err
		}
		if err := tx.Model(&t).Update("category_id", categoryID).Error; err != nil {
			return err
		}
		t.CategoryID = categoryID
		view = transactionView(t)
		return nil
	})
	return view, err
}
