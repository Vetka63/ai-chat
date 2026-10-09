param(
    [ValidateSet('qwen3:4b-q4_K_M', 'qwen3:4b-fp16')][string]$Model = 'qwen3:4b-q4_K_M',
    [switch]$Cloud,
    [switch]$Build,
    [ValidateRange(1,10000)][int]$MinimumFreeDiskGiB = 20,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'

# Проверяем диск данных Docker, а не диск исходников или Docker Desktop.exe.
# В новой установке настройки по умолчанию могут отсутствовать в settings-store.json.
$taskDockerStorage = Join-Path $env:LOCALAPPDATA 'Docker/wsl'
$taskSettingsFile = Join-Path $env:APPDATA 'Docker/settings-store.json'
if (Test-Path -LiteralPath $taskSettingsFile) {
    $taskSettings = Get-Content -LiteralPath $taskSettingsFile -Raw | ConvertFrom-Json
    if ($taskSettings.CustomWslDistroDir) { $taskDockerStorage = $taskSettings.CustomWslDistroDir }
    elseif ($null -ne $taskSettings.WslEngineEnabled -and -not $taskSettings.WslEngineEnabled -and $taskSettings.DataFolder) {
        $taskDockerStorage = $taskSettings.DataFolder
    }
}
$taskDriveRoot = [System.IO.Path]::GetPathRoot($taskDockerStorage)
if ($taskDriveRoot -notmatch '^[A-Za-z]:\\$') { throw 'Не удалось определить локальный диск данных Docker. Проверьте Disk image location.' }
$taskDockerDrive = Get-PSDrive -Name $taskDriveRoot.Substring(0,1)
$taskFreeGiB = [math]::Round($taskDockerDrive.Free / 1GB,2)
Write-Output "Данные Docker: $taskDockerStorage; свободно $taskFreeGiB GiB; требуется минимум $MinimumFreeDiskGiB GiB."
if ($taskDockerDrive.Free -lt ($MinimumFreeDiskGiB * 1GB)) {
    throw 'Недостаточно свободного места на диске Docker. Освободите место перед загрузкой модели/сборкой; данные автоматически не удаляются.'
}
if ($CheckOnly) { return }

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
        # Docker CLI проверяет сам runtime; проброс localhost-порта Windows может подняться позже.
        try {
            & docker @taskCompose exec -T ollama ollama list 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { $taskReady = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $taskReady) { throw 'Ollama не стала доступна' }
    & docker @taskCompose exec -T ollama ollama pull $Model
    if ($LASTEXITCODE) { throw 'Не удалось скачать выбранную модель' }
    if ($Build) { & docker @taskCompose build backend client; if ($LASTEXITCODE) { throw 'Сборка не прошла' } }
    & docker @taskCompose up -d backend client
    if ($LASTEXITCODE) { throw 'Не удалось запустить приложение' }
    $taskBackendReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 30; $taskAttempt++) {
        try {
            # Проверяем сервер внутри контейнера: Windows localhost/proxy не определяет готовность JVM.
            $taskHealthText = & docker @taskCompose exec -T backend curl --fail --silent --show-error --max-time 2 --header 'Accept: application/json' http://127.0.0.1:8080/actuator/health 2>$null
            if ($LASTEXITCODE -eq 0 -and ($taskHealthText | ConvertFrom-Json).status -eq 'UP') { $taskBackendReady = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $taskBackendReady) { throw 'Контейнеры запущены, но backend ещё не готов. Проверьте docker compose logs backend; повторная генерация не выполнялась.' }
    Write-Output "День 29: http://localhost:8483/#optimization; LOCAL=$Model; cloud=$($env:OPT_CLOUD_ENABLED)"
} finally { Pop-Location }
