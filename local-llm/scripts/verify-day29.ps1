param(
    [string]$BaseUrl = 'http://localhost:8482',
    [ValidateSet('LOCAL','DEEPSEEK')][string]$Provider = 'LOCAL',
    [ValidateSet('BASELINE','SPECIALIZED')][string]$PromptProfile = 'BASELINE',
    [ValidateSet('calibration','holdout')][string]$Split = 'holdout',
    [string[]]$CaseIds = @(),
    [string]$CloudModel = 'deepseek-v4-pro',
    [switch]$Skill,
    [ValidateRange(1,3)][int]$Repetitions = 1,
    [ValidateRange(1024,16384)][int]$ContextSize = 8192,
    [ValidateRange(64,8192)][int]$MaxTokens = 1024,
    [ValidateRange(0,2)][double]$Temperature = 0.7,
    [switch]$Thinking,
    [string]$OutputDirectory = ''
)
$ErrorActionPreference = 'Stop'
if (-not $OutputDirectory) { $OutputDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) ('artifacts/day29-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$taskRequestFile = Join-Path $OutputDirectory 'request.json'
$taskCases = Invoke-RestMethod "$BaseUrl/api/v1/optimization/cases" -TimeoutSec 15
if (-not $CaseIds.Count) { $CaseIds = @($taskCases | Where-Object split -eq $Split | ForEach-Object id) }
$taskRequest = @{
    id = [guid]::NewGuid().ToString(); provider = $Provider; cloudModel = $CloudModel; promptProfile = $PromptProfile
    skillEnabled = [bool]$Skill; caseIds = @($CaseIds); input = ''; repetitions = $Repetitions
    parameters = @{ temperature = $Temperature; maxTokens = $MaxTokens; contextSize = $ContextSize; thinking = [bool]$Thinking; topP = 0.8; topK = 20; seed = 42 }
}
if (Test-Path -LiteralPath $taskRequestFile) {
    $taskSaved = Get-Content -LiteralPath $taskRequestFile -Raw | ConvertFrom-Json -AsHashtable
    foreach ($taskField in @('provider','cloudModel','promptProfile','skillEnabled','caseIds','input','repetitions')) {
        if (($taskSaved[$taskField] | ConvertTo-Json -Depth 10 -Compress) -ne ($taskRequest[$taskField] | ConvertTo-Json -Depth 10 -Compress)) {
            throw 'Папка содержит другой эксперимент. Используйте новую папку или точно те же параметры.'
        }
    }
    foreach ($taskParameter in $taskRequest.parameters.Keys) {
        if ($taskSaved.parameters[$taskParameter] -ne $taskRequest.parameters[$taskParameter]) { throw 'Параметры не совпадают с квитанцией в папке.' }
    }
    $taskRequest = $taskSaved
} else { $taskRequest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $taskRequestFile -Encoding utf8 }
$taskId = $taskRequest.id
try { $taskResult = Invoke-RestMethod "$BaseUrl/api/v1/optimization/experiments/$taskId" -TimeoutSec 15 }
catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 404) { throw }
    # Единственный POST. При неопределённом исходе повторный запуск сначала проверяет GET по тому же UUID.
    $taskResult = Invoke-RestMethod "$BaseUrl/api/v1/optimization/experiments" -Method Post -ContentType 'application/json; charset=utf-8' -Body ($taskRequest | ConvertTo-Json -Depth 20) -TimeoutSec 30
}
$taskUntil = (Get-Date).AddMinutes(20)
while ($taskResult.status -eq 'RUNNING') {
    if ((Get-Date) -gt $taskUntil) { throw "Истекло ожидание. Эксперимент $taskId не отменён; повторный запуск с этой папкой продолжит чтение." }
    Start-Sleep -Milliseconds 600
    try { $taskResult = Invoke-RestMethod "$BaseUrl/api/v1/optimization/experiments/$taskId" -TimeoutSec 15 }
    catch { Write-Warning 'Статус временно недоступен. POST и LLM-вызов не повторяются.'; continue }
    $taskResult | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'result.json') -Encoding utf8
}
$taskResult | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'result.json') -Encoding utf8
$taskGold = @($taskResult.items | Where-Object { $null -ne $_.expected })
$taskMatched = ($taskGold | ForEach-Object { if ($null -eq $_.evaluation) { 0 } else { $_.evaluation.matchedFields } } | Measure-Object -Sum).Sum
[pscustomobject]@{ id=$taskId; model=$taskResult.model; status=$taskResult.status; exact=@($taskGold | Where-Object { $_.status -eq 'COMPLETED' -and $_.evaluation.exactFields }).Count; cases=$taskGold.Count; matchedFields=$taskMatched; fields=$taskGold.Count*5; path=$OutputDirectory } | Format-List
Write-Output "UI: http://localhost:8483/#optimization/$taskId"
Write-Output 'Это точное сравнение пяти полей; резюме и смысл ответа нужно просмотреть отдельно.'
