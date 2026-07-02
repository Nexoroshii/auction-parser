# AuctionNotifier

Десктопное приложение для Windows: отслеживает новые лоты на автоаукционах
(**Copart**, **IAAI**) и отправляет их в **Telegram**. Работает в фоне,
проверяет сайты по расписанию, отправляет только действительно новые лоты.

Стек: **Java 21 + Spring Boot 4** (DI/конфигурация) + **JavaFX** (UI) + **SQLite**.

> Статус:
> - Сквозной каркас (все слои) — готов, компилируется и запускается.
> - **IAAI-парсер — реализован** (HTTP + jsoup, без браузера) и проверен на живой
>   странице: извлекаются lot/VIN/год/марка/модель/повреждения/пробег/Title/дата/
>   фото/URL. Полная карточка (немаскированный VIN, оценки, вся галерея) — под
>   логином, следующий шаг.
> - **Copart — логин реализован** (Playwright/Chromium, `provider/copart/`).
>   Проверено на живом сайте: headless-браузер **проходит Incapsula**, найдены и
>   зашиты рабочие селекторы модалки логина, отработан порядок (cookie-баннер →
>   открыть модалку → заполнить → submit). Сам вход под аккаунтом требует ваших
>   учёток для финальной проверки. Извлечение лотов — следующий шаг.

## Архитектура (слои)

```
config/      AppProperties, DatabaseConfig (SQLite DataSource)
model/       Lot, SearchFilter, TelegramSettings, AppSettings, AuctionType
repository/  LotRepository, FilterRepository, SettingsRepository (JdbcTemplate)
provider/    AuctionProvider (точка расширения), CopartProvider, IaaiProvider, DemoAuctionProvider
telegram/    TelegramClient (Bot API), MessageFormatter
service/     MonitoringService (ядро), FilterService, SettingsService,
             TelegramNotifier, ExportService, UiLogService, MonitoringStatus
scheduler/   MonitoringScheduler (ScheduledExecutorService)
ui/          JavaFxApplication, MainView, FilterDialog, SettingsDialog,
             LogWindow, HistoryWindow
util/        RetryableHttpClient (ретраи + backoff)
```

### Добавление нового аукциона (Manheim, ACV, Impact, CrashedToys)

Единственная точка расширения — интерфейс `provider/AuctionProvider`.
Достаточно:

1. добавить константу в `AuctionType`;
2. создать `@Component`, реализующий `AuctionProvider`.

Пайплайн мониторинга сам подхватит новый бин (Spring внедряет
`List<AuctionProvider>`). Изменять существующий код не требуется (OCP/SOLID).

## Данные

SQLite в `%USERPROFILE%/.auctionnotifier/auctionnotifier.db`:

- `lots(lot_id, auction, url, date_found, sent, details_json)` —
  уникальность `(lot_id, auction)` гарантирует, что лот не отправится дважды;
- `filters(...)` — фильтры поиска (базовые + расширенные критерии);
- `settings(key, value)` — токен/chat id Telegram и настройки приложения.

## Запуск (разработка)

```
mvnw.cmd spring-boot:run     # запуск с UI
mvnw.cmd test                # проверка сборки контекста
```

Чтобы прогнать сквозной пайплайн (dedup → БД → Telegram) без реальных парсеров,
включите демо-провайдер и настройте Telegram в UI:

```yaml
app:
  monitoring:
    demo-provider-enabled: true
```

## Сборка EXE (следующий шаг)

Целевой артефакт — `AuctionNotifier.exe` без установки Java. План: `jlink` +
`jpackage` (тип `app-image`/`exe`) поверх fat-jar от `spring-boot-maven-plugin`,
с включением модулей JavaFX. Требует установленного WiX для `.exe`.

## Результаты исследования сайтов

Эмпирически проверено сетевыми запросами (июль 2026):

- **IAAI** — результаты поиска **server-rendered в HTML**. Фильтр по марке:
  `GET /Search?queryFilterValue={make}&queryFilterGroup=Make`. Каждая карточка
  несёт данные в трёх местах: обработчик `ImageModalClicked(...)` (stock, inv id,
  VIN, branch, год/марка/модель/комплектация, run&drive), обработчик
  `AddDelWatch(...)` (branch, дата аукциона, тип продажи) и спаны
  `span.data-list__value[title="Label: Value"]` (Title, Primary/Secondary Damage,
  Loss, Odometer). Фото — CDN `vis.iaai.com/resizer?imageKeys={stock}~SID~I{n}`.
  Реализовано в `provider/iaai` (`IaaiProvider`, `IaaiHtmlParser`).

- **Copart** — первый же запрос (в т.ч. главная) отдаёт **JS-челлендж Incapsula**
  (`_Incapsula_Resource`), а не JSON/HTML. Чистый HTTP-клиент не проходит. Выбран
  **Playwright-Java (headless Chromium)** — он прозрачно исполняет челлендж
  (проверено: реальная страница ~387 КБ вместо заглушки 1 КБ). Логин — модалка на
  главной; поля только с `data-uname`: `loginPublicloginmodalusername`,
  `loginPublicloginmodalpassword`, кнопка `loginSigninmemberbutton`; десктопный
  триггер скрыт, поэтому модалка раскрывается напрямую через DOM. Реализовано в
  `provider/copart/CopartBrowserManager`. Дальше — чтение внутреннего JSON
  (`/public/lots/search-results`, `/public/data/lotdetails/solr/{lot}`) через
  `context.request()` уже с валидными cookie.

### Playwright: установка браузера

Playwright скачивает Chromium сам при первом запуске логина, либо заранее:

```
mvnw.cmd "org.codehaus.mojo:exec-maven-plugin:3.5.0:java" ^
  "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"
```

Учётные данные Copart вводятся в «Настройки» (кнопка «Проверить вход в Copart»).
Для отладки логина: `app.copart.headless=false` (+ `slow-mo-millis`).

## Что осталось

1. **Copart** — финальная проверка логина под реальным аккаунтом; затем
   извлечение лотов через внутренний JSON (`context.request()` с cookie сессии).
2. **IAAI login** — авторизованная сессия для немаскированного VIN, оценок и
   полной галереи фото; извлечение из карточки `VehicleDetail`.
3. Пагинация и сортировка «новое сверху» для IAAI.
4. Упаковка `AuctionNotifier.exe` (jlink + jpackage; учесть Chromium Playwright).
