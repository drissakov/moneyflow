package service

import (
	"context"
	"errors"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type CategoryView struct {
	ID        string  `json:"id"`
	Kind      string  `json:"kind"`
	Name      string  `json:"name"`
	ParentID  *string `json:"parent_id"`
	IsDefault bool    `json:"is_default"`
	Archived  bool    `json:"archived"`
	SortOrder int     `json:"sort_order"`
	Version   int64   `json:"version"`
}

func categoryView(c model.Category) CategoryView {
	return CategoryView{c.ID, c.Kind, c.Name, c.ParentID, c.IsDefault, c.Archived, c.SortOrder, c.Version}
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

func visibleCategories(tx *gorm.DB, userID string) ([]model.Category, error) {
	records := make([]model.Category, 0)
	err := tx.Raw(`SELECT c.id,c.user_id,c.kind,COALESCE(o.name,c.name) AS name,c.parent_id,c.is_default,c.created_at,
 COALESCE(o.archived,c.archived) AS archived,COALESCE(o.sort_order,c.sort_order) AS sort_order,COALESCE(o.version,c.version) AS version
 FROM categories c LEFT JOIN category_overrides o ON o.category_id=c.id AND o.user_id=?
 WHERE c.user_id IS NULL OR c.user_id=? ORDER BY c.kind,COALESCE(o.sort_order,c.sort_order),c.created_at,c.id`, userID, userID).Scan(&records).Error
	return records, err
}

func (s *Service) Categories(ctx context.Context, userID string) ([]CategoryView, error) {
	records, err := visibleCategories(s.DB.WithContext(ctx), userID)
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
	err := tx.Where("id=? AND (user_id IS NULL OR user_id=?)", id, userID).First(&c).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return c, &Error{404, "category_not_found", "Category was not found."}
	}
	if err != nil {
		return c, err
	}
	if c.IsDefault {
		var override model.CategoryOverride
		err := tx.Where("user_id=? AND category_id=?", userID, id).First(&override).Error
		if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
			return c, err
		}
		if err == nil {
			c.Name, c.Archived, c.SortOrder, c.Version = override.Name, override.Archived, override.SortOrder, override.Version
		}
	}
	return c, nil
}

func categoryActive(tx *gorm.DB, userID string, c model.Category) error {
	if c.Archived {
		return &Error{409, "archived_category", "Choose an active category or unarchive it first."}
	}
	if c.ParentID != nil {
		parent, err := findVisibleCategory(tx, userID, *c.ParentID)
		if err != nil {
			return err
		}
		if parent.Archived {
			return &Error{409, "archived_category", "Unarchive the parent category first."}
		}
	}
	return nil
}

func validateCategoryName(tx *gorm.DB, userID string, c model.Category) error {
	records, err := visibleCategories(tx, userID)
	if err != nil {
		return err
	}
	for _, sibling := range records {
		if sibling.ID != c.ID && !sibling.Archived && sibling.Kind == c.Kind && categorySortID(sibling.ParentID) == categorySortID(c.ParentID) && strings.EqualFold(sibling.Name, c.Name) {
			return &Error{409, "category_exists", "A category with this name already exists under this parent."}
		}
	}
	return nil
}

func createCategoryTx(tx *gorm.DB, userID, kind, name string, parentID *string) (CategoryView, error) {
	if parentID != nil {
		parent, err := findVisibleCategory(tx, userID, *parentID)
		if err != nil {
			return CategoryView{}, err
		}
		if parent.Kind != "expense" || parent.ParentID != nil {
			return CategoryView{}, Invalid("An expense subcategory must belong to a root expense category.")
		}
		if err := categoryActive(tx, userID, parent); err != nil {
			return CategoryView{}, err
		}
	}
	c := model.Category{ID: uuid.NewString(), UserID: &userID, Kind: kind, Name: name, ParentID: parentID, Version: 1, CreatedAt: time.Now().UTC().Truncate(time.Microsecond)}
	if err := validateCategoryName(tx, userID, c); err != nil {
		return CategoryView{}, err
	}
	if err := tx.Create(&c).Error; err != nil {
		return CategoryView{}, err
	}
	return categoryView(c), nil
}

func (s *Service) CreateCategory(ctx context.Context, userID, kind, name string, parentID *string) (CategoryView, error) {
	name, parentID, err := ValidateCategory(kind, name, parentID)
	if err != nil {
		return CategoryView{}, err
	}
	var view CategoryView
	err = s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := lockLedger(tx, userID); err != nil {
			return err
		}
		var err error
		view, err = createCategoryTx(tx, userID, kind, name, parentID)
		return err
	})
	return view, err
}

func (s *Service) CreateCategoryIdempotent(ctx context.Context, userID, key, kind, name string, parentID *string) ([]byte, error) {
	normalized, parent, err := ValidateCategory(kind, name, parentID)
	if err != nil {
		return nil, err
	}
	body := struct {
		Kind     string  `json:"kind"`
		Name     string  `json:"name"`
		ParentID *string `json:"parent_id"`
	}{kind, normalized, parent}
	return s.mutate(ctx, userID, key, "POST /api/v1/categories", body, func(tx *gorm.DB) (any, error) { return createCategoryTx(tx, userID, kind, normalized, parent) })
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
	return categoryActive(tx, userID, c)
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
		if err := lockLedger(tx, userID); err != nil {
			return err
		}
		t, err := lockedTransaction(tx, userID, transactionID)
		if err != nil {
			return err
		}
		if err := requireIndependentTransaction(t); err != nil {
			return err
		}
		if t.DeletedAt != nil {
			return &Error{409, "transaction_deleted", "Restore this transaction before editing it."}
		}
		if err := validateCategoryAssignment(tx, userID, t.Kind, categoryID); err != nil {
			return err
		}
		t.CategoryID = categoryID
		t.Version++
		if err := tx.Save(&t).Error; err != nil {
			return err
		}
		view, err = enrichedTransactionView(tx, t)
		return err
	})
	return view, err
}
