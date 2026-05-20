#!/bin/bash
# Скрипт создания бэкапа базы данных и конфигурации
# Использование: ./backup.sh

set -e

# Настройки
BACKUP_DIR="backups"
DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_NAME="procurement-bot-backup-${DATE}"
BACKUP_PATH="${BACKUP_DIR}/${BACKUP_NAME}"

# Количество бэкапов для хранения (старые будут удалены)
KEEP_BACKUPS=7

# Цвета
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${YELLOW}==================================="
echo "Создание бэкапа"
echo "===================================${NC}"

# Создать директорию для бэкапов
mkdir -p "${BACKUP_DIR}"

# Создать временную директорию для архива
mkdir -p "${BACKUP_PATH}"

# Копировать базу данных
echo -e "${YELLOW}[1/4] Копирование базы данных...${NC}"
if [ -f data/procurements.db ]; then
    cp data/procurements.db "${BACKUP_PATH}/"
    echo -e "${GREEN}✓ База данных скопирована${NC}"
else
    echo "⚠ База данных не найдена, пропускаем"
fi

# Копировать .env файл
echo -e "${YELLOW}[2/4] Копирование конфигурации...${NC}"
if [ -f .env ]; then
    cp .env "${BACKUP_PATH}/"
    echo -e "${GREEN}✓ .env скопирован${NC}"
else
    echo "⚠ .env не найден, пропускаем"
fi

# Копировать логи (последние)
echo -e "${YELLOW}[3/4] Копирование последних логов...${NC}"
if [ -d logs ]; then
    mkdir -p "${BACKUP_PATH}/logs"
    # Копируем только последний лог файл (не все, чтобы архив был маленьким)
    if [ -f logs/procurement-bot.log ]; then
        cp logs/procurement-bot.log "${BACKUP_PATH}/logs/"
        echo -e "${GREEN}✓ Логи скопированы${NC}"
    fi
else
    echo "⚠ Логи не найдены, пропускаем"
fi

# Создать архив
echo -e "${YELLOW}[4/4] Создание архива...${NC}"
cd "${BACKUP_DIR}"
tar -czf "${BACKUP_NAME}.tar.gz" "${BACKUP_NAME}/"
rm -rf "${BACKUP_NAME}/"
cd ..

BACKUP_SIZE=$(du -h "${BACKUP_DIR}/${BACKUP_NAME}.tar.gz" | cut -f1)

echo -e "${GREEN}✓ Бэкап создан: ${BACKUP_DIR}/${BACKUP_NAME}.tar.gz (${BACKUP_SIZE})${NC}"

# Удалить старые бэкапы (оставить только последние N)
echo -e "${YELLOW}Очистка старых бэкапов...${NC}"
cd "${BACKUP_DIR}"
ls -t procurement-bot-backup-*.tar.gz 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm --
BACKUPS_COUNT=$(ls -1 procurement-bot-backup-*.tar.gz 2>/dev/null | wc -l)
cd ..
echo -e "${GREEN}✓ Храним последние ${BACKUPS_COUNT} бэкапов${NC}"

echo ""
echo -e "${GREEN}==================================="
echo "Бэкап завершен!"
echo "===================================${NC}"
echo ""
echo "Файл: ${BACKUP_DIR}/${BACKUP_NAME}.tar.gz"
echo "Размер: ${BACKUP_SIZE}"
echo ""
echo "Для скачивания на локальную машину:"
echo "  scp user@server:~/procurement-bot/${BACKUP_DIR}/${BACKUP_NAME}.tar.gz ./backups/"
echo ""
