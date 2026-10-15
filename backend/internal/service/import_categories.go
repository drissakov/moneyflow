package service

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"regexp"
	"strings"
	"unicode"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"golang.org/x/text/cases"
	"golang.org/x/text/unicode/norm"
	"gorm.io/gorm"
)

const importCategoryHistoryLimit = 5000

// Suggestions are advisory. Commit always receives the user's explicit category
// selection and revalidates ownership, kind and effective archive state.
type importCategorySuggestion struct {
	CategoryID *string
	Source     *string
	MatchKey   *string
}

type importCategoryCandidate struct {
	identity       string
	ruleCategoryID string
}

type importCategoryHistoryRow struct {
	Note       string
	Kind       string
	CategoryID string
}

type merchantCategoryRule struct {
	merchant string
	aliases  []string
	category string
}

func expenseCategory(root int) string     { return fmt.Sprintf("10000000-0000-4000-8000-%012d", root) }
func expenseSubcategory(child int) string { return fmt.Sprintf("30000000-0000-4000-8000-%012d", child) }
func incomeCategory(root int) string      { return fmt.Sprintf("20000000-0000-4000-8000-%012d", root) }

// These public merchant aliases are deliberately narrow. A bank name, a payment
// processor or a broad delivery aggregator does not identify what was bought.
var importMerchantRules = []merchantCategoryRule{
	{"magnum", []string{"magnum", "магнум"}, expenseSubcategory(1)},
	{"small", []string{"small", "смолл"}, expenseSubcategory(1)},
	{"airba_fresh", []string{"airba fresh", "арба фреш"}, expenseSubcategory(1)},
	{"arbuz", []string{"arbuz kz", "arbuz", "арбуз кз"}, expenseSubcategory(1)},
	{"coffee_boom", []string{"coffee boom", "coffeeboom", "кофе бум", "кофебум"}, expenseSubcategory(3)},
	{"starbucks", []string{"starbucks", "старбакс"}, expenseSubcategory(3)},
	{"costa_coffee", []string{"costa coffee"}, expenseSubcategory(3)},
	{"kfc", []string{"kfc"}, expenseSubcategory(2)},
	{"burger_king", []string{"burger king", "бургер кинг"}, expenseSubcategory(2)},
	{"yandex_taxi", []string{"yandex taxi", "яндекс такси"}, expenseSubcategory(9)},
	{"in_drive", []string{"indrive", "in drive", "индрайв"}, expenseSubcategory(9)},
	{"qazaq_oil", []string{"qazaq oil", "qazaqoil"}, expenseSubcategory(10)},
	{"helios", []string{"helios azs", "гелиос азс", "азс гелиос"}, expenseSubcategory(10)},
	{"sinooil", []string{"sinooil", "sino oil", "синойл"}, expenseSubcategory(10)},
	{"sulpak", []string{"sulpak", "сулпак"}, expenseSubcategory(13)},
	{"technodom", []string{"technodom", "технодом"}, expenseSubcategory(13)},
	{"mechta", []string{"mechta kz", "мечта кз"}, expenseSubcategory(13)},
	{"kaspi_shop", []string{"kaspi magazin", "kaspi магазин", "каспи магазин"}, expenseCategory(4)},
	{"wildberries", []string{"wildberries", "вайлдберриз"}, expenseCategory(4)},
	{"ozon", []string{"ozon", "озон"}, expenseCategory(4)},
	{"biosfera", []string{"аптека биосфера", "biosfera apteka"}, expenseSubcategory(16)},
	{"europharma", []string{"europharma", "еврофарма"}, expenseSubcategory(16)},
	{"netflix", []string{"netflix"}, expenseSubcategory(22)},
	{"spotify", []string{"spotify"}, expenseSubcategory(22)},
}

var (
	importMaskedCard     = regexp.MustCompile(`(?i)(?:\*{2,}|x{2,}|•{2,})[ -]*[0-9]{2,8}`)
	importReference      = regexp.MustCompile(`(?i)(?:^|[\s:;])(?:ref|rrn|auth|authorization|order|terminal|check|чек|терминал|заказ|операция|код)(?:[ :=#-]+[a-z0-9-]{3,}|[0-9]{3,})`)
	importIBAN           = regexp.MustCompile(`(?i)\bKZ[0-9A-Z]{18}\b`)
	importLongNumber     = regexp.MustCompile(`[0-9]{6,}`)
	importNumericDate    = regexp.MustCompile(`\b[0-9]{1,2}[./-][0-9]{1,2}[./-][0-9]{2,4}\b`)
	importNumericTime    = regexp.MustCompile(`\b[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?\b`)
	importDecimalAmount  = regexp.MustCompile(`(?i)[+-]?[0-9][0-9 \x{00a0}\x{202f}]*[.,][0-9]{2}(?:[ ]*(?:kzt|usd|eur|₸))?`)
	importCurrencyAmount = regexp.MustCompile(`(?i)[0-9][0-9 ]*[ ]*(?:kzt|usd|eur|₸)(?:[ ]|$)`)
)

func normalizedImportDescription(note string) string {
	// Never use amounts, dates, account identifiers or personal PDF fields as
	// dictionary entries. Remove recognizable volatile references in bank notes.
	note = cases.Fold().String(norm.NFKC.String(note))
	note = strings.ReplaceAll(note, "ё", "е")
	note = importMaskedCard.ReplaceAllString(note, " ")
	note = importIBAN.ReplaceAllString(note, " ")
	note = importReference.ReplaceAllString(note, " ")
	note = importNumericDate.ReplaceAllString(note, " ")
	note = importNumericTime.ReplaceAllString(note, " ")
	note = importDecimalAmount.ReplaceAllString(note, " ")
	note = importCurrencyAmount.ReplaceAllString(note, " ")
	note = importLongNumber.ReplaceAllString(note, " ")
	var out strings.Builder
	for _, r := range note {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			out.WriteRune(r)
		} else {
			out.WriteByte(' ')
		}
	}
	return strings.Join(strings.Fields(out.String()), " ")
}

func descriptionHas(text string, phrases ...string) bool {
	haystack := " " + text + " "
	for _, phrase := range phrases {
		if strings.Contains(haystack, " "+phrase+" ") {
			return true
		}
	}
	return false
}

func importMovementDescription(text string) bool {
	return descriptionHas(text, "перевод", "переводы", "аударым", "аудару", "аударымы", "transfer", "p2p", "снятие", "снятия", "наличных", "наличные", "қолма қол", "cash withdrawal", "между своими", "на свой счет", "со своего счета") ||
		(descriptionHas(text, "пополнение", "зачисление", "перечисление", "top up", "толықтыру") && !descriptionHas(text, "заработная плата", "заработной платы", "зарплата", "жалақы", "пенсия", "зейнетақы", "возврат покупки", "возврат за покупку", "возврат оплаты", "возврат средств", "refund", "қайтарым", "сатып алуды қайтару", "проценты по депозиту", "проценты по вкладу", "вознаграждение по депозиту", "вознаграждение по вкладу", "депозит бойынша сыйақы", "deposit interest"))
}

func importFeeDescription(text string) bool {
	return strings.HasPrefix(text, "комиссия ") || text == "комиссия" || strings.HasPrefix(text, "commission ") || strings.HasPrefix(text, "bank fee ") ||
		descriptionHas(text, "банковская комиссия", "комиссия банка", "банк комиссиясы")
}

func classifyImportCategory(note, kind string) importCategoryCandidate {
	text := normalizedImportDescription(note)
	if text == "" {
		return importCategoryCandidate{}
	}
	// Halyk emits a separate fee row as "Комиссия: <original note>". It must
	// remain an expense even when the original row describes a transfer/refund.
	if importFeeDescription(text) {
		if kind == "expense" {
			return importCategoryCandidate{ruleCategoryID: expenseSubcategory(37)}
		}
		return importCategoryCandidate{}
	}
	if importMovementDescription(text) {
		return importCategoryCandidate{}
	}
	if kind == "income" {
		switch {
		case descriptionHas(text, "возврат покупки", "возврат за покупку", "возврат оплаты", "возврат средств", "refund", "қайтарым", "сатып алуды қайтару", "кэшбэк", "cashback"):
			return incomeImportCandidate(text, "refund", incomeCategory(9))
		case descriptionHas(text, "заработная плата", "заработной платы", "зарплата", "жалақы", "payroll", "salary"):
			return incomeImportCandidate(text, "salary", incomeCategory(1))
		case descriptionHas(text, "проценты по депозиту", "проценты по вкладу", "вознаграждение по депозиту", "вознаграждение по вкладу", "депозит бойынша сыйақы", "deposit interest"):
			return incomeImportCandidate(text, "interest", incomeCategory(4))
		case descriptionHas(text, "пенсия", "зейнетақы", "пособие", "жәрдемақы"):
			return incomeImportCandidate(text, "pension", incomeCategory(8))
		}
		// Generic credits and merchant names do not explain the source of income.
		return importCategoryCandidate{}
	}
	if kind != "expense" {
		return importCategoryCandidate{}
	}
	// A reversal is not a new purchase, and cannot be assigned an expense
	// category just because the description happens to contain a known merchant.
	if descriptionHas(text, "возврат", "отмена", "refund", "reversal", "қайтару", "қайтарым") {
		return importCategoryCandidate{}
	}
	var merchant *merchantCategoryRule
	for i := range importMerchantRules {
		rule := &importMerchantRules[i]
		if descriptionHas(text, rule.aliases...) {
			if merchant != nil && merchant.merchant != rule.merchant {
				return importCategoryCandidate{}
			}
			merchant = rule
		}
	}
	if merchant != nil {
		return importCategoryCandidate{identity: "merchant:" + merchant.merchant, ruleCategoryID: merchant.category}
	}
	// Keyword rules are strong phrases or exact business terms, never substring
	// matches ("internet" should not accidentally match an unrelated merchant).
	var category string
	choose := func(match bool, id string) bool {
		if !match {
			return true
		}
		if category != "" && category != id {
			return false
		}
		category = id
		return true
	}
	if !choose(descriptionHas(text, "кофе", "кофейня", "coffee", "coffeehouse", "кофехана"), expenseSubcategory(3)) ||
		!choose(descriptionHas(text, "кафе", "ресторан", "restaurant", "асхана", "мейрамхана"), expenseSubcategory(2)) ||
		!choose(descriptionHas(text, "супермаркет", "supermarket", "продукты", "азық түлік"), expenseSubcategory(1)) ||
		!choose(descriptionHas(text, "такси", "taxi"), expenseSubcategory(9)) ||
		!choose(descriptionHas(text, "азс", "бензин", "жанармай"), expenseSubcategory(10)) ||
		!choose(descriptionHas(text, "аптека", "pharmacy", "дәріхана"), expenseSubcategory(16)) ||
		!choose(descriptionHas(text, "коммунальные услуги", "коммуналдық қызметтер", "оплата электроэнергии"), expenseSubcategory(6)) ||
		!choose(descriptionHas(text, "домашний интернет", "үй интернеті"), expenseSubcategory(28)) ||
		!choose(descriptionHas(text, "мобильная связь", "ұялы байланыс"), expenseSubcategory(27)) {
		return importCategoryCandidate{}
	}
	identity := unknownImportMerchant(text)
	return importCategoryCandidate{identity: identity, ruleCategoryID: category}
}

func incomeImportCandidate(text, semantic, category string) importCategoryCandidate {
	identity := ""
	for _, rule := range importMerchantRules {
		if descriptionHas(text, rule.aliases...) {
			if identity != "" && identity != "merchant:"+rule.merchant {
				return importCategoryCandidate{ruleCategoryID: category}
			}
			identity = "merchant:" + rule.merchant
		}
	}
	if identity == "" {
		identity = unknownImportMerchant(text)
	}
	if identity != "" {
		identity = semantic + ":" + identity
	}
	return importCategoryCandidate{identity: identity, ruleCategoryID: category}
}

func unknownImportMerchant(text string) string {
	// Only explicit business identifiers can teach custom categories. Bare
	// personal names and generic "Покупка/Оплата" must never share a batch key.
	if !descriptionHas(text, "ип", "тоо", "жшс", "llp", "ltd", "магазин", "store", "market", "кафе", "cafe", "restaurant", "кофейня", "coffeehouse", "coffee", "аптека", "pharmacy", "дәріхана", "супермаркет") {
		return ""
	}
	tokens := strings.Fields(text)
	for len(tokens) > 0 && descriptionHas(tokens[0], "покупка", "оплата", "pos", "purchase", "payment", "төлем", "сатып") {
		tokens = tokens[1:]
	}
	if len(tokens) < 2 {
		return ""
	}
	meaningful := false
	for _, token := range tokens {
		if descriptionHas(token, "ип", "тоо", "жшс", "llp", "ltd", "магазин", "store", "market", "кафе", "cafe", "restaurant", "кофейня", "coffeehouse", "coffee", "аптека", "pharmacy", "дәріхана", "супермаркет", "алу", "оплата", "покупка") {
			continue
		}
		letters := 0
		for _, r := range token {
			if unicode.IsLetter(r) {
				letters++
			}
		}
		if letters >= 3 {
			meaningful = true
		}
	}
	if !meaningful {
		return ""
	}
	return "description:" + strings.Join(tokens, " ")
}

func importCategoryMatchKey(userID, bank, kind, identity string) *string {
	if identity == "" {
		return nil
	}
	hash := sha256.Sum256([]byte("import-category-v1\n" + userID + "\n" + bank + "\n" + kind + "\n" + identity))
	value := "v1:" + hex.EncodeToString(hash[:])
	return &value
}

func suggestImportCategories(tx *gorm.DB, userID, bank string, rows []model.StatementImportRow) (map[int]importCategorySuggestion, error) {
	result := make(map[int]importCategorySuggestion, len(rows))
	categories, err := visibleCategories(tx, userID)
	if err != nil {
		return nil, err
	}
	byID := make(map[string]model.Category, len(categories))
	for _, c := range categories {
		byID[c.ID] = c
	}
	active := func(id, kind string) bool {
		c, ok := byID[id]
		if !ok || c.Kind != kind || c.Archived {
			return false
		}
		if c.ParentID != nil {
			p, ok := byID[*c.ParentID]
			if !ok || p.Archived {
				return false
			}
		}
		return true
	}
	candidates := make(map[int]importCategoryCandidate, len(rows))
	wanted := map[string]bool{}
	for _, row := range rows {
		c := classifyImportCategory(row.Note, row.Kind)
		candidates[row.RowID] = c
		if c.identity != "" {
			wanted[row.Kind+"\n"+c.identity] = true
		}
	}
	history := make([]importCategoryHistoryRow, 0)
	if len(wanted) > 0 {
		// A single bounded scan replaces per-row lookups. The existing user history
		// index supplies this order. Unknown-bank manual notes are not assumed to be
		// Kaspi/Halyk merchant evidence. No original PDFs or additional PII are read.
		err = tx.Raw(`SELECT t.note,t.kind,t.category_id FROM transactions t
   JOIN transaction_import_sources src ON src.transaction_id=t.id AND src.user_id=t.user_id
   JOIN statement_imports i ON i.id=src.import_id AND i.user_id=t.user_id
   WHERE t.user_id=? AND i.bank=? AND t.deleted_at IS NULL AND t.transfer_id IS NULL
   AND NOT t.exclude_from_analytics AND t.category_id IS NOT NULL
   ORDER BY t.occurred_at DESC,t.created_at DESC,t.id DESC LIMIT ?`, userID, bank, importCategoryHistoryLimit).Scan(&history).Error
		if err != nil {
			return nil, err
		}
	}
	assigned := map[string]string{}
	conflict := map[string]bool{}
	for _, h := range history {
		if !active(h.CategoryID, h.Kind) {
			continue
		}
		c := classifyImportCategory(h.Note, h.Kind)
		if c.identity == "" {
			continue
		}
		key := h.Kind + "\n" + c.identity
		if !wanted[key] {
			continue
		}
		if previous, ok := assigned[key]; ok && previous != h.CategoryID {
			conflict[key] = true
		} else {
			assigned[key] = h.CategoryID
		}
	}
	for _, row := range rows {
		c := candidates[row.RowID]
		view := importCategorySuggestion{MatchKey: importCategoryMatchKey(userID, bank, row.Kind, c.identity)}
		key := row.Kind + "\n" + c.identity
		if c.identity != "" && conflict[key] {
			result[row.RowID] = view
			continue
		}
		if id := assigned[key]; c.identity != "" && id != "" {
			source := "history"
			view.CategoryID = &id
			view.Source = &source
		} else if c.ruleCategoryID != "" && active(c.ruleCategoryID, row.Kind) {
			id := c.ruleCategoryID
			source := "rule"
			view.CategoryID = &id
			view.Source = &source
		}
		result[row.RowID] = view
	}
	return result, nil
}
