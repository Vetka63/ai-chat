param([switch]$Build, [switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskEnv = Join-Path $taskRoot '.env.day30'
if (-not (Test-Path -LiteralPath $taskEnv)) { throw 'Сначала запустите Initialize-Day30.ps1.' }
$taskSpace = (Get-PSDrive C).Free / 1GB
if ($taskSpace -lt 20) { throw "На C: осталось $([math]::Round($taskSpace, 1)) ГБ. Нужно минимум 20 ГБ, чтобы не повторить сбой Docker." }
& docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Запустите Docker Desktop.' }
& docker volume inspect ai-chat-local-llm_local-models 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Не найден volume с моделями дней 26/27. Восстановите модели; данные автоматически не удаляются.' }
$taskOtherRuntimes = @(& docker ps --filter ancestor=ollama/ollama:0.35.1 --format '{{.Names}}' | Where-Object { $_ -notlike 'ai-chat-day30-*' })
if ($taskOtherRuntimes.Count -gt 0) { throw "Уже работает другая Ollama: $($taskOtherRuntimes -join ', '). Сначала остановите её явно, чтобы не конкурировать за GPU/общие веса." }
if ($CheckOnly) { Write-Host 'Docker, диск и конфигурация готовы. Контейнеры не изменены.'; exit 0 }
$taskArgs = @('compose', '--env-file', $taskEnv, '-f', (Join-Path $taskRoot 'compose.day30.yml'))
$taskRun = @('up', '-d', '--wait', '--wait-timeout', '240')
if ($Build) { $taskRun += '--build' }
& docker @taskArgs @taskRun
if ($LASTEXITCODE -ne 0) { throw 'Запуск не завершён. Проверьте docker compose logs; volume не удаляйте.' }
Write-Host 'День 30: http://127.0.0.1:8583/. Пока доступ только с этого ПК.'
Write-Host 'Для iPhone после настройки ZeroTier выполните Enable-Day30ZeroTier.ps1 от администратора.'
