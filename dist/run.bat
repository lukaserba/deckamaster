@echo off
rem Deckamaster launcher for Windows. Needs Java 17+ (https://adoptium.net).
cd /d "%~dp0"
where java >nul 2>nul || (echo Deckamaster needs Java 17 or newer: https://adoptium.net & pause & exit /b 1)
echo Starting Deckamaster on http://localhost:8080 ...
start "" /b java -Xmx2g -cp "forge.jar;deckamaster-server.jar" cc.deckamaster.server.Main 8080 web decks
timeout /t 12 /nobreak >nul
start "" http://localhost:8080/
echo Deckamaster is running. Close this window to stop it.
pause >nul
