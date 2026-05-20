@echo off
chcp 65001 > nul
echo ========================================
echo  Перезапуск Procurement Bot
echo ========================================
echo.

REM Останавливаем все java процессы с procurement-bot
echo [1/4] Останавливаем активные процессы бота...
taskkill /F /FI "IMAGENAME eq java.exe" /FI "WINDOWTITLE eq *procurement-bot*" 2>nul
if %ERRORLEVEL% EQU 0 (
    echo ✓ Процессы остановлены
) else (
    echo ℹ Активных процессов не найдено
)

REM Ждём 3 секунды для корректного закрытия соединений
echo.
echo [2/4] Ожидание закрытия соединений (3 сек)...
timeout /t 3 /nobreak > nul
echo ✓ Готово

REM Компилируем проект
echo.
echo [3/4] Компиляция проекта...
set JAVA_HOME=D:\1\IntelliJ IDEA Community Edition 2025.1.3\jbr
"D:\1\IntelliJ IDEA Community Edition 2025.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" clean package -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo ✗ Ошибка компиляции
    pause
    exit /b 1
)
echo ✓ Компиляция завершена

REM Запускаем бота
echo.
echo [4/4] Запуск бота...
start "Procurement Bot" java -jar target\procurement-bot-1.0-SNAPSHOT-shaded.jar
echo ✓ Бот запущен в новом окне

echo.
echo ========================================
echo  Перезапуск завершён успешно!
echo ========================================
echo.
echo Бот работает в отдельном окне
echo Для остановки закройте окно бота или нажмите Ctrl+C
echo.
pause

