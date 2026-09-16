#!/bin/bash
# Скрипт для скачивания бэкапа с сервера на локальную машину
# Использование: ./download-backup.sh [user@server]

# Цвета
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Параметры подключения к серверу
if [ -z "$1" ]; then
    echo -e "${RED}Ошибка: Укажите адрес сервера${NC}"
    echo "Использование: ./download-backup.sh user@server-ip"
    echo "Пример: ./download-backup.sh root@192.168.1.100"
    exit 1
fi

SERVER="$1"
REMOTE_PATH="~/procurement-bot/backups"
LOCAL_BACKUP_DIR="backups_local"

echo -e "${YELLOW}==================================="
echo "Скачивание бэкапа с сервера"
echo "===================================${NC}"

# Создать локальную директорию
mkdir -p "${LOCAL_BACKUP_DIR}"

# Получить список бэкапов на сервере
echo -e "${YELLOW}[1/3] Получение списка бэкапов...${NC}"
BACKUPS=$(ssh "${SERVER}" "ls -t ${REMOTE_PATH}/procurement-bot-backup-*.tar.gz 2>/dev/null || echo 'NONE'")

if [ "$BACKUPS" = "NONE" ]; then
    echo -e "${RED}Бэкапы на сервере не найдены!${NC}"
    echo "Создайте бэкап на сервере командой: ./backup.sh"
    exit 1
fi

# Показать доступные бэкапы
echo -e "${GREEN}Доступные бэкапы:${NC}"
echo "$BACKUPS" | nl
echo ""

# Выбор бэкапа (по умолчанию - самый новый)
LATEST_BACKUP=$(echo "$BACKUPS" | head -n 1)
echo -e "${YELLOW}Будет скачан последний бэкап: ${LATEST_BACKUP}${NC}"
echo ""

# Скачивание
echo -e "${YELLOW}[2/3] Скачивание...${NC}"
REMOTE_FILE="${REMOTE_PATH}/${LATEST_BACKUP}"
LOCAL_FILE="${LOCAL_BACKUP_DIR}/${LATEST_BACKUP}"

scp "${SERVER}:${REMOTE_FILE}" "${LOCAL_FILE}"

if [ $? -eq 0 ]; then
    FILE_SIZE=$(du -h "${LOCAL_FILE}" | cut -f1)
    echo -e "${GREEN}✓ Бэкап скачан: ${LOCAL_FILE} (${FILE_SIZE})${NC}"
else
    echo -e "${RED}Ошибка при скачивании!${NC}"
    exit 1
fi

# Проверка целостности
echo -e "${YELLOW}[3/3] Проверка архива...${NC}"
if tar -tzf "${LOCAL_FILE}" > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Архив корректный${NC}"
else
    echo -e "${RED}⚠ Архив поврежден!${NC}"
    exit 1
fi

echo ""
echo -e "${GREEN}==================================="
echo "Скачивание завершено!"
echo "===================================${NC}"
echo ""
echo "Файл сохранен: ${LOCAL_FILE}"
echo ""
echo "Для восстановления:"
echo "  1. Распакуйте архив: tar -xzf ${LOCAL_FILE}"
echo "  2. Скопируйте файлы на сервер при необходимости"
echo ""
echo "Для автоматического скачивания добавьте в cron:"
echo "  0 3 * * * /path/to/download-backup.sh ${SERVER}"
echo ""
