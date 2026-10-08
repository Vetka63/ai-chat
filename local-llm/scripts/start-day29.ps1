param(
    [ValidateSet('qwen3:4b-q4_K_M', 'qwen3:4b-fp16')][string]$Model = 'qwen3:4b-q4_K_M',
    [switch]$Cloud,
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    # Не прерываем активный эксперимент молча. На свежей установке endpoint может отсутствовать.
    $taskActive = @()
    try { $taskActive = Invoke-RestMethod 'http://localhost:8482/api/v1/optimization/experiments' -TimeoutSec 3 } catch { }
    if ($taskActive | Where-Object status -eq 'RUNNING') { throw 'Дождитесь завершения эксперимента перед сменой модели.' }
    $env:OPT_LOCAL_MODEL = $Model
    $env:OPT_CLOUD_ENABLED = if ($Cloud) { 'true' } else { 'false' }
    $taskCompose = @('compose','--env-file','../.env','-f','compose.yml','-f','compose.gpu.yml')
    # Перезапуск Ollama выгружает прежнюю модель; сохранённые веса и базы не удаляются.
    & docker @taskCompose stop ollama
    if ($LASTEXITCODE) { throw 'Не удалось остановить Ollama' }
    & docker @taskCompose up -d ollama
    if ($LASTEXITCODE) { throw 'Не удалось запустить Ollama' }
    $taskReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 30; $taskAttempt++) {
        try { Invoke-RestMethod 'http://localhost:11436/api/version' -TimeoutSec 2 | Out-Null; $taskReady = $true; break } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $taskReady) { throw 'Ollama не стала доступна' }
    & docker @taskCompose exec -T ollama ollama pull $Model
    if ($LASTEXITCODE) { throw 'Не удалось скачать выбранную модель' }
    if ($Build) { & docker @taskCompose build backend client; if ($LASTEXITCODE) { throw 'Сборка не прошла' } }
    & docker @taskCompose up -d backend client
    if ($LASTEXITCODE) { throw 'Не удалось запустить приложение' }
    Write-Output "День 29: http://localhost:8483/#optimization; LOCAL=$Model; cloud=$($env:OPT_CLOUD_ENABLED)"
} finally { Pop-Location }
