@echo off
chcp 65001 >nul
title AuctionNotifier — РАБОТАЕТ
cd /d "%~dp0"

rem --- выбрать java (JAVA_HOME, иначе из PATH) ---
set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

rem --- найти собранный jar ---
set "JAR="
for %%f in ("%~dp0target\auction-parser-*.jar") do set "JAR=%%f"
if not defined JAR (
  echo [ОШИБКА] JAR не найден в папке target\.
  echo Сначала соберите приложение:  mvnw.cmd package -DskipTests
  echo.
  pause
  exit /b 1
)

echo ============================================================
echo   AuctionNotifier ЗАПУЩЕН
echo   Это окно = индикатор работы. Пока оно открыто — работает.
echo   Чтобы ОСТАНОВИТЬ: закройте это окно или нажмите Ctrl+C.
echo ------------------------------------------------------------
echo   Прокси (без VPN): правьте config\application.yaml
echo   JAR: %JAR%
echo ============================================================
echo.

"%JAVA_EXE%" -jar "%JAR%"

echo.
echo ============================================================
echo   AuctionNotifier ОСТАНОВЛЕН.
echo ============================================================
pause >nul
