<div align="center">

<img src="docs/assets/moneyflow-banner.svg" alt="MoneyFlow — Android + Go + PostgreSQL" width="100%">

# 💸 MoneyFlow

**Личные финансы в Android-приложении с собственным сервером.**<br>
Счета, доходы, расходы и точные балансы — Kotlin / Jetpack Compose + Go / PostgreSQL.

[![CI](https://github.com/drissakov/moneyflow/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/drissakov/moneyflow/actions/workflows/ci.yml) [![Go](https://img.shields.io/badge/Go-1.25%2B-00ADD8?logo=go&logoColor=white)](backend/go.mod) [![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7F52FF?logo=kotlin&logoColor=white)](android/build.gradle.kts) [![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](android/app/build.gradle.kts) [![Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=jetpackcompose&logoColor=white)](android/app/src/main/java/com/moneyflow/app/ui/MoneyFlowApp.kt) [![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)](infra/compose.yaml) [![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](infra/compose.yaml) [![Status](https://img.shields.io/badge/Статус-MVP-F59E0B)](#features)

[🚀 Быстрый старт](#quick-start) · [📱 Android](#android) · [🔌 API](#api) · [🌐 VPS](#deployment) · [🛠 Решение проблем](#troubleshooting)

<img src="docs/assets/android-dashboard.png" alt="MoneyFlow: счет и расход в Android-приложении" width="320">

</div>

<a id="overview"></a>
## 🟣 О проекте

MoneyFlow — pet-проект для учета личных финансов с русским Android-интерфейсом. Сервер принимает операции, проверяет владельца данных и сохраняет их в PostgreSQL. Приложение отображает локальный кэш Room и обновляет его после ответа сервера.

Версия **0.1.0** позволяет зарегистрироваться, создать счет, записать доход или расход и получить точный баланс. Ниже описаны запуск на компьютере, подключение телефона, API, проверки и подготовка сервера на VPS.

### Навигация

| Начало работы | Устройство проекта | Эксплуатация |
|---|---|---|
| [Возможности](#features) | [Архитектура и стек](#architecture) | [Проверки и CI](#testing) |
| [Требования](#requirements) | [Структура](#structure) | [Миграции](#migrations) |
| [Docker Compose](#quick-start) | [Настройки](#configuration) | [VPS / HTTPS / release](#deployment) |
| [Go + PostgreSQL без Docker](#native-server) | [API и примеры](#api) | [Резервные копии](#backups) |
| [Android Studio / CLI / телефон](#android) | [Деньги, сессии, офлайн](#data-rules) | [Проблемы](#troubleshooting) · [План](#roadmap) |

<a id="features"></a>
## 🟢 Что уже работает

| Возможность | Состояние |
|---|---|
| Регистрация, вход по email / паролю, выход | ✅ Серверные сессии с отзывом и сроком действия |
| Несколько счетов | ✅ Название, валюта, начальный баланс |
| Доходы и расходы | ✅ Сумма, заметка, дата |
| Баланс счета | ✅ Начальный баланс + вся подтвержденная история |
| KZT, USD, EUR, JPY, KWD | ✅ Точное количество дробных знаков |
| Повтор после неопределенного ответа сети | ✅ Сохраненный запрос и ключ идемпотентности |
| Просмотр загруженных данных без сети | ✅ Пока локальная сессия не истекла |
| Разделение пользователей | ✅ Проверки сервера и отдельный локальный кэш |
| SQL-миграции, Docker, конфигурация HTTPS | ✅ В репозитории |
| GitHub Actions | ✅ Backend / PostgreSQL и Android build / lint / tests |

**Ограничения MVP:** в приложении видны последние **50 операций** выбранного счета; API допускает limit 1–100, но курсорной пагинации нет. Баланс учитывает всю историю. Пока нет редактирования/удаления, переводов между счетами, категорий, отчетов, конвертации валют, общего баланса разных валют, подтверждения email и восстановления пароля.

Полноценная очередь офлайн-записей и WorkManager не реализованы. Можно вручную повторить один ранее сохраненный неподтвержденный запрос текущего пользователя; перед следующей операцией нужно разрешить этот запрос.

<a id="architecture"></a>
## 🔵 Архитектура и стек

~~~mermaid
flowchart TD
    UI["📱 Android · Compose / Material 3"] --> VM["ViewModel · StateFlow · Coroutines"]
    VM --> R["Repository"]
    R <--> ROOM[("Room · кэш")]
    R <--> SEC["Keystore · токен / pending"]
    R <-->|"Retrofit / OkHttp · HTTPS / JSON"| EDGE["Caddy · TLS на VPS"]
    EDGE --> API["Go / Gin · REST /api/v1"]
    API --> SVC["Сервисы · auth / accounts / transactions"]
    SVC --> ORM["GORM + SQL"]
    ORM --> PG[("PostgreSQL")]
    MIG["Отдельный migration runner"] --> PG
    classDef android fill:#E8F5E9,stroke:#22C55E,color:#14532D
    classDef local fill:#F3E8FF,stroke:#A855F7,color:#581C87
    classDef backend fill:#E0F2FE,stroke:#0EA5E9,color:#0C4A6E
    classDef database fill:#FEF3C7,stroke:#F59E0B,color:#78350F
    class UI,VM,R android
    class ROOM,SEC local
    class EDGE,API,SVC,ORM,MIG backend
    class PG database
~~~

Локально Android обращается прямо к API на порту 8080. Caddy включается в production для HTTPS. Backend — одно Go-приложение с отдельными пакетами; Redis и микросервисы на этом этапе не требуются.

| Слой | Технологии и версии из проекта |
|---|---|
| Android UI | Kotlin 2.1.21, Jetpack Compose BOM 2025.05.01, Material 3 |
| Данные / состояние | ViewModel, StateFlow, Coroutines 1.10.2, Room 2.7.2 |
| Android сеть | Retrofit 2.11.0, OkHttp 4.12.0, Gson 2.11.0 |
| Сборка | Gradle 8.11.1, AGP 8.9.2, KSP 2.1.21-2.0.1 |
| Backend | Go 1.25+, Gin, GORM; версии в `go.mod` / `go.sum` |
| База / инфраструктура | PostgreSQL 17.11, Docker Compose, Caddy 2.11.7 |
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

Источники: [Android README](android/README.md), [OpenAPI](api/openapi.yaml), [локальный Compose](infra/compose.yaml), [production Compose](infra/compose.production.yaml), [SQL-схема](backend/internal/migrations/sql/001_initial.sql).

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

Регистрация → счет KZT с начальным балансом 5000 → выбор счета → расход 12,99 → обновление. Ожидаемый баланс — **4987,01 KZT**. При неопределенном результате записи повторите сохраненную операцию через кнопку в приложении.

<a id="configuration"></a>
## ⚙️ Настройки окружения

Копируйте шаблоны только при первом запуске. Пароли и `.env` не должны попадать в Git.

| Переменная | Назначение / значение |
|---|---|
| `DATABASE_URL` | Обязательна для Go. Native: хост `localhost`; production Compose: `db` |
| `PORT` | Native API: по умолчанию 8080, допустимо 1–65535 |
| `SESSION_TTL` | По умолчанию `720h` (30 дней), допустимо `1m`–`2160h`. Production-шаблон: `168h` |
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
| GET | `/api/v1/transactions?account_id=UUID&limit=50` | Bearer | 200, `{"transactions":[...]}` |
| POST | `/api/v1/transactions` | Bearer + `Idempotency-Key` | 201, новая / повторно полученная операция |

JSON требует `Content-Type: application/json`. Тело — один объект до **16 KiB**, неизвестные поля отклоняются. Время — RFC3339 с часовым поясом.

| Код | Значение |
|---|---|
| 400 | Некорректный JSON, поля, UUID, сумма, дата или limit |
| 401 | Пароль/токен неверен, сессия отсутствует, истекла или отозвана |
| 404 / 405 | Ресурс отсутствует/чужой или маршрут не существует / неверный метод |
| 409 | Email занят, конфликт ключа идемпотентности или переполнение баланса |
| 413 / 415 | Слишком большое тело / неверный Content-Type |
| 429 | Лимит auth-запросов; учитывайте `Retry-After` |
| 500 / 503 / 504 | Ошибка сервера / база недоступна на readiness / deadline запроса |

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
Send-MoneyFlow POST '/api/v1/auth/logout' -Headers $taskAuth
"PASS: один расход, баланс 4987.01 KZT, сессия отозвана. Пользователь: $taskEmail"
~~~

Повторный вход: `POST /api/v1/auth/login` с теми же `email` / `password` выдаст новый токен. Доход: `kind: "income"`, положительная сумма и **новый** ключ.

Минимальная проверка из shell; `MONEYFLOW_TOKEN` содержит актуальный токен регистрации/входа:

~~~sh
curl --fail http://localhost:8080/readyz
curl --fail http://localhost:8080/api/v1/accounts \
  -H "Authorization: Bearer $MONEYFLOW_TOKEN"
~~~

<a id="data-rules"></a>
## 🧮 Деньги, безопасность и офлайн

### Точные суммы

API передает деньги **строками целых чисел в минимальных единицах**; PostgreSQL хранит `BIGINT`, Android преобразует ввод через `BigDecimal`. Floating point для денег не используется.

| Валюта | Дробных знаков | Сумма UI | `amount_minor` |
|---|---:|---:|---|
| KZT / USD / EUR | 2 | 12,99 | `"1299"` |
| JPY | 0 | 1299 | `"1299"` |
| KWD | 3 | 1,299 | `"1299"` |

- Доход/расход: сумма положительная, от 1 до **10^15**; направление задает `kind`.
- Начальный баланс: отрицательный, нулевой или положительный, модуль ≤ **10^15**.
- Формат канонический: `"0"`, `"1299"`, `"-1299"` при разрешенном минусе. `"01"`, `"-0"`, `"1.5"`, `"1e3"` и JSON-числа вместо строк отклоняются.
- Баланс = `начальный + доходы − расходы`, диапазон signed int64. Переполняющая запись отклоняется атомарно с 409.
- Название счета: 1–100 Unicode-символов после trim; заметка: до 500 символов.
- Валюта берется из счета; `user_id` от клиента для выбора владельца не принимается.

### Сессии и безопасный повтор

Пароль — **12–128 UTF-8 байт**, его пробелы не обрезаются. Сервер хранит salted Argon2id-хеш. Opaque Bearer-токен сохраняется в базе в виде хеша; выход отзывает текущую сессию. Refresh tokens пока нет. Регистрация/вход ограничены общим лимитом **20 запросов в минуту на IP**, счетчик находится в памяти процесса.

Чужие счета/операции возвращают 404. Логи содержат метод, маршрут, статус и длительность; тела запросов и Authorization не журналируются.

Новый финансовый запрос получает UUID `Idempotency-Key`. Сохраняйте ключ и поля до подтверждения: идентичный повтор вернет оригинальный ответ **201**, другой payload с этим ключом — **409**. Операция и результат для повторов сохраняются в одной транзакции PostgreSQL. Создание счета такого контракта не имеет.

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
<summary><b>Android: сборка, 14 unit/contract tests, lint</b></summary>

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
## 🌐 VPS, HTTPS и Android release

[Production Compose](infra/compose.production.yaml) — **самостоятельный** стек; используйте его отдельно от локального файла. Нужны Linux VPS с Docker/Compose, домен API с DNS-записью на VPS и доступные TCP 80/443 для Caddy. PostgreSQL и Go API не публикуют порты наружу.

На VPS:

~~~sh
git clone https://github.com/drissakov/moneyflow.git
cd moneyflow
git switch main
if [ ! -f .env.production ]; then cp .env.production.example .env.production; fi
chmod 600 .env.production
~~~

Отредактируйте файл. Это **образец**, не готовые реквизиты:

~~~dotenv
POSTGRES_USER=moneyflow
POSTGRES_DB=moneyflow
POSTGRES_PASSWORD=REPLACE_WITH_LONG_RANDOM_URL_SAFE_PASSWORD
DATABASE_URL=postgres://moneyflow:REPLACE_WITH_LONG_RANDOM_URL_SAFE_PASSWORD@db:5432/moneyflow?sslmode=disable
PORT=8080
SESSION_TTL=168h
DOMAIN=api.your-domain.com
ACME_EMAIL=you@your-domain.com
~~~

~~~sh
docker compose --env-file .env.production -f infra/compose.production.yaml up --build -d
docker compose --env-file .env.production -f infra/compose.production.yaml ps -a
docker compose --env-file .env.production -f infra/compose.production.yaml logs --tail=100 migrate api caddy
task_attempt=0
until curl --fail --silent --max-time 3 https://api.your-domain.com/readyz >/dev/null; do
  task_attempt=$((task_attempt + 1))
  if [ "$task_attempt" -ge 60 ]; then
    echo 'HTTPS API не готов: проверьте DNS и логи Caddy / API / migrate' >&2
    exit 1
  fi
  sleep 2
done
curl --fail https://api.your-domain.com/readyz
~~~

Caddy получает сертификат и проксирует API. Проверьте DNS, включая AAAA, если она есть. `sslmode=disable` относится к PostgreSQL в приватной Compose-сети; Android использует HTTPS.

API доверяет forwarded-заголовкам только от фиксированного IP Caddy. При конфликте `172.30.50.0/24` добавьте согласованные `PRIVATE_NETWORK_SUBNET` и `CADDY_PRIVATE_IP`: IP должен принадлежать подсети.

### Обновление на VPS

Из корня клона. Сделайте backup, затем:

~~~sh
git pull --ff-only origin main
docker compose --env-file .env.production -f infra/compose.production.yaml build
docker compose --env-file .env.production -f infra/compose.production.yaml stop caddy api
docker compose --env-file .env.production -f infra/compose.production.yaml run --rm migrate
# Только после успешной миграции:
docker compose --env-file .env.production -f infra/compose.production.yaml up -d api caddy
curl --fail https://api.your-domain.com/readyz
~~~

База остается запущенной. При ошибке миграции сначала исправьте ее. CI не выполняет автоматический deploy; push в GitHub сам по себе не создает публичный сервер.

### Signed APK / AAB

Из `android/`:

~~~powershell
.\gradlew.bat :app:assembleRelease -PMONEYFLOW_RELEASE_API_URL=https://api.your-domain.com/
~~~

Default release URL `https://api.example.invalid/` не работает. **В проекте нет signingConfig или keystore**: команда создает unsigned release APK. Для распространения используйте Android Studio → **Build → Generate Signed App Bundle / APK**, собственный ключ и тот же Gradle property для URL (например, задайте его в локальном `android/gradle.properties` перед сборкой). Ключ и пароли храните вне репозитория.

Release package: `com.moneyflow.app`; HTTP запрещен. Перед публикацией проверяйте актуальные требования магазина и версии SDK/зависимостей.

<a id="backups"></a>
## 💾 Backup и восстановление

Автоматический backup, шифрование архивов и их отправка вне VPS **не настроены**. Ниже ручной сценарий. Важные копии храните отдельно от сервера и проверяйте восстановление.

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
| Новая операция блокируется pending | Разрешите сохраненный запрос ручным повтором |
| CI APK не видит API с телефона | Он собран для эмулятора; нужен APK с USB/LAN/HTTPS URL |
| Checksum миграции не совпадает | Верните примененный файл, добавьте новую миграцию |

<a id="roadmap"></a>
## 🟠 Следующие этапы

- [x] Android MVP и серверная запись операций.
- [x] Точные деньги, изоляция пользователей и повтор без дублей.
- [x] Кэш, SQL-миграции, CI и Docker/HTTPS основа.
- [ ] Публичный deploy, мониторинг, автоматические зашифрованные backup.
- [ ] Signed APK/AAB и публикация.
- [ ] Подтверждение email и восстановление пароля.
- [ ] Категории, переводы, месячные отчеты.
- [ ] Полная история и редактирование с контролем конфликтов.
- [ ] Офлайн-очередь и WorkManager.

Основная ветка — **`main`**. При изменениях проверяйте backend/Android командами выше. Не коммитьте `.env`, signing keys, SDK-пути, дампы и сгенерированные build-файлы.

<div align="center">

**💚 Android UI · 💙 Go API · 💛 PostgreSQL**<br>
[Код](https://github.com/drissakov/moneyflow) · [CI и APK](https://github.com/drissakov/moneyflow/actions) · [Контракт API](api/openapi.yaml)

</div>
