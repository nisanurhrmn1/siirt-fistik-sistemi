@echo off
rem Siirt Fistigi - tek tikla demo. Ilk calistirmada sanal ortami kurar (internet gerekir),
rem sonraki calistirmalarda tamamen cevrimdisi calisir.
chcp 65001 >nul
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo [Kurulum] Sanal ortam olusturuluyor...
    python -m venv .venv || goto :hata
    echo [Kurulum] Bagimliliklar yukleniyor...
    ".venv\Scripts\python.exe" -m pip install --disable-pip-version-check -q -r requirements.txt || goto :hata
)

".venv\Scripts\python.exe" -m scripts.demo %*
goto :eof

:hata
echo.
echo Kurulum basarisiz oldu. Python 3.11 veya ustu kurulu ve PATH'te mi?
pause
exit /b 1
