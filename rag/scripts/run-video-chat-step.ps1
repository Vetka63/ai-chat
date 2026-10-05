#requires -Version 7.0
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Fa-f0-9-]{36}$')][string]$ChatId,
    [Parameter(Mandatory)][ValidateNotNullOrEmpty()][string]$Question,
    [ValidatePattern('^[A-Za-z0-9_-]{1,80}$')][string]$RequestId = [Guid]::NewGuid().ToString(),
    [string]$ApiUrl = 'http://localhost:8382/api/v1',
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')][string]$BackendContainer = 'ai-chat-rag-day-21-backend-1'
)
<#
Одно настоящее сообщение в существующем чате, не пакет и не полная приёмка дня 25.
Вызывать только с разрешённым бюджетом. Пример из папки rag:
  ./scripts/run-video-chat-step.ps1 -ChatId '<UUID чата>' -Question 'Что сохраняет git stash?'
Сохраняется уникальный отчёт в data; история не редактируется и не удаляется.
POST выполняется не более одного раза, даже при timeout. После ошибки отправки —
один GET для восстановления по requestId. Если исход неизвестен, следующий шаг —
только чтение чата, не повтор вопроса с новым requestId и не restart backend.
Переданный повторно RequestId с уже сохранённым сообщением используется только для GET.
results включает все имеющиеся turns для проверки provenance через verify-saved-grounding;
лишь requestId из поля request может быть новой отправкой этого запуска.
Запас 1.5 CNY — preflight, не гарантия завершения всех стадий. Жёсткий общий предел
обеспечивает budget-guard. Параллельные проверки входят в общие budgetBefore/After:
их разность нельзя считать стоимостью исключительно этого сообщения.
#>
$ErrorActionPreference = 'Stop'
$questionText = $Question.Trim()
if (!$questionText -or $questionText.Length -gt 2000) { throw 'Нужно от 1 до 2000 символов вопроса.' }
$parsedChatId = [Guid]::Empty
if (![Guid]::TryParse($ChatId, [ref]$parsedChatId)) { throw 'ChatId должен быть UUID существующего чата.' }
$apiUri = [Uri]$ApiUrl
if (!$apiUri.IsAbsoluteUri -or !$apiUri.IsLoopback -or $apiUri.Scheme -notin @('http', 'https') -or $apiUri.UserInfo -or $apiUri.Query -or $apiUri.Fragment) {
    throw 'Разрешён только локальный HTTP(S) API без credentials/query/fragment.'
}
$api = $ApiUrl.TrimEnd('/')
$chatUrl = "$api/conversations/$ChatId"
$detailBefore = Invoke-RestMethod $chatUrl -TimeoutSec 20
if ($detailBefore.conversation.id -cne $ChatId) { throw 'API вернул другой чат.' }
$existing = @($detailBefore.turns | Where-Object requestId -CEQ $RequestId)
if ($existing.Count -gt 1 -or ($existing.Count -eq 1 -and $existing[0].question -cne $questionText)) {
    throw 'RequestId уже относится к другому вопросу или неоднозначной записи. POST запрещён.'
}

# Печатаем/читаем только одну несекретную переменную, никогда весь Docker environment.
$baseUrlLines = @(& docker exec $BackendContainer printenv DEEPSEEK_BASE_URL 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'Не удалось проверить DEEPSEEK_BASE_URL работающего backend через Docker. POST запрещён.' }
$backendBaseUrl = ($baseUrlLines -join "`n").Trim().TrimEnd('/')
if ($backendBaseUrl -cne 'http://budget-guard:8787') {
    throw 'Backend не подключён к budget-guard. POST запрещён; сначала включите compose.budget.yml.'
}
$budgetBefore = Invoke-RestMethod 'http://localhost:8787/budget' -TimeoutSec 10
foreach ($field in @('limitCny', 'stageCapCny', 'committedUpperCny')) {
    $property = $budgetBefore.PSObject.Properties[$field]
    $number = 0.0
    if (!$property -or $null -eq $property.Value -or ![double]::TryParse([string]$property.Value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$number) -or [double]::IsNaN($number) -or [double]::IsInfinity($number) -or $number -lt 0) {
        throw "Некорректный денежный счётчик $field. POST запрещён."
    }
}
$availableBefore = [Math]::Min([double]$budgetBefore.stageCapCny, [double]$budgetBefore.limitCny - 1) - [double]$budgetBefore.committedUpperCny

if (!$existing.Count) {
    if (@($detailBefore.turns | Where-Object status -EQ 'PENDING').Count) { throw 'В чате уже есть PENDING. Новое сообщение не отправлено; дождитесь завершения.' }
    if ($availableBefore -lt 1.5) { throw 'Доступный резерв меньше 1.5 CNY. Новое сообщение не отправлено.' }
    $outputCap = $detailBefore.conversation.settings.maxOutputTokens
    if ($null -eq $outputCap -or [int]$outputCap -lt 1 -or [int]$outputCap -gt 16384) {
        throw 'Для budget-guard чат должен иметь явный maxOutputTokens от 1 до 16384. Настройки существующего чата не менялись.'
    }
}

$directory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../data'))
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$reportPath = Join-Path $directory "video-day25-step-$([DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfff'))-$([Guid]::NewGuid().ToString('N')).json"
$request = @{ requestId = $RequestId; question = $questionText; expectedRevision = $detailBefore.conversation.revision }
$report = [ordered]@{
    at = [DateTimeOffset]::UtcNow.ToString('o')
    note = 'Один шаг демонстрации, НЕ полная приёмка. results содержит весь сохранённый чат; prior turns не сгенерированы заново. Общий budget delta включает параллельные работы.'
    chats = @{ video = $ChatId }
    indexId = $detailBefore.conversation.settings.indexId
    request = $request
    backendContainer = $BackendContainer
    backendBaseUrl = $backendBaseUrl
    budgetBefore = $budgetBefore
    availableReserveBeforeCny = $availableBefore
    budgetAfter = $null
    budgetAfterIssue = $null
    postAttempts = 0
    outcome = 'PREPARED'
    deliveryIssue = $null
    recoveryIssue = $null
    detailBefore = $detailBefore
    detail = $detailBefore
    results = @()
}
function Save-VideoChatReport {
    $report.results = @()
    $position = 0
    foreach ($turn in $report.detail.turns) {
        $position++
        $report.results += @{ scenario = 'video'; position = $position; conversationId = $ChatId; turn = $turn }
    }
    [IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 100), [Text.UTF8Encoding]::new($false))
}
# CreateNew гарантирует, что даже теоретическая коллизия имени не затрёт прошлый trace.
$newReport = [IO.File]::Open($reportPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
$newReport.Dispose()
Save-VideoChatReport
"RequestId: $RequestId; trace: $reportPath"
try {
    if ($existing.Count) {
        $report.outcome = 'ALREADY_SAVED_GET_ONLY'
    } else {
        # Единственная точка POST. Сначала durable local receipt, затем сетевой вызов.
        $report.postAttempts = 1
        $report.outcome = 'POST_STARTED'
        Save-VideoChatReport
        try {
            $report.detail = Invoke-RestMethod "$chatUrl/turns" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($request | ConvertTo-Json))) -TimeoutSec 900
            $report.outcome = 'POST_RETURNED'
        } catch {
            $report.deliveryIssue = $_.Exception.Message
            $report.outcome = 'DELIVERY_UNKNOWN'
            Save-VideoChatReport
            try {
                # Ровно один recovery GET, никакого повторного POST при timeout/ошибке.
                $report.detail = Invoke-RestMethod $chatUrl -TimeoutSec 20
            } catch {
                $report.recoveryIssue = $_.Exception.Message
            }
        }
    }
    if ($report.detail.conversation.id -cne $ChatId) { throw 'Ответ API относится не к запрошенному чату. Сохранённый trace требует ручной проверки.' }
    $receipt = @($report.detail.turns | Where-Object requestId -CEQ $RequestId)
    if ($receipt.Count -ne 1) {
        $report.outcome = 'DELIVERY_UNKNOWN'
        throw 'RequestId пока не найден в сохранённой истории. Нельзя считать запрос невыполненным; продолжайте только GET-проверкой.'
    }
    if ($receipt[0].question -cne $questionText) { throw 'Сохранённый requestId относится к другому тексту. Продолжение остановлено.' }
    if ($receipt[0].status -eq 'PENDING') {
        $report.outcome = 'SAVED_PENDING'
        Write-Warning 'Вопрос сохранён и ещё выполняется. Не повторяйте POST и не перезапускайте backend; позже проверьте историю GET.'
    } elseif ($receipt[0].status -eq 'COMPLETED') {
        $report.outcome = if ($report.deliveryIssue) { 'RECOVERED_COMPLETED' } elseif ($existing.Count) { 'ALREADY_COMPLETED_GET_ONLY' } else { 'COMPLETED' }
        "Сохранённый результат: $($receipt[0].result.status); issue: $($receipt[0].issue); LLM-стадий: $($receipt[0].llmStagesAttempted)."
        if ($receipt[0].issue -or $receipt[0].result.status -ne 'ANSWERED') { Write-Warning 'Это не подтверждённый полезный ответ; проверьте статус и диагностику. Автоматического повтора не будет.' }
    } else {
        $report.outcome = 'SAVED_INTERRUPTED'
        Write-Warning 'Сохранённый turn прерван. Это не успешный ответ; повтор не запускался.'
    }
} finally {
    # Даже если счётчик временно недоступен, не теряем уже оплаченный ответ/receipt.
    try { $report.budgetAfter = Invoke-RestMethod 'http://localhost:8787/budget' -TimeoutSec 10 }
    catch { $report.budgetAfterIssue = $_.Exception.Message }
    Save-VideoChatReport
    "Итог: $($report.outcome); отчёт: $reportPath"
}
