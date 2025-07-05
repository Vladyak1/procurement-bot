# Procurement Bot

Бот для парсинга лотов недвижимости с torgi.gov.ru и отправки в Telegram.

## Требования
- Java 17
- Maven
- SQLite
- Telegram Bot Token

## Настройка
1. Укажи в `src/main/resources/application.properties`:
~~~
bot.token=YOUR_BOT_TOKEN 
bot.chatId=YOUR_CHAT_ID 
parser.url=https://torgi.gov.ru/new/api/public/lotcards/rss?dynSubjRF=80&lotStatus=PUBLISHED,APPLICATIONS_SUBMISSION&byFirstVersion=true
~~~
2. Скомпилируй проект: `mvn clean install`
3. Запусти: `java -jar target/procurement-bot-1.0-SNAPSHOT.jar`

## Структура
- `src/main/java/com/example/procurement/`: Java-классы.
- `src/main/resources/`: Конфигурации.
- `cache/`: Кэш HTML-страниц.