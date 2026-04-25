@echo off
cd /d "%~dp0"
set PORT=8080

where java >nul 2>nul
if errorlevel 1 (
  echo Java is not installed or not added to PATH.
  echo Install Java JDK 17 or newer, then run this file again.
  pause
  exit /b 1
)

where javac >nul 2>nul
if errorlevel 1 (
  echo Java compiler javac was not found.
  echo Please install Java JDK 17 or newer. A JRE alone is not enough.
  pause
  exit /b 1
)

echo Compiling Resume RAG AI...
javac -d out src\main\java\com\resumerag\ResumeRagApp.java
if errorlevel 1 (
  echo.
  echo Compilation failed. Make sure Java JDK 17 or newer is installed.
  pause
  exit /b 1
)

netstat -ano | findstr /R /C:":8080 .*LISTENING" >nul
if not errorlevel 1 (
  set PORT=8081
  echo Port 8080 is already in use. Trying port 8081 instead.
)

netstat -ano | findstr /R /C:":8081 .*LISTENING" >nul
if "%PORT%"=="8081" if not errorlevel 1 (
  set PORT=8082
  echo Port 8081 is also in use. Trying port 8082 instead.
)

echo.
echo Starting Resume RAG AI at http://localhost:%PORT%
echo Keep this window open while using the app.
echo Press Ctrl+C to stop.
start "" "http://localhost:%PORT%"
java -cp out com.resumerag.ResumeRagApp
echo.
echo The app stopped.
pause
