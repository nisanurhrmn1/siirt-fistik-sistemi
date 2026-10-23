@echo off
rem Siirt Fistigi - Java (Spring Boot) sunucusunu baslatir ve paneli acar.
rem Gereksinimler: Java 21, PostgreSQL ve proje kokunde .env (python -m scripts.postgres_kurulum ile olusur).
rem Ornek veriyle baslatmak icin:  baslat.bat --fistik.demo-verisi=true   (uretici tablosu bossa yukler)
chcp 65001 >nul
cd /d "%~dp0"

where java >nul 2>nul || (echo Java bulunamadi. Java 21 kurun: https://adoptium.net & pause & exit /b 1)
if not exist ".env" (
    echo .env bulunamadi. Once PostgreSQL kurulumunu yapin: .venv\Scripts\python.exe -m scripts.postgres_kurulum
    pause & exit /b 1
)
if not exist "java-backend\target\siirt-fistik.jar" (
    echo [Derleme] Java projesi derleniyor - ilk seferde bagimliliklar indirilir...
    call java-backend\mvnw.cmd -q -B -f java-backend\pom.xml -DskipTests package || goto :hata
)

echo.
echo   Panel   : http://127.0.0.1:8000/
echo   Swagger : http://127.0.0.1:8000/docs
echo   Durdurmak icin Ctrl+C
echo.
if not "%TARAYICI_ACMA%"=="1" start "" /b cmd /c "timeout /t 25 >nul & start http://127.0.0.1:8000/"
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar java-backend\target\siirt-fistik.jar %*
goto :eof

:hata
echo Derleme basarisiz oldu.
pause
exit /b 1
