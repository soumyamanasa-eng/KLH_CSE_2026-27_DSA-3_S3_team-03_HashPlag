@echo off
setlocal
cd /d "%~dp0"

echo ==========================================
echo         HashPlag Detect - Launcher
echo ==========================================

echo.
if not exist "src\HashPlagServer.java" (
    echo ERROR: src\HashPlagServer.java was not found.
    echo Extract the complete ZIP and run this file from the HashPlagWeb folder.
    pause
    exit /b 1
)
if not exist "web\index.html" (
    echo ERROR: web\index.html was not found.
    pause
    exit /b 1
)
if not exist "out" mkdir out

echo Compiling Java server...
javac -cp "lib/*" -d out src\HashPlagServer.java
if errorlevel 1 (
    echo.
    echo Compilation failed. Check the error messages above.
    pause
    exit /b 1
)

echo.
echo Starting HashPlag Detect. Keep this window open while using the site.
echo Your browser should open automatically.
echo.
java -cp "out;lib/*" HashPlagServer

echo.
echo HashPlag server stopped.
pause
endlocal
