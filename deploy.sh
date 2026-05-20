#!/bin/bash
# Скрипт автоматического развертывания Procurement Bot

set -e  # Остановка при ошибке

echo "==================================="
echo "Procurement Bot - Auto Deploy"
echo "==================================="

# Цвета для вывода
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Проверка наличия .env файла
if [ ! -f .env ]; then
    echo -e "${RED}Ошибка: Файл .env не найден!${NC}"
    echo -e "${YELLOW}Скопируйте .env.example в .env и заполните значения:${NC}"
    echo "  cp .env.example .env"
    echo "  nano .env"
    exit 1
fi

echo -e "${GREEN}[1/6] Проверка .env файла... ✓${NC}"

# Проверка Docker
if ! command -v docker &> /dev/null; then
    echo -e "${RED}Ошибка: Docker не установлен!${NC}"
    exit 1
fi

if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    echo -e "${RED}Ошибка: Docker Compose не установлен!${NC}"
    exit 1
fi

echo -e "${GREEN}[2/6] Проверка Docker... ✓${NC}"

# Определяем команду docker-compose
if docker compose version &> /dev/null; then
    COMPOSE_CMD="docker compose"
else
    COMPOSE_CMD="docker-compose"
fi

# Обновление кода (если используется git)
if [ -d .git ]; then
    echo -e "${YELLOW}[3/6] Обновление кода из git...${NC}"
    git pull || echo -e "${YELLOW}Не удалось обновить из git, продолжаем с текущей версией${NC}"
else
    echo -e "${YELLOW}[3/6] Git репозиторий не найден, пропускаем обновление${NC}"
fi

# Остановка старой версии
echo -e "${YELLOW}[4/6] Остановка старой версии бота...${NC}"
$COMPOSE_CMD down || true

# Сборка и запуск
echo -e "${YELLOW}[5/6] Сборка и запуск бота...${NC}"
$COMPOSE_CMD up -d --build

# Проверка статуса
echo -e "${YELLOW}[6/6] Проверка статуса...${NC}"
sleep 3
$COMPOSE_CMD ps

echo ""
echo -e "${GREEN}==================================="
echo "Развертывание завершено! ✓"
echo "===================================${NC}"
echo ""
echo "Полезные команды:"
echo "  $COMPOSE_CMD logs -f              # Просмотр логов в реальном времени"
echo "  $COMPOSE_CMD logs -f --tail=100   # Последние 100 строк логов"
echo "  $COMPOSE_CMD ps                   # Статус контейнеров"
echo "  $COMPOSE_CMD down                 # Остановить бота"
echo "  $COMPOSE_CMD restart              # Перезапустить бота"
echo ""
echo -e "${YELLOW}Показываем последние логи (Ctrl+C для выхода):${NC}"
sleep 2
$COMPOSE_CMD logs -f --tail=50
