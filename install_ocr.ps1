$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$pythonPath = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $pythonPath)) { throw 'Сначала запустите start.bat один раз.' }
Write-Host 'Установка локального OCR для CPU. Фото не отправляются в сеть.'
Write-Host 'PaddlePaddle подбирается по версии Python и Windows. Если pip сообщит, что совместимой сборки нет, основной движок пока не будет установлен; приложение останется рабочим, а запасным движком станет Tesseract 5 после его установки.'
& $pythonPath -m pip install -r ocr-requirements.txt
if ($LASTEXITCODE -ne 0) { throw 'PaddleOCR не установлен. Основное приложение продолжает работать; см. сообщение pip.' }
Write-Host 'PaddleOCR установлен. Модели будут скачаны однократно при первом запуске OCR, затем работа идёт без сети.'
