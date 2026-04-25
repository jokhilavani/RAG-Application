Set-Location $PSScriptRoot
$env:PORT = "8080"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "Java is not installed or not added to PATH."
    Write-Host "Install Java JDK 17 or newer, then run this file again."
    Read-Host "Press Enter to exit"
    exit 1
}

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    Write-Host "Java compiler javac was not found."
    Write-Host "Please install Java JDK 17 or newer. A JRE alone is not enough."
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host "Compiling Resume RAG AI..."
javac -d out src/main/java/com/resumerag/ResumeRagApp.java
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Compilation failed. Make sure Java JDK 17 or newer is installed."
    Read-Host "Press Enter to exit"
    exit 1
}

if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    $env:PORT = "8081"
    Write-Host "Port 8080 is already in use. Trying port 8081 instead."
}

if ($env:PORT -eq "8081" -and (Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue)) {
    $env:PORT = "8082"
    Write-Host "Port 8081 is also in use. Trying port 8082 instead."
}

Write-Host ""
Write-Host "Starting Resume RAG AI at http://localhost:$env:PORT"
Write-Host "Keep this window open while using the app."
Write-Host "Press Ctrl+C to stop."
Start-Process "http://localhost:$env:PORT"
java -cp out com.resumerag.ResumeRagApp
Write-Host ""
Write-Host "The app stopped."
Read-Host "Press Enter to exit"
