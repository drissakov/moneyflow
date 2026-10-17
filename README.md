<div align="center">

<img src="docs/assets/moneyflow-banner.svg" alt="MoneyFlow — Android + Go + PostgreSQL" width="100%">

# 💸 MoneyFlow

**Личные финансы в Android-приложении с собственным сервером.**<br>
Счета, доходы, расходы, общий баланс и конвертер валют — Kotlin / Jetpack Compose + Go / PostgreSQL + Frankfurter.

[![CI](https://github.com/drissakov/moneyflow/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/drissakov/moneyflow/actions/workflows/ci.yml) [![Go](https://img.shields.io/badge/Go-1.25%2B-00ADD8?logo=go&logoColor=white)](backend/go.mod) [![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7F52FF?logo=kotlin&logoColor=white)](android/build.gradle.kts) [![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](android/app/build.gradle.kts) [![Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=jetpackcompose&logoColor=white)](android/app/src/main/java/com/moneyflow/app/ui/MoneyFlowApp.kt) [![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)](infra/compose.yaml) [![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](infra/compose.yaml) [![Status](https://img.shields.io/badge/Статус-MVP-F59E0B)](#features)

[🚀 Быстрый старт](#quick-start) · [📱 Android](#android) · [📄 Импорт выписки](#statement-import) · [🔌 API](#api) · [🌐 Публичный сервер](#deployment) · [🛠 Решение проблем](#troubleshooting)

<img src="docs/assets/android-dashboard.png" alt="MoneyFlow: чёрно-белый интерфейс в светлой теме" width="280">
<img src="docs/assets/android-dashboard-dark.png" alt="MoneyFlow: чёрно-белый интерфейс в тёмной теме" width="280">
<img src="docs/assets/android-category-picker.png" alt="MoneyFlow: выбор категории с поиском и подкатегориями" width="280">

<details>
<summary>Журнал операций и переводы между своими счетами</summary>

<img src="docs/assets/android-ledger.png" alt="MoneyFlow: полный журнал с поиском и фильтрами" width="280">
<img src="docs/assets/android-transfer.png" alt="MoneyFlow: перевод KZT → USD с отдельным счётом комиссии" width="280">

</details>

</div>

<a id="overview"></a>
## 🟣 О проекте

MoneyFlow — pet-проект для учета личных финансов с русским Android-интерфейсом. Сервер принимает операции, проверяет владельца данных и сохраняет их в PostgreSQL. Приложение отображает локальный кэш Room и обновляет его после ответа сервера.

Версия **0.5.0** добавляет первый блок P1: бюджеты, регулярные платежи, календарь и показатель «Можно потратить». Плановые события становятся операциями только после подтверждения; напоминания включаются по желанию. Доступны полный журнал с поиском и фильтрами, редактирование, удаление и восстановление операций, управление счетами и категориями, а также переводы между своими счетами в разных валютах с комиссиями. Работают регистрация, PDF-импорт Kaspi/Halyk с автоматическими категориями, месячная аналитика и общий баланс в основной валюте. Конвертер использует ежедневные курсы Frankfurter; исходные суммы операций сохраняются в валюте счета. Ниже описаны запуск на компьютере, подключение телефона, API, проверки и подготовка сервера на VPS.

Интерфейс выполнен в чёрно-белой палитре и следует светлой или тёмной теме Android. Главная карточка показывает **общий баланс всех счетов**, по умолчанию в тенге. У каждого счёта видны исходный остаток и оценка в валюте итога. Нажмите «Как рассчитан баланс», чтобы посмотреть слагаемые и дату курсов. Выбор счёта меняет список операций, но не общий итог. Отрицательные остатки также участвуют в сумме; недоступный курс явно помечает итог как неполный.

Шрифты **Inter / Inter Display** встроены в APK и работают без интернета; проверены русские и казахские буквы, а также знак ₸. Выбор категорий открывается в выдвижной панели с поиском, разделами стандартных и своих категорий и раскрываемыми подкатегориями. Серые сегменты диаграмм обозначены номерами, которые повторяются в легенде с суммами и долями категорий. Источники и лицензия шрифтов — в [`assets/fonts`](android/app/src/main/assets/fonts/README.md).

### Навигация

| Начало работы | Устройство проекта | Эксплуатация |
|---|---|---|
| [Возможности](#features) | [Архитектура и стек](#architecture) | [Проверки и CI](#testing) |
| [Требования](#requirements) | [Структура](#structure) | [Миграции](#migrations) |
| [Docker Compose](#quick-start) | [Настройки](#configuration) | [Бесплатный сервер / VPS / release](#deployment) |
| [Go + PostgreSQL без Docker](#native-server) | [API и примеры](#api) | [Резервные копии](#backups) |
| [Android Studio / CLI / телефон](#android) | [Деньги, сессии, офлайн](#data-rules) | [Проблемы](#troubleshooting) · [План](#roadmap) |

<a id="features"></a>
## 🟢 Что уже работает

| Возможность | Состояние |
|---|---|
| Чёрно-белый интерфейс | ✅ Светлая и тёмная темы, пронумерованные диаграммы |
| Регистрация, вход по email / паролю, выход | ✅ Серверные сессии с отзывом и сроком действия |
| Управление счетами | ✅ Название, тип, начальный остаток, порядок, архив и включение в общий баланс |
| Полная история | ✅ Все счета, поиск по заметке, фильтры по периоду, категории и типу, курсорная загрузка |
| Изменение операций | ✅ Сумма, дата и время, счёт, категория; удаление и восстановление |
| Переводы между своими счетами | ✅ Две фактические суммы, разные валюты, комиссия с выбранного счёта, изменение целиком |
| Доходы и расходы | ✅ Сумма, заметка, дата, категория |
| Категории расходов | ✅ 14 стандартных групп, 41 подкатегория и свои варианты |
| Категории доходов | ✅ 10 стандартных категорий и свои варианты |
| Управление категориями | ✅ Переименование, порядок, архив, восстановление и объединение; шаблоны настраиваются отдельно для каждого пользователя |
| Месячная аналитика | ✅ Круговая диаграмма, суммы и доли категорий; детализация расходов |
| Бюджеты | ✅ Лимит, факт, остаток и превышение; свой день начала месячного периода |
| Регулярные платежи | ✅ Календарь, повторы, подтверждение, пропуск, пауза и архив |
| «Можно потратить» | ✅ Выбранные счета, резерв и обязательные платежи; конвертация в валюту планирования |
| Напоминания | ✅ По желанию, с разрешением Android; без сумм и названий счетов в уведомлении |
| PDF-выписки Kaspi Gold и Halyk | ✅ Локальный разбор русских текстовых PDF в KZT, предпросмотр и выбор строк |
| Автоматические категории импорта | ✅ По описанию магазина/услуги и прежним назначениям в своих импортах; фильтр неизвестных и исправление похожих строк одним действием |
| Повторный импорт | ✅ Проверка всей истории, точные и возможные дубли, безопасный повтор сохранения |
| История и отмена импорта | ✅ Удаление только операций выбранного импорта |
| Движение денег | ✅ Явная пометка собственных переводов: баланс учитывается, диаграммы исключают строку |
| Баланс счета | ✅ Начальный баланс + вся подтвержденная история |
| 11 валют | ✅ KZT, USD, EUR, JPY, KWD, RUB, GBP, CHF, AUD, CAD, CNY |
| Основная валюта пользователя | ✅ Сохраняется на сервере, по умолчанию KZT |
| Общий баланс и конвертер | ✅ Frankfurter v2, точное округление, дата курса |
| Недоступные / старые курсы | ✅ Ограниченный кэш и явно неполный итог |
| Повтор после неопределенного ответа сети | ✅ Сохраненный запрос и ключ идемпотентности |
| Просмотр загруженных данных без сети | ✅ Пока локальная сессия не истекла |
| Разделение пользователей | ✅ Проверки сервера и отдельный локальный кэш |
| SQL-миграции, Docker, конфигурация HTTPS | ✅ В репозитории |
| GitHub Actions | ✅ Backend / PostgreSQL и Android build / lint / tests |
| Публичный API | ✅ [Vercel Hobby + Neon Free](https://moneyflow-api-two.vercel.app/healthz), HTTPS и постоянная PostgreSQL |
| Внешний мониторинг | ✅ Каждые 6 часов: HTTPS, сертификат, API, БД и свежесть backup |
| Зашифрованные backup | ✅ Ежедневно, 7 копий за 7 дней, age и проверка восстановления |

**Границы версии:** аналитика и бюджеты показывают операции в выбранной исходной валюте: KZT и USD смотрятся отдельно. Общий баланс и прогноз используют ежедневные курсы Frankfurter; при записи перевода указываются фактические суммы списания и зачисления. Запись перевода не отправляет деньги в банк. Связывание уже импортированных строк двух банков в один перевод, расширенная аналитика, подтверждение email и восстановление пароля остаются следующими этапами.

При неопределённом ответе сети приложение сохраняет точное тело изменения и ключ идемпотентности в зашифрованном хранилище. Повтор доступен после перезапуска. Перед следующей записью нужно завершить неподтверждённое изменение. Полноценной фоновой очереди офлайн-записей нет; без сети журнал показывает только ранее загруженные страницы.

### 🖤 Журнал и управление

Пять разделов: **Главная → История → Счета → Отчёты → Ещё**. Категории и импорт расположены в «Ещё». На главной — общий баланс, быстрые действия и последние записи выбранного счёта. В истории нет ограничения в 50 записей: кнопка загрузки продолжает журнал, поиск и фильтры применяются сервером ко всей истории.

- Нажмите операцию для изменения суммы, счёта, типа, даты/времени, комментария и категории. У импортированной операции счёт сохраняется: это необходимо для корректной отмены импорта.
- Удаление требует подтверждения и сразу пересчитывает баланс и отчёт. В фильтрах включите удалённые записи для восстановления. Перевод редактируется, удаляется и восстанавливается целиком.
- В «Счетах» можно изменить начальный остаток, название и тип, порядок, архив и параметр включения в общий баланс. Валюта существующего счёта неизменна. Архив запрещает новые записи на счёт, сохраняя историю. Архив и включение в баланс — независимые настройки.
- В «Категориях» доступны свои категории и подкатегории расходов, категории доходов, переименование, порядок и архив. Архив родителя распространяется на подкатегории. Объединение переносит назначения всей ветки на выбранную категорию и архивирует исходную ветку; отдельной отмены объединения нет. Настройка стандартной категории меняет её только у текущего пользователя.
- При записи перевода выберите два счёта, фактические суммы, дату и при необходимости комиссию. Для одинаковых валют суммы равны. Комиссия может списываться с третьего своего счёта. Основные части перевода не входят в доходы/расходы; комиссия — обычный расход. Обменный курс банка не подменяется справочным курсом Frankfurter.

Контракт, правила конфликтов и проверочные сценарии описаны в [P0: журнал и переводы](docs/LEDGER.md).

### 🗓️ Планирование: бюджеты и регулярные платежи

Откройте **«Ещё → Планирование»** или карточку **«Можно потратить»** на главной. В бюджете выберите категорию, валюту, лимит и день начала периода. Основная категория включает расходы своих подкатегорий. Факт считается по всей подтверждённой истории за текущий бюджетный период, а не по числу загруженных строк. Для одной валюты нельзя одновременно включить пересекающиеся бюджеты на родителя и его подкатегорию.

В календаре создайте разовый или повторяющийся доход/расход: еженедельно, ежемесячно или ежегодно. Запланированная зарплата или аренда не меняет баланс. Нажмите событие, проверьте фактическую сумму и дату, затем подтвердите. Ненужное событие можно пропустить, весь шаблон — приостановить или отправить в архив. День 31 корректно переносится на последний день короткого месяца, сохраняя исходное расписание для следующих месяцев.

**Можно потратить = текущие остатки выбранных счетов − резерв − ещё не оплаченные обязательные расходы до выбранной даты включительно.** По умолчанию учитываются активные карты и наличные, включённые в общий баланс; накопления и кредитные счета не входят. Горизонт — ближайший ожидаемый основной доход или конец месяца. Будущие доходы не увеличивают доступные деньги до подтверждения. Бюджетные лимиты отдельно показывают прогресс и повторно из суммы не вычитаются. При недостатке денег карточка показывает дефицит.

В настройках планирования выберите счета, валюту и резерв. Валюта планирования хранится отдельно от валюты общего баланса; при её смене резерв нужно ввести заново. Напоминания включаются на устройстве по желанию: Android проверяет события периодически при доступной сети, поэтому точная минута доставки не гарантируется. При недоступном курсе или неполной истории приложение не показывает частичный итог как полную доступную сумму.

Правила расчёта, ограничения, повтор запросов и сценарии проверки — в [инструкции планирования P1](docs/PLANNING.md).

### 🏷️ Категории и подкатегории

Стартовый список основан на типичных группах из [стандартных категорий Monarch](https://help.monarch.com/hc/en-us/articles/360048883851-Default-Categories) и [шаблона YNAB для начинающих](https://www.ynab.com/templates/ynab-beginner-template), с русскими названиями и более компактной структурой. Это практический набор, а не статистический рейтинг частоты расходов.

| Расходы | Стандартные подкатегории |
|---|---|
| Продукты и питание | Продукты, кафе и рестораны, кофе, доставка еды |
| Жильё и коммунальные услуги | Аренда жилья, коммунальные услуги, ремонт и обслуживание |
| Транспорт | Общественный транспорт, такси, топливо, обслуживание автомобиля |
| Покупки | Одежда и обувь, техника, товары для дома |
| Здоровье | Врачи и обследования, лекарства, стоматология |
| Красота и уход | Косметика, парикмахерская, уход и процедуры |
| Развлечения и хобби | Кино и мероприятия, игры и подписки, спорт и хобби |
| Образование | Курсы и обучение, книги, учебные материалы |
| Связь и интернет | Мобильная связь, домашний интернет |
| Путешествия | Билеты, проживание, экскурсии |
| Семья и дети | Детские товары, детский сад и занятия, помощь семье |
| Подарки и благотворительность | Подарки, благотворительность |
| Финансовые расходы | Банковские комиссии, проценты по кредитам, налоги и сборы |
| Прочие расходы | Домашние животные, прочее |

**Доходы:** зарплата; подработка и фриланс; бизнес; проценты и дивиденды; аренда; продажа имущества; подарки; пенсия и пособия; возвраты и компенсации; прочие доходы. Для доходов используется один уровень категорий.

В разделе **«Ещё → Категории»** можно создать свою категорию расходов или доходов, а также подкатегорию любой основной категории расходов. Например, **«Покупка автомобиля»** — отдельная категория, **«Сигареты»** — подкатегория «Продукты и питание». Стандартный «Кофе» уже находится внутри питания. Свою категорию создайте в этом разделе, затем выберите её при записи операции.

Название содержит 1–100 символов; одинаковые названия активных категорий внутри одного типа и родителя не допускаются. Стандартный список общий, а пользовательские категории видны только своему владельцу. Расходы имеют максимум два уровня. Старые операции после обновления остаются **«Без категории»**: нажмите на операцию и назначьте категорию вручную.

### 📊 Аналитика доходов и расходов

Откройте **«Отчёты»**, выберите месяц, валюту и режим «Расходы» или «Доходы». Диаграмма и список показывают суммы и проценты, начиная с самой крупной категории. Для расходов можно открыть категорию и посмотреть её подкатегории; расходы, назначенные сразу основной категории, отображаются как «Без подкатегории».

Сервер считает все неудалённые операции за календарный месяц в часовом поясе устройства, независимо от числа загруженных страниц журнала. Начальные остатки счетов не входят в доходы. Подкатегории включаются в итог родителя один раз; операции без категории образуют отдельный сектор. Для месяца без операций показывается пустой отчёт.

Импортированные операции с подтверждённой пометкой **«Движение денег»** влияют на баланс счёта, а в доходы, расходы и доли категорий не включаются. Это позволяет учитывать собственные переводы и наличные без завышения финансовой статистики.

Отчёт складывает только суммы в выбранной **исходной валюте** по всем счетам пользователя. Обновление курсов Frankfurter не меняет эти итоги. Категории кэшируются на устройстве; загрузка актуального отчёта и создание категории требуют соединения с сервером.

Для существующей установки сначала сделайте проверенную резервную копию и примените все SQL-миграции, включая **`006_planning.sql`**, затем запустите обновлённый API и установите новый APK. Обычный запуск через Docker Compose или `scripts/start-api.ps1` применяет миграции автоматически. Android обновляет Room с версий 1–5 до 6 с сохранением счетов и операций.

<a id="statement-import"></a>
### 📄 Импорт выписок Kaspi Gold и Halyk

Откройте **«Ещё → Импорт выписки»**, выберите PDF в системном диалоге Android и проверьте результаты разбора. Первая версия поддерживает русские текстовые макеты **Kaspi Gold / Halyk в KZT**: до 20 MiB, 100 страниц и 2000 операций. Скан, PDF с паролем или неизвестная структура вызывают явную ошибку до сохранения.

Выберите существующий счёт KZT или создайте новый с проверенным **остатком на начало периода**. Новые выписки используют фиксированное **время Казахстана UTC+5**, без выбора часового пояса и зависимости от настроек телефона. Сервер подготовит предпросмотр, найдёт совпадения во всей истории и автоматически подберёт категории и подкатегории там, где распознано описание.

Предыдущие согласованные назначения для такого магазина в своих импортах имеют приоритет перед встроенными правилами. Категории можно исправить; кнопка **«Применить к похожим»** меняет категорию других выбранных похожих строк, сохраняя уже сделанные ручные исправления. Фильтр **«Без категории»** позволяет быстро разобрать неизвестные описания. Такие строки можно сохранить без категории. Автоподбор не подтверждает возможные дубли и не определяет автоматически, является ли перевод собственным движением денег. Проверьте итоговый баланс и подтвердите запись. Выбранные строки сохраняются атомарно; неопределённый ответ сети повторяется с сохранённым ключом и теми же параметрами.

В истории импортов доступна отмена после подтверждения: удаляются только операции, созданные этим импортом. Исходный PDF обрабатывается на устройстве; сервер получает нормализованные строки. В Halyk комиссия учитывается отдельно, а исходная сумма покупки в другой валюте не заменяет фактическое списание со счёта. При разборе Kaspi приложенная справка и итоговые таблицы не создают операций.

Для существующего счёта проверьте начальный остаток и уже внесённые операции. Для нового счёта с полным импортом используйте начало выписки. После исключения строк баланс может отличаться от банковского. Новые переводы можно записывать в журнале. Автоматическое связывание двух уже импортированных строк остаётся отдельным этапом.

Подробности, ограничения, дубли и серверный контракт — в [инструкции импорта](docs/STATEMENT_IMPORT.md).

<a id="architecture"></a>
## 🔵 Архитектура и стек

~~~mermaid
flowchart TD
    UI["📱 Android · Compose / Material 3"] --> VM["ViewModel · StateFlow · Coroutines"]
    VM --> R["Repository"]
    R <--> ROOM[("Room · кэш")]
    R <--> SEC["Keystore · токен / pending"]
    R <-->|"Retrofit / OkHttp · HTTPS / JSON"| EDGE["Vercel · HTTPS / Caddy на VPS"]
    EDGE --> API["Go / Gin · REST /api/v1"]
    API --> SVC["Сервисы · auth / accounts / transactions"]
    SVC --> ORM["GORM + SQL"]
    ORM --> PG[("PostgreSQL · Neon / VPS")]
    SVC --> FX["FX · точные коэффициенты / кэш"]
    FX --> PG
    FX --> FF["Frankfurter v2 · ежедневные курсы"]
    MIG["Отдельный migration runner"] --> PG
    classDef android fill:#E8F5E9,stroke:#22C55E,color:#14532D
    classDef local fill:#F3E8FF,stroke:#A855F7,color:#581C87
    classDef backend fill:#E0F2FE,stroke:#0EA5E9,color:#0C4A6E
    classDef database fill:#FEF3C7,stroke:#F59E0B,color:#78350F
    class UI,VM,R android
    class ROOM,SEC local
    class EDGE,API,SVC,ORM,MIG,FX,FF backend
    class PG database
~~~

Локально Android обращается прямо к API на порту 8080. Публичный тестовый сервер работает через Vercel HTTPS и Neon PostgreSQL; для отдельного VPS подготовлен Caddy. Backend — одно Go-приложение с отдельными пакетами; Redis и микросервисы на этом этапе не требуются.

| Слой | Технологии и версии из проекта |
|---|---|
| Android UI | Kotlin 2.1.21, Jetpack Compose BOM 2025.05.01, Material 3 |
| Данные / состояние | ViewModel, StateFlow, Coroutines 1.10.2, Room 2.7.2 |
| Android сеть | Retrofit 2.11.0, OkHttp 4.12.0, Gson 2.11.0 |
| Сборка | Gradle 8.13, AGP 8.13.2, KSP 2.1.21-2.0.1 |
| Backend | Go 1.25+, Gin, GORM; версии в `go.mod` / `go.sum` |
| База / инфраструктура | Neon PostgreSQL 18.6, Vercel Hobby; локально / на VPS PostgreSQL 17.11, Docker Compose, Caddy 2.11.7 |
| Курсы / конвертация | Frankfurter v2, USD-снимок в PostgreSQL, рациональная арифметика Go |
| Контракт / проверки | OpenAPI 3.0.3; Go tests / vet / race, JUnit / MockWebServer, Android Lint |

<a id="structure"></a>
## 🗂 Структура репозитория

~~~text
moneyflow/
├── android/                    # Android Studio / Gradle проект
│   └── app/src/main/java/com/moneyflow/app/
│       ├── data/               # Repository, Room, Retrofit, деньги, SessionStore
│       └── ui/                 # Compose, ViewModel, тема
├── backend/
│   ├── cmd/api/                # HTTP-сервер
│   ├── cmd/migrate/            # Migration runner
│   └── internal/
│       ├── auth/               # Пароли и токены
│       ├── config/             # Переменные окружения
│       ├── database/           # Соединение PostgreSQL
│       ├── fx/                 # Frankfurter, кэш курсов и точная конвертация
│       ├── httpapi/            # Маршруты, middleware, валидация, тесты
│       ├── migrations/sql/     # Версионированные SQL-файлы
│       ├── model/              # Модели хранения
│       └── service/            # Финансовые правила и авторизация
├── api/openapi.yaml            # Полный контракт API
├── infra/                      # Dockerfile, Compose, Caddyfile
├── scripts/                    # PowerShell запуск и smoke-проверка
├── docs/assets/                # Баннер и скриншот
├── .github/workflows/ci.yml    # Проверки и debug APK
├── .env.example               # Локальные настройки
└── .env.production.example    # Production-настройки
~~~

Источники: [Android README](android/README.md), [OpenAPI](api/openapi.yaml), [локальный Compose](infra/compose.yaml), [production Compose](infra/compose.production.yaml), [SQL-миграции](backend/internal/migrations/sql), [официальная документация Frankfurter v2](https://frankfurter.dev/).

<a id="requirements"></a>
## 🧰 Что установить

| Задача | Требования |
|---|---|
| Получить код | Git |
| Сервер через Docker | Работающий Docker Engine + Compose v2; Windows: Docker Desktop |
| Сервер без Docker | Go 1.25+, доступный PostgreSQL, `psql` для настройки |
| Android | Android Studio или SDK, JDK 17, SDK Platform 35, Build Tools 35.0.0 |
| Устройство | Эмулятор / телефон с Android 8.0+ (API 26+) |
| Smoke-скрипт | PowerShell 7 (`pwsh`): используется `-SkipHttpErrorCheck` |

В CI используется JDK 17; локальная сборка также проверялась с JDK 21. Java/Kotlin bytecode — Java 17. `compileSdk` / `targetSdk` — 35. Wrapper сам загружает Gradle; глобальная установка не нужна. Для первой сборки нужен интернет.

<a id="quick-start"></a>
## 🚀 Быстрый старт: сервер через Docker

Запустите Docker Desktop / Engine. Команды после клонирования выполняются **из корня репозитория**.

<details open>
<summary><b>🪟 Windows · PowerShell</b></summary>

~~~powershell
git clone https://github.com/drissakov/moneyflow.git
Set-Location moneyflow
git switch main
# Создать .env только при первом запуске, не перезаписывая настройки.
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
docker version
docker compose version
docker compose --env-file .env -f infra/compose.yaml up --build -d
docker compose --env-file .env -f infra/compose.yaml ps -a
$taskApiReady = $false
foreach ($taskAttempt in 1..60) {
    try {
        $null = Invoke-RestMethod http://localhost:8080/readyz -TimeoutSec 3 -ErrorAction Stop
        $taskApiReady = $true
        break
    } catch { Start-Sleep -Seconds 2 }
}
if (-not $taskApiReady) { throw 'API не готов: проверьте docker compose logs api migrate' }
Invoke-RestMethod http://localhost:8080/healthz
Invoke-RestMethod http://localhost:8080/readyz
~~~

</details>

<details>
<summary><b>🐧 Linux / 🍎 macOS · shell</b></summary>

~~~sh
git clone https://github.com/drissakov/moneyflow.git
cd moneyflow
git switch main
if [ ! -f .env ]; then cp .env.example .env; fi
docker version
docker compose version
docker compose --env-file .env -f infra/compose.yaml up --build -d
docker compose --env-file .env -f infra/compose.yaml ps -a
task_attempt=0
until curl --fail --silent --max-time 3 http://localhost:8080/readyz >/dev/null; do
  task_attempt=$((task_attempt + 1))
  if [ "$task_attempt" -ge 60 ]; then
    echo 'API не готов: проверьте docker compose logs api migrate' >&2
    exit 1
  fi
  sleep 2
done
curl --fail http://localhost:8080/healthz
curl --fail http://localhost:8080/readyz
~~~

</details>

Порядок запуска: `db` → готовность PostgreSQL → `migrate` → `api`. **Exited (0)** у `migrate` означает успешную миграцию: runner не работает постоянно. API и база должны стать healthy. Первый запуск требует времени на загрузку образов и сборку.

- `/healthz` → `{"status":"ok"}`: HTTP-процесс работает.
- `/readyz` → `{"status":"ready"}`: PostgreSQL доступен.
- Readiness проверяет ping, **не наличие таблиц**; миграции должны завершиться отдельно.
- `http://localhost:8080/` возвращает **404**: веб-страницы и встроенного Swagger UI нет.

PostgreSQL доступен компьютеру по `127.0.0.1:5432`, API — по `8080`. Внутри Docker backend использует хост `db`.

### Логи, остановка, сохранение данных

~~~sh
docker compose --env-file .env -f infra/compose.yaml logs --tail=100 db migrate api
docker compose --env-file .env -f infra/compose.yaml logs -f api
docker compose --env-file .env -f infra/compose.yaml down
~~~

`Ctrl+C` прекращает просмотр логов. Обычный `down` сохраняет базу в named volume `postgres_data`; следующий `up --build -d` использует те же данные.

> 🔴 `down -v` удаляет volume и базу этого стека. Используйте только для намеренного локального сброса. Изменение `POSTGRES_PASSWORD` в `.env` не меняет пароль роли в уже существующем volume.

<a id="native-server"></a>
## 🐹 Go + установленный PostgreSQL без Docker

Нужны Go, запущенный PostgreSQL и `psql` в PATH. Создайте роль и базу **один раз**, от PostgreSQL-администратора. Пример использует `postgres`; пароль администратора запрашивает `psql`.

`CREATE ROLE` и `CREATE DATABASE` выполняются отдельными командами: создание базы нельзя объединять в одну транзакцию с созданием роли.

<details>
<summary><b>🪟 Windows · PowerShell</b></summary>

Из корня проекта:

~~~powershell
psql -h localhost -U postgres -c "CREATE ROLE moneyflow LOGIN PASSWORD 'moneyflow_dev_password';"
psql -h localhost -U postgres -c "CREATE DATABASE moneyflow OWNER moneyflow;"
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
# При другом имени / порте / пароле отредактируйте DATABASE_URL в .env.
.\scripts\start-api.ps1
~~~

[Скрипт](scripts/start-api.ps1) читает `.env`, запускает миграции и API. Сервер занимает терминал; проверяйте `/readyz` в другом окне. Остановка — `Ctrl+C`. Другой файл: `.\scripts\start-api.ps1 -EnvFile .env.local`.

Ручной запуск вместо скрипта:

~~~powershell
$env:DATABASE_URL = 'postgres://moneyflow:moneyflow_dev_password@localhost:5432/moneyflow?sslmode=disable'
$env:PORT = '8080'
$env:SESSION_TTL = '720h'
Set-Location backend
go run ./cmd/migrate
# Продолжайте только после успешной миграции.
go run ./cmd/api
~~~

</details>

<details>
<summary><b>🐧 Linux / 🍎 macOS · shell</b></summary>

Из корня проекта:

~~~sh
psql -h localhost -U postgres -c "CREATE ROLE moneyflow LOGIN PASSWORD 'moneyflow_dev_password';"
psql -h localhost -U postgres -c "CREATE DATABASE moneyflow OWNER moneyflow;"
export DATABASE_URL='postgres://moneyflow:moneyflow_dev_password@localhost:5432/moneyflow?sslmode=disable'
export PORT=8080
export SESSION_TTL=720h
cd backend
go run ./cmd/migrate && go run ./cmd/api
~~~

Если локальная установка использует Unix peer-аутентификацию, выполните две `psql`-команды от доступного PostgreSQL-администратора согласно настройкам системы.

</details>

**Go-бинарники сами не читают `.env`**: `DATABASE_URL` нужен в переменных окружения процесса. Файл автоматически загружает PowerShell-скрипт или Docker Compose.

<a id="android"></a>
## 📱 Запуск Android

### Android Studio

1. Запустите backend и проверьте `/readyz`.
2. Откройте каталог **`android/`** в Android Studio.
3. В SDK Manager установите **SDK Platform 35**, **Build Tools 35.0.0**, **Platform Tools**.
4. Выберите JDK 17 в настройках Gradle и дождитесь Sync.
5. Создайте эмулятор с API 26+ в Device Manager.
6. Выберите `app` / debug и нажмите Run ▶️.

По умолчанию debug API URL — `http://10.0.2.2:8080/`. В стандартном Android Emulator это адрес компьютера; `localhost` внутри эмулятора означает сам эмулятор.

### SDK и adb для CLI

Android Studio обычно создает `android/local.properties`. Если его нет, добавьте реальный **абсолютный** путь к SDK, например:

~~~properties
sdk.dir=C:/Users/YOUR_USER/AppData/Local/Android/Sdk
~~~

Можно вместо этого задать `ANDROID_HOME`. `local.properties` не коммитьте. Добавьте `platform-tools` в PATH для `adb`.

<details>
<summary><b>Примеры окружения</b></summary>

PowerShell, с заменой пути на свой:

~~~powershell
$env:ANDROID_HOME = 'C:\Users\YOUR_USER\AppData\Local\Android\Sdk'
$env:PATH = "$env:ANDROID_HOME\platform-tools;$env:PATH"
java -version
adb version
~~~

Linux: обычно `$HOME/Android/Sdk`; macOS: `$HOME/Library/Android/sdk`:

~~~sh
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
java -version
adb version
~~~

</details>

### Собрать, установить и открыть

В новом терминале из корня проекта, с запущенным эмулятором:

<details open>
<summary><b>🪟 Windows · PowerShell</b></summary>

~~~powershell
Set-Location android
.\gradlew.bat :app:assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.moneyflow.app.debug/com.moneyflow.app.MainActivity
~~~

</details>

<details>
<summary><b>🐧 Linux / 🍎 macOS · shell</b></summary>

~~~sh
cd android
bash ./gradlew :app:assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.moneyflow.app.debug/com.moneyflow.app.MainActivity
~~~

</details>

`assembleDebug` только собирает APK; `adb install` устанавливает; `am start` открывает. Можно установить через Gradle `:app:installDebug`. При нескольких устройствах используйте `adb -s SERIAL`.

Debug package: **`com.moneyflow.app.debug`**. APK: **`android/app/build/outputs/apk/debug/app-debug.apk`**.

### Телефон по USB

Включите отладку USB, подтвердите доверие компьютеру. Из `android/`:

~~~powershell
adb devices
adb reverse tcp:8080 tcp:8080
.\gradlew.bat :app:assembleDebug -PMONEYFLOW_API_URL=http://127.0.0.1:8080/
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.moneyflow.app.debug/com.moneyflow.app.MainActivity
~~~

Linux/macOS: замените `.\gradlew.bat` на `bash ./gradlew`. После переподключения телефона снова настройте `adb reverse`, если проброс исчез.

### Телефон по Wi-Fi

Телефон должен видеть компьютер в локальной сети. Узнайте LAN IP (`ipconfig` на Windows) и замените пример:

~~~powershell
.\gradlew.bat :app:assembleDebug -PMONEYFLOW_API_URL=http://192.168.1.10:8080/
adb install -r app/build/outputs/apk/debug/app-debug.apk
~~~

Разрешите TCP 8080 в firewall для доверенной сети. Проверьте с телефона `http://192.168.1.10:8080/healthz`. Гостевой Wi-Fi может изолировать устройства.

URL обязан заканчиваться **`/`** и встраивается **при сборке**: после смены адреса пересоберите и переустановите APK. Для возврата к эмулятору укажите `-PMONEYFLOW_API_URL=http://10.0.2.2:8080/`. HTTP разрешен только в debug; не передавайте реальные пароли по общей сети в HTTP-конфигурации.

### Первый сценарий

Регистрация → счет KZT с начальным балансом 5000 → выбор счета → расход 12,99 с категорией «Продукты и питание → Кофе» → обновление. Ожидаемый баланс — **4987,01 KZT**. В «Отчётах» текущего месяца выберите KZT: расходы — **12,99 KZT**, питание — **100%**. При неопределенном результате записи повторите сохраненную операцию через кнопку в приложении.

<a id="configuration"></a>
## ⚙️ Настройки окружения

Копируйте шаблоны только при первом запуске. Пароли и `.env` не должны попадать в Git.

| Переменная | Назначение / значение |
|---|---|
| `DATABASE_URL` | Обязательна для Go. Native: хост `localhost`; production Compose: `db` |
| `PORT` | Native API: по умолчанию 8080, допустимо 1–65535 |
| `SESSION_TTL` | По умолчанию `720h` (30 дней), допустимо `1m`–`2160h`. Production-шаблон: `168h` |
| `FRANKFURTER_URL` | `https://api.frankfurter.dev/v2`; HTTPS URL без userinfo/query/fragment. HTTP разрешен только для localhost / loopback fixtures |
| `FX_CACHE_TTL` | Свежесть снимка: по умолчанию `6h`, допустимо `1m`–`24h` |
| `FX_MAX_STALE` | Предельный возраст fallback-данных: по умолчанию `168h` (7 дней), допустимо `1h`–`720h`, не меньше `FX_CACHE_TTL` |
| `APP_ENV` | `development` / `test` / `production`; пустое допустимо для development |
| `TRUSTED_PROXIES` | IP/CIDR через запятую; по умолчанию доверенных прокси нет |
| `GIN_MODE` | В Compose — `release` |
| `POSTGRES_USER` / `POSTGRES_DB` | Compose: по умолчанию `moneyflow` / `moneyflow` |
| `POSTGRES_PASSWORD` | Dev-пароль в локальном шаблоне; production требует свой |
| `DOMAIN` / `ACME_EMAIL` | Production: домен API и контактный email для сертификата |
| `CADDY_PRIVATE_IP` | Production: `172.30.50.10` по умолчанию; доверенный IP Caddy |
| `PRIVATE_NETWORK_SUBNET` | Production: `172.30.50.0/24` по умолчанию |
| `TEST_DATABASE_URL` | Соединение для PostgreSQL integration tests |

Локальный Compose сам строит DSN из `POSTGRES_*` с хостом `db`. `DATABASE_URL` локального `.env` нужен для native Go. **Оба Compose-файла явно задают `PORT: "8080"`**: смена `PORT` в `.env` не меняет порт контейнера / mapping.

Спецсимволы пароля в DSN URL-encode; в `POSTGRES_PASSWORD` остается исходный пароль. Для простого production-примера используйте длинный случайный URL-safe пароль с одинаковым значением в обеих настройках.

<a id="api"></a>
## 🔌 API

**[Полный OpenAPI-контракт](api/openapi.yaml)** содержит поля, схемы, примеры и ошибки; импортируйте его в API-клиент или OpenAPI-редактор.

| Метод | Путь | Авторизация | Успех |
|---|---|---|---|
| GET | `/healthz` | — | 200, HTTP-процесс жив |
| GET | `/readyz` | — | 200, база доступна |
| POST | `/api/v1/auth/register` | — | 201, пользователь + сессия |
| POST | `/api/v1/auth/login` | — | 200, новая сессия |
| POST | `/api/v1/auth/logout` | Bearer | 204, отзыв текущей сессии |
| GET | `/api/v1/me` | Bearer | 200, пользователь |
| GET | `/api/v1/accounts` | Bearer | 200, `{"accounts":[...]}` |
| POST | `/api/v1/accounts` | Bearer | 201, счет |
| GET | `/api/v1/transactions?limit=50&cursor=...` | Bearer | 200, `{"transactions":[...],"next_cursor":...}`; счёт необязателен |
| PUT / DELETE / POST | `/api/v1/transactions/UUID`, `/UUID/restore` | Bearer + Idempotency-Key | Изменение / мягкое удаление / восстановление; `expected_version` |
| PUT | `/api/v1/accounts/UUID` | Bearer + Idempotency-Key | Настройки и начальный остаток; `expected_version` |
| PUT / POST | `/api/v1/categories/UUID`, `/UUID/merge` | Bearer + Idempotency-Key | Настройка / объединение; `expected_version` |
| GET / POST | `/api/v1/transfers` | Bearer; запись + Idempotency-Key | Переводы: курсорный список / атомарное создание |
| GET / PUT / DELETE / POST | `/api/v1/transfers/UUID`, `/UUID/restore` | Bearer; запись + Idempotency-Key | Получение / изменение / удаление / восстановление группы |
| POST | `/api/v1/transactions` | Bearer + `Idempotency-Key` | 201, новая / повторно полученная операция |
| GET | `/api/v1/categories` | Bearer | 200, стандартные и свои категории |
| POST | `/api/v1/categories` | Bearer | 201, своя категория / подкатегория расходов |
| PUT | `/api/v1/transactions/UUID/category` | Bearer | 200, операция с обновлённой категорией |
| GET | `/api/v1/analytics?month=2026-10&currency=KZT&timezone=Asia/Qyzylorda` | Bearer | 200, итоги месяца и группы для диаграммы |
| GET | `/api/v1/currencies` | Bearer | 200, каталог 11 валют |
| GET / PUT | `/api/v1/preferences` | Bearer | 200, `{"base_currency":"KZT"}` |
| GET | `/api/v1/exchange-rates?base=USD` | Bearer | 200, доступные курсы и список отсутствующих |
| POST | `/api/v1/convert` | Bearer | 200, предварительная конвертация без записи операции |
| GET | `/api/v1/portfolio?base=KZT` | Bearer | 200, общий / явно неполный баланс |
| GET | `/api/v1/planning?from=2026-10-01&to=2026-10-31` | Bearer | Бюджеты, шаблоны, события и настройки; календарные даты в UTC+5 |
| POST / PUT | `/api/v1/budgets`, `/api/v1/budgets/UUID` | Bearer + Idempotency-Key | Создание / изменение бюджета, включая архив |
| POST / PUT | `/api/v1/schedules`, `/api/v1/schedules/UUID` | Bearer + Idempotency-Key | Создание / изменение планового шаблона |
| POST | `/api/v1/schedules/UUID/occurrences/YYYY-MM-DD/{post,skip,unskip}` | Bearer + Idempotency-Key | Подтверждение факта / пропуск / возврат в план |
| PUT | `/api/v1/planning/settings` | Bearer + Idempotency-Key | Валюта, резерв и счета для расчёта; expected_version |
| GET | `/api/v1/planning/forecast?until=2026-10-31` | Bearer | Оценка доступных денег; until необязателен |

JSON требует `Content-Type: application/json`. Тело — один объект до **16 KiB**, неизвестные поля отклоняются. Время — RFC3339 с часовым поясом.

| Код | Значение |
|---|---|
| 400 | Некорректный JSON, поля, UUID, сумма, дата или limit |
| 401 | Пароль/токен неверен, сессия отсутствует, истекла или отозвана |
| 404 / 405 | Ресурс отсутствует/чужой или маршрут не существует / неверный метод |
| 409 | Email занят, конфликт ключа идемпотентности, переполнение баланса / конвертации (`conversion_overflow`) |
| 413 / 415 | Слишком большое тело / неверный Content-Type |
| 429 | Лимит auth-запросов; учитывайте `Retry-After` |
| 500 / 503 / 504 | Ошибка сервера / база недоступна на readiness или `rates_unavailable` при конвертации / deadline запроса |

Формат: `{"error":{"code":"invalid_request","message":"Human-readable explanation."}}`.

### 🧪 Полный пример в PowerShell 7

При запущенном API пример создает тестовые записи и оставляет их в базе. Демонстрационный пароль не используйте для реальных данных. Токен берется из ответа и не выводится в консоль. UTF-8 encoding позволяет передавать русские названия.

~~~powershell
$ErrorActionPreference = 'Stop'
$taskApi = 'http://localhost:8080'
function Send-MoneyFlow {
    param([string]$Method, [string]$Path, $Body = $null, [hashtable]$Headers = @{})
    $taskParams = @{ Method = $Method; Uri = "$taskApi$Path"; Headers = $Headers }
    if ($null -ne $Body) {
        $taskJson = $Body | ConvertTo-Json -Compress
        $taskParams.Body = [Text.Encoding]::UTF8.GetBytes($taskJson)
        $taskParams.ContentType = 'application/json; charset=utf-8'
    }
    Invoke-RestMethod @taskParams
}
# Email новый при каждом запуске; сохраните его для входа в приложении.
$taskEmail = "demo-$([guid]::NewGuid().ToString('N'))@example.com"
$taskPassword = 'Demo-only-password-2026!'
$taskSession = Send-MoneyFlow POST '/api/v1/auth/register' @{
    email = $taskEmail; password = $taskPassword
}
$taskAuth = @{ Authorization = "Bearer $($taskSession.access_token)" }
$taskAccount = Send-MoneyFlow POST '/api/v1/accounts' @{
    name = 'Наличные'; currency = 'KZT'; opening_balance_minor = '500000'
} $taskAuth

$taskExpense = @{
    account_id = $taskAccount.id; kind = 'expense'; amount_minor = '1299'
    note = 'Обед'; occurred_at = [DateTimeOffset]::UtcNow.ToString('o')
}
$taskKey = [guid]::NewGuid().ToString()
$taskWriteHeaders = @{
    Authorization = $taskAuth.Authorization; 'Idempotency-Key' = $taskKey
}
$taskCreated = Send-MoneyFlow POST '/api/v1/transactions' $taskExpense $taskWriteHeaders
# Тот же payload и ключ: тот же результат, без второго расхода.
$taskRetry = Send-MoneyFlow POST '/api/v1/transactions' $taskExpense $taskWriteHeaders
if ($taskCreated.id -ne $taskRetry.id) { throw 'Duplicate transaction!' }
$taskAccounts = Send-MoneyFlow GET '/api/v1/accounts' -Headers $taskAuth
$taskBalance = ($taskAccounts.accounts | Where-Object id -eq $taskAccount.id).balance_minor
if ($taskBalance -ne '498701') { throw "Unexpected balance: $taskBalance" }
Send-MoneyFlow GET "/api/v1/transactions?account_id=$($taskAccount.id)&limit=50" -Headers $taskAuth
# Основная валюта сохраняется для этого пользователя.
Send-MoneyFlow PUT '/api/v1/preferences' @{ base_currency = 'KZT' } $taskAuth
Send-MoneyFlow GET '/api/v1/currencies' -Headers $taskAuth
Send-MoneyFlow GET '/api/v1/exchange-rates?base=USD' -Headers $taskAuth
# 100.00 USD -> KZT: это расчет, финансовая операция не создается.
Send-MoneyFlow POST '/api/v1/convert' @{
    from_currency = 'USD'; to_currency = 'KZT'; amount_minor = '10000'
} $taskAuth
# Без base используется сохраненная основная валюта.
Send-MoneyFlow GET '/api/v1/portfolio' -Headers $taskAuth
Send-MoneyFlow POST '/api/v1/auth/logout' -Headers $taskAuth
"PASS: один расход, баланс 4987.01 KZT, сессия отозвана. Пользователь: $taskEmail"
~~~

Повторный вход: `POST /api/v1/auth/login` с теми же `email` / `password` выдаст новый токен. Доход: `kind: "income"`, положительная сумма и **новый** ключ.

Минимальная проверка из shell; `MONEYFLOW_TOKEN` содержит актуальный токен регистрации/входа:

~~~sh
curl --fail http://localhost:8080/readyz
curl --fail http://localhost:8080/api/v1/accounts \
  -H "Authorization: Bearer $MONEYFLOW_TOKEN"
curl --fail 'http://localhost:8080/api/v1/portfolio?base=KZT' \
  -H "Authorization: Bearer $MONEYFLOW_TOKEN"
~~~

### 🌍 Курсы и общий баланс

`GET /currencies` возвращает `{provider,currencies}` с кодом, английским названием, символом и `fraction_digits`. `GET /preferences` и `PUT /preferences` читают / сохраняют `base_currency` текущего пользователя. Все эти пути имеют префикс `/api/v1` и требуют Bearer-токен. Необязательный query `base` в курсах и портфеле переопределяет валюту только для этого ответа; сохраненная настройка не меняется.

`POST /convert` принимает `from_currency`, `to_currency`, `amount_minor` — каноническую строку со знаком, модуль ≤ 10^15 минимальных единиц. Ответ содержит исходные поля, `converted_amount_minor`, `rate`, `rate_date`, `fetched_at`, `stale`, `provider`. Одинаковая валюта дает rate `"1"`, даты `null`, `stale=false` и работает без внешнего провайдера. Неподдерживаемая валюта / некорректная сумма — 400; недоступный курс — 503 `rates_unavailable`; переполнение signed int64 — 409 `conversion_overflow`.

`GET /portfolio` возвращает исходный и пересчитанный баланс каждого счета. **`complete=true`** разрешает показывать `total_balance_minor` как общий итог. Если курс хотя бы одной нужной валюты отсутствует, ответ остается 200, но **`complete=false`, `total_balance_minor=null`**. `partial_balance_minor` — сумма только доступных оценок, `missing_currencies` объясняет пропуски; это не полный баланс. На счетах без курса `converted_balance_minor`, `rate` и `rate_date` равны `null`. Переполнение отдельной оценки или суммы также возвращает 409.

<a id="data-rules"></a>
## 🧮 Деньги, безопасность и офлайн

### Точные суммы

API передает деньги **строками целых чисел в минимальных единицах**; PostgreSQL хранит `BIGINT`, Android преобразует ввод через `BigDecimal`. Floating point для денег не используется.

| Валюта | Дробных знаков | Сумма UI | `amount_minor` |
|---|---:|---:|---|
| KZT / USD / EUR / RUB / GBP / CHF / AUD / CAD / CNY | 2 | 12,99 | `"1299"` |
| JPY | 0 | 1299 | `"1299"` |
| KWD | 3 | 1,299 | `"1299"` |

- Доход/расход: сумма положительная, от 1 до **10^15**; направление задает `kind`.
- Начальный баланс: отрицательный, нулевой или положительный, модуль ≤ **10^15**.
- Формат канонический: `"0"`, `"1299"`, `"-1299"` при разрешенном минусе. `"01"`, `"-0"`, `"1.5"`, `"1e3"` и JSON-числа вместо строк отклоняются.
- Баланс = `начальный + доходы − расходы`, диапазон signed int64. Переполняющая запись отклоняется атомарно с 409.
- Название счета: 1–100 Unicode-символов после trim; заметка: до 500 символов.
- Валюта берется из счета; `user_id` от клиента для выбора владельца не принимается.

### Frankfurter и точность оценки

Используется [Frankfurter v2](https://frankfurter.dev/) с ежедневными справочными курсами нескольких официальных источников. API-key не нужен. Это оценка по публикуемым курсам, а не банковский bid/ask, гарантированный курс обмена или котировка для торгов в реальном времени. Приложение предлагает 11 валют из более широкого каталога провайдера; KZT поддерживается v2.

Сервер запрашивает только список валют и USD-базу (`/v2/rates?base=USD&quotes=...`); имена пользователей, токены, счета, суммы и заметки провайдеру не передаются. Коэффициенты сохраняются десятичными строками в PostgreSQL, а конвертация выполняется точной рациональной арифметикой. Результат округляется до минимальной единицы целевой валюты по **half-even**: при точной половине выбирается четное число. Для общего итога сначала округляется каждый счет, затем складываются полученные минимальные единицы. Поле `rate` удобно для отображения; повторный расчет по его ограниченной десятичной записи может отличаться от точного внутреннего расчета.

USD-снимок общий для пользователей и переживает перезапуск сервера. По умолчанию он свежий **6 часов**; после этого следующему запросу нужна попытка обновления. При недоступном провайдере допускается ограниченный fallback до **7 дней**, отмеченный `stale=true`; слишком старые / отсутствующие валюты не подменяются нулем или курсом 1. Возраст проверяется и для снимка, и для опубликованной даты курса. `rate_date` — дата наблюдения провайдера (для кросс-курса — более ранняя из двух дат), `fetched_at` — время загрузки снимка; это разные значения. Выходные и праздники могут давать дату предыдущего рабочего дня. Конвертация не меняет валюту счета, исходные записи или их исторические суммы.

### Сессии и безопасный повтор

Пароль — **12–128 UTF-8 байт**, его пробелы не обрезаются. Сервер хранит salted Argon2id-хеш. Opaque Bearer-токен сохраняется в базе в виде хеша; выход отзывает текущую сессию. Refresh tokens пока нет. Регистрация/вход ограничены общим лимитом **20 запросов в минуту на IP**, счетчик находится в памяти процесса.

Чужие счета/операции возвращают 404. Логи содержат метод, маршрут, статус и длительность; тела запросов и Authorization не журналируются.

Новый финансовый запрос получает UUID `Idempotency-Key`. Сохраняйте ключ и поля до подтверждения: идентичный повтор вернет сохранённый ответ с исходным статусом (**201** при создании, **200** при изменении, удалении или восстановлении), другой payload с этим ключом — **409**. Операция и результат для повторов сохраняются в одной транзакции PostgreSQL. Создание счетов и категорий также поддерживает необязательный ключ; новый Android-интерфейс передаёт его. Для изменения существующих записей используется `expected_version`: устаревшая версия возвращает **409**, сохраняя новые данные на сервере.

### Данные на телефоне

Room в приватном каталоге приложения разделяет данные по пользователю, но **сама Room-база не зашифрована**. Токен и pending-запрос шифруются AES-GCM с ключом Android Keystore. Пароль не сохраняется, Android backup выключен.

Кэш доступен при неистекшей локальной сессии. Выход сразу удаляет токен и финансовый кэш, затем пытается отозвать сессию сервера. Если сети нет, приложение сообщает, что серверная сессия может оставаться действительной до истечения срока.

Неразрешенный запрос сохраняется отдельно для каждого пользователя после выхода/истечения сессии. Вход тем же пользователем позволяет повторить его; другой пользователь не перезаписывает запрос. Подтвержденный или окончательно отклоненный запрос удаляется. Фоновой офлайн-очереди и автоматических retry нет.

<a id="testing"></a>
## ✅ Проверки и CI

Из корня проекта с работающим API, в PowerShell 7:

~~~powershell
.\scripts\smoke.ps1
# Для другого адреса:
.\scripts\smoke.ps1 -BaseUrl https://api.your-domain.com
~~~

Smoke создает двух пользователей и проверяет точный баланс, повтор без дублей, изоляцию, конфликт ключа и выход. Тестовые данные остаются — используйте development-базу.

<details>
<summary><b>Backend: тесты, анализ, PostgreSQL</b></summary>

~~~sh
cd backend
go test ./...
go vet ./...
go build ./cmd/api ./cmd/migrate
~~~

Без `TEST_DATABASE_URL` integration tests **пропускаются**. Для них создайте отдельную базу:

~~~sh
psql -h localhost -U postgres -c "CREATE DATABASE moneyflow_test OWNER moneyflow;"
~~~

PowerShell, из `backend/`:

~~~powershell
$env:TEST_DATABASE_URL = 'postgres://moneyflow:moneyflow_dev_password@localhost:5432/moneyflow_test?sslmode=disable'
go test -count=1 ./...
~~~

Linux/macOS, из `backend/`:

~~~sh
export TEST_DATABASE_URL='postgres://moneyflow:moneyflow_dev_password@localhost:5432/moneyflow_test?sslmode=disable'
go test -race -count=1 ./...
~~~

Тесты создают временные изолированные схемы и затем удаляют их; нужны права создания схем. CI выполняет race detector на Linux; локально ему требуется поддерживаемое окружение и C toolchain.

</details>

<details>
<summary><b>Android: сборка, unit/contract tests, lint</b></summary>

Из `android/`:

~~~powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
~~~

Linux/macOS:

~~~sh
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
~~~

Отчеты: `app/build/reports/tests/testDebugUnitTest/index.html` и `app/build/reports/lint-results-debug.html`. Автоматических end-to-end UI-тестов на устройстве пока нет.

</details>

[GitHub Actions](https://github.com/drissakov/moneyflow/actions) запускается при push, pull request и вручную. Проверяет форматирование/vet/race/integration tests с PostgreSQL 17, сборку backend, Android build/lint/tests. Успешный Android job загружает artifact **`moneyflow-debug`** с APK. Это debug APK с URL эмулятора, а не опубликованный signed release.

При создании MVP **9 октября 2026** локально прошли Go-проверки, PostgreSQL 18.1 integration tests, smoke, восстановление backup, Android build / 14 tests / lint без ошибок. На эмуляторе вручную проверены регистрация, счет и расход. Compose-конфигурации провалидированы, но запуск контейнеров тогда не был проверен из-за сбоя Docker Desktop. Эти результаты не заменяют текущий статус CI и проверку вашей среды.

<a id="migrations"></a>
## 🧱 Миграции и локальное обновление

[SQL-миграции](backend/internal/migrations/sql) встраиваются в бинарник и применяются отдельным runner с блокировкой PostgreSQL и SHA-256 checksum. Примененные файлы не меняйте: добавляйте новый файл с очередным номером. API не выполняет `AutoMigrate`; down-миграций нет.

Обновление мультивалютности добавляет [**`002_multicurrency.sql`**](backend/internal/migrations/sql/002_multicurrency.sql): расширенный список валют, предпочтение основной валюты и persistent-кэш курсов. Уже примененная `001_initial.sql` остается неизменной. Для существующей базы обязательно запустите новый runner перед новым API; удалять базу / volume для обновления не нужно. Для native Go: `go run ./cmd/migrate` из `backend/` с настроенным `DATABASE_URL`.

После изменения Go/SQL, из корня проекта:

~~~sh
docker compose --env-file .env -f infra/compose.yaml build
docker compose --env-file .env -f infra/compose.yaml stop api
docker compose --env-file .env -f infra/compose.yaml run --rm migrate
# Только после успешной миграции:
docker compose --env-file .env -f infra/compose.yaml up -d api
~~~

Runner обычно завершен, поэтому нужен **`run --rm migrate`**, а не `exec migrate`. `restart` не пересобирает образ и не применяет SQL. Перед изменениями базы сделайте backup.

<a id="deployment"></a>
## 🌐 Бесплатный сервер, HTTPS и Android release

Основной вариант для публичного тестирования — **Vercel Hobby + Neon Free**. Vercel запускает Go API и выдаёт HTTPS-адрес `*.vercel.app`; Neon хранит PostgreSQL, включая пользователей, сессии и операции. Покупать сервер, домен или S3-хранилище не нужно. Подробная настройка, ограничения бесплатных тарифов и восстановление описаны в **[инструкции бесплатного размещения](docs/FREE_HOSTING.md)**.

**Действующий API:** [`https://moneyflow-api-two.vercel.app/`](https://moneyflow-api-two.vercel.app/healthz). Проверки: [`/healthz`](https://moneyflow-api-two.vercel.app/healthz) и [`/readyz`](https://moneyflow-api-two.vercel.app/readyz). Корневой путь не содержит сайта: приложение обращается к `/api/v1/`.

Для запуска приложения с этим сервером откройте `android/` в Android Studio и задайте `MONEYFLOW_API_URL=https://moneyflow-api-two.vercel.app/` в своих локальных Gradle properties, затем нажмите **Run**. Или из `android/` установите debug APK на подключённый телефон / эмулятор:

~~~powershell
.\gradlew.bat :app:installDebug -PMONEYFLOW_API_URL=https://moneyflow-api-two.vercel.app/
~~~

Для этой сборки локальные PostgreSQL и Go API запускать не нужно. Зарегистрируйте отдельный тестовый профиль; данные сохраняются на публичном сервере.

В Vercel проект использует Root Directory **`backend`**, конфигурацию [backend/vercel.json](backend/vercel.json) и команды:

~~~sh
go build -buildvcs=false -o server ./cmd/api
~~~

Миграции запускаются отдельно **до** публикации обновлённого API. Для Go API требуется pooled-строка Neon с проверкой TLS; для миграций и резервных копий используется direct-строка. Настройки пула `4 / 0 / 30s` и отсутствие `METRICS_PORT` позволяют базе засыпать при простое.

### Мониторинг и альтернативный VPS

[Free API monitoring](.github/workflows/free-monitor.yml) проверяет HTTPS, срок сертификата, `/healthz`, `/readyz` и свежесть успешной резервной копии **четыре раза в сутки**. Результат доступен в GitHub Actions. Частые проверки не держат Neon постоянно активным. Бесплатные тарифы имеют квоты и не дают круглосуточную гарантию доступности.

Размещение проверено через регистрацию, счета, категории, запись операций и месячную аналитику. [Мониторинг API и backup прошёл успешно](https://github.com/drissakov/moneyflow/actions/runs/38237719597); [CI](https://github.com/drissakov/moneyflow/actions/runs/38237715521) проверил Go / PostgreSQL и сборку, lint и тесты Android.

Для отдельного Ubuntu VPS подготовлен [production runbook](docs/PRODUCTION.md): Caddy, PostgreSQL, приватный Prometheus, Grafana и Alertmanager, автоматический restic backup в S3-совместимое хранилище и изолированная проверка восстановления. [Production Compose](infra/compose.production.yaml) запускается отдельно от локального файла. Секреты хранятся в `secrets/`; перед миграциями скрипт требует проверенную резервную копию. Мониторинг доступен через SSH-туннель, а наружу открываются только HTTP/HTTPS. Этот вариант требует своего VPS и не включён в бесплатное размещение.

### Signed APK / AAB

Из `android/`:

~~~powershell
.\gradlew.bat :app:assembleRelease -PMONEYFLOW_RELEASE_API_URL=https://api.your-domain.com/
~~~

Default release URL `https://api.example.invalid/` не работает. **В проекте нет signingConfig или keystore**: команда создает unsigned release APK. Для распространения используйте Android Studio → **Build → Generate Signed App Bundle / APK**, собственный ключ и тот же Gradle property для URL (например, задайте его в локальном `android/gradle.properties` перед сборкой). Ключ и пароли храните вне репозитория.

Release package: `com.moneyflow.app`; HTTP запрещен. Перед публикацией проверяйте актуальные требования магазина и версии SDK/зависимостей.

<a id="backups"></a>
## 💾 Backup и восстановление

Для бесплатного сервера [Free encrypted database backup](.github/workflows/free-backup.yml) запускается ежедневно в **03:17 UTC**. Он получает согласованный PostgreSQL snapshot, восстанавливает dump в отдельную PostgreSQL, сравнивает отпечатки таблиц и шифрует архив через **age**. В закрытом **draft release** GitHub сохраняются последние **7 копий за 7 дней**, каждая до 20 MiB. Черновик не публикуется; автоматизация не использует платное хранилище артефактов Actions для backup.

Приватный ключ восстановления хранится отдельно от GitHub и Vercel. Публичный ключ задаётся переменной `MONEYFLOW_BACKUP_AGE_RECIPIENT`; URI базы — секретом `MONEYFLOW_BACKUP_DATABASE_URL`. Инструкции скачивания, проверки SHA-256, расшифровки и восстановления находятся в [FREE_HOSTING.md](docs/FREE_HOSTING.md). Расписание GitHub может задерживаться и отключается при длительной неактивности репозитория; монитор проверяет возраст фактической копии.

[Первая копия действующей Neon БД создана успешно](https://github.com/drissakov/moneyflow/actions/runs/38236518827). После скачивания проверены SHA-256, расшифровка локальным приватным ключом и восстановление в новую PostgreSQL 18: отпечатки всех восьми таблиц совпали. [Проверки сценариев backup](https://github.com/drissakov/moneyflow/actions/runs/38236515404) также проверяют отказ с неправильным ключом и очистку временных незашифрованных файлов.

Ниже сохранён ручной сценарий для **локальной** базы. Для VPS используется отдельный [restic-сценарий](infra/backup/README.md).

Dump создается в контейнере, затем копируется на компьютер: бинарный архив не проходит через `>` в Windows PowerShell.

<details>
<summary><b>🪟 PowerShell: backup локальной базы</b></summary>

Из корня:

~~~powershell
New-Item -ItemType Directory -Force backups | Out-Null
$taskBackupName = 'moneyflow-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.dump'
docker compose --env-file .env -f infra/compose.yaml exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/moneyflow.dump'
# Копируйте только после успешного pg_dump.
docker compose --env-file .env -f infra/compose.yaml cp db:/tmp/moneyflow.dump "backups/$taskBackupName"
~~~

</details>

<details>
<summary><b>🐧 / 🍎 shell: backup локальной базы</b></summary>

~~~sh
mkdir -p backups
task_backup_name="moneyflow-$(date +%Y%m%d-%H%M%S).dump"
docker compose --env-file .env -f infra/compose.yaml exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/moneyflow.dump' &&
docker compose --env-file .env -f infra/compose.yaml cp db:/tmp/moneyflow.dump "backups/$task_backup_name"
~~~

</details>

### Проверка в отдельной базе

Замените имя архива. `moneyflow_restore_check` должна быть новой базой; при повторе выберите другое имя. Команды для PowerShell / shell:

~~~sh
docker compose --env-file .env -f infra/compose.yaml cp backups/YOUR_BACKUP.dump db:/tmp/moneyflow-restore.dump
docker compose --env-file .env -f infra/compose.yaml exec -T db sh -c 'createdb -U "$POSTGRES_USER" moneyflow_restore_check'
docker compose --env-file .env -f infra/compose.yaml exec -T db sh -c 'pg_restore --exit-on-error --single-transaction --no-owner -U "$POSTGRES_USER" -d moneyflow_restore_check /tmp/moneyflow-restore.dump'
docker compose --env-file .env -f infra/compose.yaml exec -T db psql -U moneyflow -d moneyflow_restore_check -c 'SELECT count(*) AS accounts FROM accounts;'
~~~

В последней команде `-U moneyflow` соответствует стандартному `POSTGRES_USER`; если выбрали другую роль, подставьте ее имя. Прямой вызов `psql` избегает вложенных кавычек и подходит Windows PowerShell. Проверьте ключевые таблицы, суммы и миграции. Для production меняйте **оба** аргумента на `--env-file .env.production -f infra/compose.production.yaml`.

<details>
<summary><b>🔴 Production restore: заменяет основную базу</b></summary>

Это удалит текущую основную базу и заменит ее архивом. Сначала сохраните копию текущего состояния, проверьте архив в отдельной базе и исключите другие записи в PostgreSQL. Выполняйте по шагам, останавливаясь при любой ошибке.

~~~sh
docker compose --env-file .env.production -f infra/compose.production.yaml stop caddy api
docker compose --env-file .env.production -f infra/compose.production.yaml cp backups/YOUR_BACKUP.dump db:/tmp/moneyflow-restore.dump
docker compose --env-file .env.production -f infra/compose.production.yaml exec -T db sh -c 'dropdb -U "$POSTGRES_USER" "$POSTGRES_DB"'
docker compose --env-file .env.production -f infra/compose.production.yaml exec -T db sh -c 'createdb -U "$POSTGRES_USER" -O "$POSTGRES_USER" "$POSTGRES_DB"'
docker compose --env-file .env.production -f infra/compose.production.yaml exec -T db sh -c 'pg_restore --exit-on-error --single-transaction --no-owner -U "$POSTGRES_USER" -d "$POSTGRES_DB" /tmp/moneyflow-restore.dump'
# После успешного восстановления:
docker compose --env-file .env.production -f infra/compose.production.yaml run --rm migrate
# После успешной миграции:
docker compose --env-file .env.production -f infra/compose.production.yaml up -d api caddy
~~~

Сценарий относится к изолированному стеку проекта, а не общей базе других приложений. Не удаляйте volumes для восстановления.

</details>

<a id="troubleshooting"></a>
## 🛠 Частые проблемы

| Симптом | Действие |
|---|---|
| Docker daemon недоступен | Запустите Desktop/Engine, проверьте `docker version`; альтернативно — native Go/PostgreSQL |
| Порт 5432 / 8080 занят | Остановите лишний процесс либо согласованно измените mapping/URL |
| `migrate` Exited (0) | Успех; для другого exit code смотрите `logs migrate` |
| Readiness 503 | Проверьте логи базы, DSN и пароль |
| Readiness OK, таблицы отсутствуют | Примените миграции: ping базы не проверяет схему |
| `DATABASE_URL is required` | Задайте окружение или используйте `start-api.ps1` |
| Смена .env не поменяла пароль БД | Существующий volume хранит credentials; измените роль и DSN согласованно |
| На `/` 404 | Используйте `/healthz`, `/readyz` или документированный маршрут |
| SDK location not found | Проверьте `ANDROID_HOME` / абсолютный `sdk.dir` |
| Gradle Java / SDK ошибка | JDK 17, SDK Platform 35, Build Tools 35.0.0 |
| `adb` не найден / unauthorized | PATH platform-tools / подтверждение USB на телефоне |
| Эмулятор не видит API | Адрес `10.0.2.2`, API должен работать на компьютере |
| Телефон не видит API | USB reverse + localhost APK; либо LAN IP / сеть / firewall |
| URL изменили, приложение использует старый | Пересоберите и переустановите; завершающий slash обязателен |
| HTTP не работает в release | Нужен HTTPS, действующий домен/сертификат |
| 401 после перерыва | Сессия истекла; войдите снова |
| 409 при повторе | Исходный payload и key должны быть теми же |
| `rates_unavailable` / неполный общий итог | Проверьте сеть сервера, `FRANKFURTER_URL`, даты и `missing_currencies`; не считайте partial полным итогом |
| `stale=true` у курса | Показана ограниченная cached-оценка; проверьте `rate_date` / `fetched_at` и доступность провайдера |
| После обновления нет FX-таблиц | Пересоберите runner и примените миграцию `002` перед API |
| Новая операция блокируется pending | Разрешите сохраненный запрос ручным повтором |
| CI APK не видит API с телефона | Он собран для эмулятора; нужен APK с USB/LAN/HTTPS URL |
| Checksum миграции не совпадает | Верните примененный файл, добавьте новую миграцию |

<a id="roadmap"></a>
## 🟠 Следующие этапы

- [x] Android MVP и серверная запись операций.
- [x] Точные деньги, изоляция пользователей и повтор без дублей.
- [x] Кэш, SQL-миграции, CI и Docker/HTTPS основа.
- [x] 11 валют, Frankfurter v2, конвертер и общий баланс в выбранной валюте.
- [x] Публичный deploy, мониторинг, автоматические зашифрованные backup.
- [ ] Signed APK для передачи знакомым.
- [ ] Подтверждение email и восстановление пароля.
- [x] Стандартные и свои категории, подкатегории расходов, месячная аналитика с диаграммой.
- [x] PDF-импорт Kaspi Gold / Halyk с проверкой, категориями, историей и отменой.
- [x] Бюджеты, регулярные платежи, напоминания и «Можно потратить» — первый блок P1.
- [ ] Правила автокатегорий пользователя и очередь разбора импортов.
- [ ] Другие банки Казахстана и расширенная аналитика.
- [x] Полная история, редактирование, удаление и восстановление с контролем конфликтов.
- [x] Управление счетами и категориями, валютные переводы с комиссией.
- [ ] Офлайн-очередь и WorkManager.

Основная ветка — **`main`**. При изменениях проверяйте backend/Android командами выше. Не коммитьте `.env`, signing keys, SDK-пути, дампы и сгенерированные build-файлы.

<div align="center">

**💚 Android UI · 💙 Go API · 💛 PostgreSQL**<br>
[Код](https://github.com/drissakov/moneyflow) · [CI и APK](https://github.com/drissakov/moneyflow/actions) · [Контракт API](api/openapi.yaml)

</div>
