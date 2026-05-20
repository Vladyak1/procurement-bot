@echo off
chcp 65001 > nul
echo ========================================
echo  Остановка Procurement Bot
echo ========================================
echo.

REM Ищем процессы Java
echo Поиск процессов бота...
echo.

REM Показываем список java процессов
tasklist /FI "IMAGENAME eq java.exe" /FO TABLE 2>nul | findstr "java.exe"
if %ERRORLEVEL% NEQ 0 (
    echo ℹ Активных Java-процессов не найдено
    echo.
    pause
    exit /b 0
)

echo.
echo Остановка всех Java-процессов (включая бота)...
taskkill /F /IM java.exe 2>nul

if %ERRORLEVEL% EQU 0 (
    echo ✓ Процессы остановлены
    echo.
    echo Ожидание закрытия соединений (3 сек)...
    timeout /t 3 /nobreak > nul
    echo ✓ Готово
) else (
    echo ✗ Не удалось остановить процессы
)

echo.
echo ========================================
echo  Остановка завершена
echo ========================================
echo.
pause

