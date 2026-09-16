@echo off
REM Скрипт для скачивания бэкапа с сервера (Windows)
REM Требуется: scp (входит в Windows 10/11 или установите Git Bash)
REM Использование: download-backup.bat user@server-ip

setlocal enabledelayedexpansion

set SERVER=%1
set REMOTE_PATH=~/procurement-bot/backups
set LOCAL_BACKUP_DIR=backups_local

if "%SERVER%"=="" (
    echo [ОШИБКА] Укажите адрес сервера
    echo Использование: download-backup.bat user@server-ip
    echo Пример: download-backup.bat root@192.168.1.100
    exit /b 1
)

echo ===================================
echo Скачивание бэкапа с сервера
echo ===================================
echo.

REM Создать локальную директорию
if not exist "%LOCAL_BACKUP_DIR%" mkdir "%LOCAL_BACKUP_DIR%"

REM Получить имя последнего бэкапа
echo [1/3] Получение списка бэкапов...
ssh %SERVER% "ls -t %REMOTE_PATH%/procurement-bot-backup-*.tar.gz 2>/dev/null | head -n 1" > temp_backup_name.txt

if %errorlevel% neq 0 (
    echo [ОШИБКА] Не удалось подключиться к серверу
    del temp_backup_name.txt 2>nul
    exit /b 1
)

set /p LATEST_BACKUP=<temp_backup_name.txt
del temp_backup_name.txt

if "%LATEST_BACKUP%"=="" (
    echo [ОШИБКА] Бэкапы на сервере не найдены!
    echo Создайте бэкап на сервере командой: ./backup.sh
    exit /b 1
)

REM Извлечь только имя файла из полного пути
for %%f in ("%LATEST_BACKUP%") do set BACKUP_FILE=%%~nxf

echo Будет скачан: %BACKUP_FILE%
echo.

REM Скачивание
echo [2/3] Скачивание...
scp "%SERVER%:%REMOTE_PATH%/%BACKUP_FILE%" "%LOCAL_BACKUP_DIR%\%BACKUP_FILE%"

if %errorlevel% neq 0 (
    echo [ОШИБКА] Ошибка при скачивании!
    exit /b 1
)

echo [OK] Бэкап скачан: %LOCAL_BACKUP_DIR%\%BACKUP_FILE%
echo.

echo [3/3] Готово!
echo.
echo ===================================
echo Скачивание завершено!
echo ===================================
echo.
echo Файл сохранен: %LOCAL_BACKUP_DIR%\%BACKUP_FILE%
echo.
echo Для распаковки используйте:
echo   tar -xzf %LOCAL_BACKUP_DIR%\%BACKUP_FILE%
echo Или программу 7-Zip / WinRAR
echo.

endlocal
