# procurement-bot

Telegram-бот для мониторинга торгов недвижимостью с портала **torgi.gov.ru** (регион: Севастополь).  
Парсит RSS-ленту, обогащает данные через XHR API, публикует лоты с фото в Telegram-канал и отслеживает изменения статусов.

## Возможности

- Парсинг по расписанию — 10:00 и 18:00 МСК (будни), запуск вручную через команды
- Публикация лотов с фото (до 4 шт.) и ссылкой на Яндекс.Карты
- Атомарная публикация — лот публикуется только при наличии всех фото, иначе пропускается
- Фильтрация по ключевым словам (включающие / исключающие)
- Фильтрация по виду торгов
- Отслеживание изменений: дедлайн подачи заявок, статус лота (состоялся / не состоялся / отменён)
- Автообновление опубликованных сообщений при изменениях
- Уведомления админам о значимых изменениях
- Система вопросов от пользователей через кнопку в посте
- SOCKS5 прокси для доступа к Telegram API (для запуска на российских серверах)
- SQLite с автоматической миграцией схемы

## Быстрый старт

**Требования:** Java 21+, Maven 3.6+

### 1. Конфигурация

`src/main/resources/application.properties`:

```properties
# Telegram Bot
bot.token=YOUR_BOT_TOKEN
bot.admin.group.id=ADMIN_GROUP_ID
bot.parse.group.id=PARSE_GROUP_ID
bot.adminIds=ADMIN_USER_ID1,ADMIN_USER_ID2

# Прокси (опционально, для запуска через SOCKS5)
proxy.host=your.proxy.host
proxy.port=1080

# Парсер torgi.gov.ru
parser.rssUrl=https://torgi.gov.ru/new/api/public/lotcards/rss?dynSubjRF=80&lotStatus=PUBLISHED,APPLICATIONS_SUBMISSION&byFirstVersion=true
parser.xhrUrl=https://torgi.gov.ru/new/api/public/lotcards/

# Фильтры
filter.include.keywords=нежилое,помещение,нежилые,помещения,здание,жилое,квартира
filter.exclude.keywords=автомобиль,камаз,трактор,погрузчик,транспорт
```

### 2. Сборка и запуск

```bash
mvn clean package
java -jar target/procurement-bot-1.0-SNAPSHOT-shaded.jar
```

**Скрипты:** `restart-bot.bat` (остановка → сборка → запуск), `stop-bot.bat`

### 3. Docker

```bash
docker-compose up -d
docker-compose logs -f
```

## Команды бота

| Команда | Описание |
|---------|----------|
| `/parse` | Запустить парсинг (публикует до 2 новых лотов) |
| `/fullparse` | Полный парсинг и публикация всех новых лотов |
| `/teststatus` | Тест обновления статуса лота |
| `/testdeadline <номер> <дата>` | Тест изменения дедлайна |
| `/addadmin <chatId>` | Добавить админа |
| `/removeadmin <chatId>` | Удалить админа |
| `/deletelot` | Удалить лот из БД (требует пересылки сообщения) |

## Структура проекта

```
procurement-bot/
├── src/main/java/com/example/procurement/
│   ├── Main.java                           # Точка входа
│   ├── TelegramBot.java                    # Бот, команды, диалоги
│   ├── RssParser.java                      # Парсинг активных лотов
│   ├── CompletedLotsParser.java            # Парсинг завершённых лотов
│   ├── LotPageParser.java                  # Обогащение данных через XHR API
│   ├── ProcurementProcessingService.java   # Основная логика обработки
│   ├── ParserService.java                  # Координация парсеров
│   ├── LotFilter.java                      # Фильтрация лотов
│   ├── DatabaseManager.java                # SQLite операции
│   └── Config.java                         # Конфигурация
├── src/main/resources/
│   ├── application.properties
│   └── logback.xml
└── data/
    └── procurements.db                     # SQLite БД (создаётся автоматически)
```

## База данных

- `procurements` — лоты (номер, заголовок, адрес, цена, площадь, дедлайн, статус, кадастровый номер, срок договора и др.)
- `message_mappings` — связь лотов с Telegram-сообщениями для обновлений
- `no_match_lots` — лоты, не прошедшие фильтрацию

---

**Java**: 21 · **БД**: SQLite · **Сборка**: Maven Shade (fat JAR)
