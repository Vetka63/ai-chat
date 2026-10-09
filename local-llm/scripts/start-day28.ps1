param(
    [ValidateSet('qwen3:8b','qwen3:4b-q4_K_M')][string]$Model = 'qwen3:8b',
    [switch]$Cloud,
    [switch]$Offline,
    [switch]$Build,
    [ValidateRange(1,10000)][int]$MinimumFreeDiskGiB = 20,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
if ($Cloud -and $Offline) { throw 'Offline и Cloud нельзя включать одновременно.' }
$taskStorage = Join-Path $env:LOCALAPPDATA 'Docker/wsl'
$taskSettingsPath = Join-Path $env:APPDATA 'Docker/settings-store.json'
if (Test-Path -LiteralPath $taskSettingsPath) {
    $taskSettings = Get-Content -LiteralPath $taskSettingsPath -Raw | ConvertFrom-Json
    if ($taskSettings.CustomWslDistroDir) { $taskStorage = $taskSettings.CustomWslDistroDir }
    elseif ($null -ne $taskSettings.WslEngineEnabled -and -not $taskSettings.WslEngineEnabled -and $taskSettings.DataFolder) { $taskStorage = $taskSettings.DataFolder }
}
$taskDriveRoot = [IO.Path]::GetPathRoot($taskStorage)
if ($taskDriveRoot -notmatch '^[A-Za-z]:\\$') { throw 'Не удалось определить диск данных Docker.' }
$taskDrive = Get-PSDrive -Name $taskDriveRoot.Substring(0,1)
Write-Output "Данные Docker: $taskStorage; свободно $([math]::Round($taskDrive.Free/1GB,2)) GiB."
if ($taskDrive.Free -lt $MinimumFreeDiskGiB*1GB) { throw 'Недостаточно места на диске Docker. Данные автоматически не удаляются.' }
if ($CheckOnly) { return }
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $taskCompose = @('compose','--env-file','../.env','-f','compose.yml','-f','compose.gpu.yml')
    # Читаем активные задания из контейнера: это работает и при прежнем offline deployment.
    foreach ($taskEndpoint in @('experiments','indexes')) {
        try {
            $taskJson = & docker @taskCompose exec -T backend curl --fail --silent --max-time 3 "http://127.0.0.1:8080/api/v1/rag/$taskEndpoint" 2>$null
            if ($LASTEXITCODE -eq 0) {
                $taskRecords = $taskJson | ConvertFrom-Json
                if ($taskRecords | Where-Object { $_.status -in @('PREPARING','RUNNING','BUILDING') }) { throw 'Дождитесь завершения RAG-запроса/индексации перед перезапуском.' }
            }
        } catch { if ($_.Exception.Message -like 'Дождитесь*') { throw } }
    }
    $env:LOCAL_LLM_MODELS = $Model
    $env:RAG_CLOUD_ENABLED = if ($Cloud) { 'true' } else { 'false' }
    if ($Offline) {
        $taskCompose += @('-f','compose.offline.yml')
        & docker @taskCompose up -d ollama
        if ($LASTEXITCODE) { throw 'Не удалось запустить локальную Ollama в offline-сети.' }
        $taskReady = $false
        for ($taskAttempt=0; $taskAttempt -lt 30; $taskAttempt++) {
            & docker @taskCompose exec -T ollama ollama list 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { $taskReady=$true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $taskReady) { throw 'Offline Ollama не стала доступна.' }
        $taskModelList = & docker @taskCompose exec -T ollama ollama list
        if ($LASTEXITCODE -ne 0 -or ($taskModelList -join "`n") -notmatch [regex]::Escape($Model) -or ($taskModelList -join "`n") -notmatch 'qwen3-embedding:0\.6b') { throw 'Сначала скачайте обе модели в обычном режиме: offline не обращается в сеть.' }
    } else {
        & docker @taskCompose up -d ollama
        if ($LASTEXITCODE) { throw 'Ollama не запущена.' }
        $taskReady = $false
        for ($taskAttempt=0; $taskAttempt -lt 30; $taskAttempt++) {
            & docker @taskCompose exec -T ollama ollama list 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { $taskReady=$true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $taskReady) { throw 'Ollama не стала доступна.' }
        foreach ($taskWeight in @('qwen3-embedding:0.6b',$Model)) {
            & docker @taskCompose exec -T ollama ollama pull $taskWeight
            if ($LASTEXITCODE) { throw "Не удалось загрузить $taskWeight." }
        }
    }
    if ($Build) { & docker @taskCompose build backend client; if ($LASTEXITCODE) { throw 'Сборка не прошла.' } }
    & docker @taskCompose up -d
    if ($LASTEXITCODE) { throw 'Не удалось запустить приложение.' }
    $taskHealthy = $false
    for ($taskAttempt=0; $taskAttempt -lt 40; $taskAttempt++) {
        try {
            $taskHealth = & docker @taskCompose exec -T backend curl --fail --silent --max-time 2 http://127.0.0.1:8080/actuator/health 2>$null
            if ($LASTEXITCODE -eq 0 -and ($taskHealth | ConvertFrom-Json).status -eq 'UP') { $taskHealthy=$true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $taskHealthy) { throw 'Backend ещё не готов. Проверьте logs; генерация не повторялась.' }
    Write-Output "День 28: http://localhost:8483/#rag; LOCAL=$Model; cloud=$($env:RAG_CLOUD_ENABLED); offline=$([bool]$Offline)."
} finally { Pop-Location }
