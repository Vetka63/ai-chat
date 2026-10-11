param()
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskEnv = Join-Path $taskRoot '.env.day30'
if (Test-Path -LiteralPath $taskEnv) {
    Write-Host 'Локальная конфигурация уже есть; пароль не менялся.'
    exit 0
}
$taskBytes = New-Object byte[] 32
$taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $taskRandom.GetBytes($taskBytes) } finally { $taskRandom.Dispose() }
$taskPassword = [Convert]::ToBase64String($taskBytes)
$taskConfig = @"
DAY30_PASSWORD=$taskPassword
DAY30_ALLOWED_ORIGINS=http://127.0.0.1:8583,http://localhost:8583
DAY30_MODELS=qwen3:8b
DAY30_CONTEXT_TOKENS=8192
DAY30_CONTEXT_BYTES=24576
DAY30_GENERATIONS_PER_MINUTE=10
"@
[IO.File]::WriteAllText($taskEnv, $taskConfig, [Text.UTF8Encoding]::new($false))
# Наследование отключается только у нового секретного файла, другие каталоги не меняются.
$taskOwner = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $taskEnv /inheritance:r /grant:r "${taskOwner}:(F)" 'SYSTEM:(F)' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Не удалось ограничить права локального секретного файла.' }
Write-Host "Создан $taskEnv. Пароль не выводится в логи; откройте файл локально для входа в чат."
