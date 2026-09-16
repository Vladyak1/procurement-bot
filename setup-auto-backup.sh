#!/bin/bash
# Скрипт настройки автоматического ежедневного бэкапа
# Использование: ./setup-auto-backup.sh

set -e

# Цвета
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${YELLOW}==================================="
echo "Настройка автоматического бэкапа"
echo "===================================${NC}"
echo ""

# Получить абсолютный путь к проекту
PROJECT_DIR=$(pwd)
BACKUP_SCRIPT="${PROJECT_DIR}/backup.sh"

# Проверить что backup.sh существует
if [ ! -f "${BACKUP_SCRIPT}" ]; then
    echo -e "${RED}Ошибка: backup.sh не найден в ${PROJECT_DIR}${NC}"
    exit 1
fi

# Сделать скрипт исполняемым
chmod +x "${BACKUP_SCRIPT}"

# Проверить существующие cron задачи
EXISTING_CRON=$(crontab -l 2>/dev/null | grep "backup.sh" || echo "")

if [ ! -z "$EXISTING_CRON" ]; then
    echo -e "${YELLOW}Найдена существующая задача cron для бэкапа:${NC}"
    echo "$EXISTING_CRON"
    echo ""
    read -p "Заменить её? (y/n): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Отменено"
        exit 0
    fi
    # Удалить старую задачу
    crontab -l 2>/dev/null | grep -v "backup.sh" | crontab - || true
fi

# Добавить новую cron задачу
echo -e "${YELLOW}Настройка ежедневного бэкапа в 03:00...${NC}"

# Добавить в crontab
(crontab -l 2>/dev/null; echo "# Procurement Bot - Ежедневный бэкап в 03:00") | crontab -
(crontab -l 2>/dev/null; echo "0 3 * * * cd ${PROJECT_DIR} && ${BACKUP_SCRIPT} >> ${PROJECT_DIR}/logs/backup.log 2>&1") | crontab -

echo -e "${GREEN}✓ Автоматический бэкап настроен!${NC}"
echo ""
echo "Расписание:"
echo "  - Каждый день в 03:00 (ночью, когда нагрузка минимальна)"
echo "  - Лог: ${PROJECT_DIR}/logs/backup.log"
echo "  - Бэкапы: ${PROJECT_DIR}/backups/"
echo "  - Хранится: 7 последних бэкапов"
echo ""
echo "Текущие задачи cron:"
crontab -l | grep -v "^#" | grep -v "^$"
echo ""
echo -e "${GREEN}==================================="
echo "Настройка завершена!"
echo "===================================${NC}"
echo ""
echo "Для тестирования запустите вручную:"
echo "  ${BACKUP_SCRIPT}"
echo ""
echo "Для просмотра логов:"
echo "  tail -f ${PROJECT_DIR}/logs/backup.log"
echo ""
