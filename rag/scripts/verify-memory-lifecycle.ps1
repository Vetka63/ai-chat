# Optional standalone paid regression; never part of the ordinary test suite.
# Run explicitly: pwsh -File ./scripts/verify-memory-lifecycle.ps1 -RunLive
# Four user turns, at most 20 LLM calls. One bounded semantic revision is visible; no HTTP retries, Git operations or chat deletion.
# Long-tail retention/restart are covered separately by verify-day25.ps1 (2 x 12 turns).
param(
    [switch]$RunLive,
    [string]$ApiUrl = 'http://localhost:8382/api/v1',
    [ValidateRange(1,32768)][int]$MaxOutputTokens = 2400
)
$ErrorActionPreference = 'Stop'
if (!$RunLive) { throw 'Опциональный платный тест: укажите -RunLive для 4 сообщений, до 20 LLM-вызовов. Без флага API не вызывается.' }
$ragApi = $ApiUrl.TrimEnd('/')
$ragRunId = '{0}-{1}' -f [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'), [Guid]::NewGuid().ToString('N').Substring(0,8)
$ragData = Join-Path $PSScriptRoot '../data'
New-Item -ItemType Directory -Path $ragData -Force | Out-Null
$ragReportPath = Join-Path $ragData "memory-lifecycle-live-$ragRunId.json"
if (Test-Path -LiteralPath $ragReportPath) { throw 'Уникальный путь отчёта уже занят; существующий файл не заменяется.' }
$ragReport = @{ at = [DateTimeOffset]::UtcNow.ToString('o'); runId = $ragRunId; status = 'RUNNING'; indexId = $null; maxOutputTokens = $MaxOutputTokens; chats = @{}; attempts = @(); results = @(); isolation = @(); checks = @(); note = 'Optional standalone memory lifecycle test: 4 turns, at most 20 paid calls, at most one visible semantic revision, no HTTP retries. Fictional task; no Git operations. Memory preparation/provenance and lifecycle are asserted. Book answer status is recorded separately; this is not a usefulness or long-tail benchmark.' }
$ragDocuments = @{}
function Save-RagReport { [IO.File]::WriteAllText($ragReportPath, ($ragReport | ConvertTo-Json -Depth 90), [Text.UTF8Encoding]::new($false)) }
function Assert-Rag([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Post-Rag([string]$Path, $Body) { Invoke-RestMethod "$ragApi$Path" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20))) -TimeoutSec 900 }
function Find-RagFact($Chat, [string]$Layer, [string]$Pattern) {
    $ragFound = @($Chat.memory.facts | Where-Object { $_.layer -ceq $Layer -and $_.value -match $Pattern })
    Assert-Rag ($ragFound.Count -eq 1) "Ожидался один факт $Layer по смыслу '$Pattern', найдено $($ragFound.Count)."
    return $ragFound[0]
}
function Fact-Signature($Fact) { @($Fact.layer, $Fact.key, $Fact.value, $Fact.sourceTurnId, $Fact.quote) | ConvertTo-Json -Compress }
function Check-Isolation([int]$Position) {
    $ragOther = Invoke-RestMethod "$ragApi/conversations/$($ragReport.chats.B)" -TimeoutSec 30
    $ragReport.isolation += @{ afterPosition = $Position; detail = $ragOther }; Save-RagReport
    Assert-Rag ($ragOther.turns.Count -eq 0 -and $ragOther.memory.facts.Count -eq 0) 'Память или история A попала в пустой чат B.'
}
function Check-Provenance($Chat, $Turn) {
    foreach ($ragFact in $Chat.memory.facts) {
        $ragOrigin = @($Chat.turns | Where-Object { $_.id -ceq $ragFact.sourceTurnId })
        Assert-Rag ($ragOrigin.Count -eq 1 -and ![string]::IsNullOrWhiteSpace($ragFact.quote) -and $ragOrigin[0].question.Contains($ragFact.quote)) 'Факт памяти не имеет точной цитаты пользователя из этого чата.'
    }
    Assert-Rag ($Turn.preparation.query -ceq $Turn.result.retrieval.searchQuery) 'Retrieval использовал не подготовленный вопрос.'
    if ($Turn.result.status -ne 'ANSWERED') {
        Assert-Rag ($Turn.result.claims.Count -eq 0 -and $Turn.result.sources.Count -eq 0) 'Отклонённый ответ содержит публичные факты или источники.'
        return
    }
    Assert-Rag ($Turn.result.claims.Count -gt 0 -and $Turn.result.sources.Count -gt 0) 'ANSWERED без пунктов или источников.'
    $ragSupport = $Turn.result.supportCheck
    Assert-Rag ($ragSupport.status -ceq 'PASSED' -and $ragSupport.claims.Count -eq $Turn.result.claims.Count -and @($ragSupport.claims | Where-Object { $_.verdict -cne 'SUPPORTED' }).Count -eq 0) 'ANSWERED без полной успешной смысловой проверки.'
    $ragIndices = @($ragSupport.claims | ForEach-Object { $_.claimIndex } | Sort-Object)
    Assert-Rag (($ragIndices -join ',') -ceq ((0..($Turn.result.claims.Count - 1)) -join ',')) 'Индексы смысловых вердиктов не покрывают ответ ровно один раз.'
    foreach ($ragClaim in $Turn.result.claims) {
        Assert-Rag ($ragClaim.citations.Count -gt 0) 'Пункт ответа без цитаты.'
        foreach ($ragCitation in $ragClaim.citations) {
            $ragHit = $Turn.result.retrieval.included | Where-Object { $_.chunk.chunkId -ceq $ragCitation.source.chunkId } | Select-Object -First 1
            Assert-Rag ($null -ne $ragHit -and ![string]::IsNullOrWhiteSpace($ragCitation.quote) -and $ragHit.chunk.text.Contains($ragCitation.quote)) 'Цитата не из текущего книжного контекста.'
            Assert-Rag ($ragHit.chunk.documentId -ceq $ragCitation.source.documentId -and $ragHit.chunk.source -ceq $ragCitation.source.source -and $ragHit.chunk.section -ceq $ragCitation.source.section) 'Метаданные цитаты не соответствуют чанку.'
            $ragDocumentId = $ragCitation.source.documentId
            if (!$ragDocuments.ContainsKey($ragDocumentId)) { $ragDocuments[$ragDocumentId] = Invoke-RestMethod "$ragApi/indexes/$($ragReport.indexId)/documents/$ragDocumentId" -TimeoutSec 30 }
            $ragDocument = $ragDocuments[$ragDocumentId]
            Assert-Rag ($ragCitation.canonicalStart -ge 0 -and $ragCitation.canonicalEndExclusive -gt $ragCitation.canonicalStart -and $ragCitation.canonicalEndExclusive -le $ragDocument.text.Length) 'Некорректные координаты цитаты.'
            Assert-Rag ($ragDocument.text.Substring($ragCitation.canonicalStart, $ragCitation.canonicalEndExclusive - $ragCitation.canonicalStart) -ceq $ragCitation.quote) 'Цитата не совпадает со snapshot.'
        }
    }
}
Save-RagReport
"Новый отчёт: $ragReportPath. Четыре сообщения, до 20 платных LLM-вызовов; чаты сохраняются."
try {
    $ragSettings = Invoke-RestMethod "$ragApi/answer-settings" -TimeoutSec 30
    Assert-Rag $ragSettings.configured 'Нужен настроенный серверный ключ.'
    $ragIndexes = Invoke-RestMethod "$ragApi/indexes" -TimeoutSec 30
    $ragIndex = $ragIndexes | Where-Object { $_.config.strategy -ceq 'STRUCTURAL' -and $_.config.maxCharacters -eq 3000 -and $_.config.overlapCharacters -eq 300 } | Select-Object -First 1
    Assert-Rag ($null -ne $ragIndex) 'Нужен готовый STRUCTURAL индекс 3000/300.'
    $ragReport.indexId = $ragIndex.id; Save-RagReport
    foreach ($ragName in @('A','B')) {
        $ragCreated = Post-Rag '/conversations' @{ title = "Memory lifecycle $ragName $ragRunId"; settings = @{ indexId = $ragIndex.id; historyTurns = 6; historyMaxCharacters = 10000; maxOutputTokens = $MaxOutputTokens } }
        $ragReport.chats[$ragName] = $ragCreated.conversation.id; Save-RagReport
        if ($ragName -ceq 'A') { $ragChat = $ragCreated }
    }
    Check-Isolation 0
    $ragQuestions = @(
        'Моя цель — подготовить выпуск приложения в отдельной ветке. Назовём рабочую ветку release-payment. В общую ветку force push запрещён. Как создать рабочую ветку и переключиться на неё?',
        'Переименуем рабочую ветку в release-payments-v2. Общая цель и запрет force push прежние. Как переименовать локальную ветку?',
        'Снимаю прежний запрет на force push в общую ветку. Это изменение условий учебного сценария, команды выполнять не нужно. Как посмотреть список локальных веток?',
        'Новая цель: найти коммит, который внёс ошибку. Как работает git bisect?'
    )
    for ($ragN = 0; $ragN -lt $ragQuestions.Count; $ragN++) {
        $ragRequest = @{ requestId = [Guid]::NewGuid().ToString(); question = $ragQuestions[$ragN]; expectedRevision = $ragChat.conversation.revision }
        $ragReport.attempts += @{ position = $ragN + 1; request = $ragRequest }; Save-RagReport
        $ragChat = Post-Rag "/conversations/$($ragReport.chats.A)/turns" $ragRequest
        $ragTurn = $ragChat.turns[-1]
        $ragReport.results += @{ position = $ragN + 1; turn = $ragTurn; memory = $ragChat.memory }; Save-RagReport
        Assert-Rag ($ragChat.turns.Count -eq $ragN + 1 -and $ragTurn.requestId -ceq $ragRequest.requestId -and $ragTurn.question -ceq $ragRequest.question) 'Сохранённая история не совпадает с отправленным шагом.'
        Assert-Rag ($ragTurn.status -ceq 'COMPLETED' -and $null -ne $ragTurn.preparation -and $ragTurn.preparation.issues.Count -eq 0) 'Подготовка памяти не завершена или не прошла проверку.'
        Assert-Rag (!$ragTurn.issue -and $null -ne $ragTurn.result.retrieval -and $ragTurn.result.status -cin @('ANSWERED','UNKNOWN','INVALID_EVIDENCE')) 'Техническая ошибка обработки; это не успешная проверка памяти.'
        Assert-Rag ($ragTurn.llmStagesAttempted -ge 1 -and $ragTurn.llmStagesAttempted -le 5) 'Неожиданное число LLM-стадий.'
        Check-Provenance $ragChat $ragTurn
        $ragGoals = @($ragChat.memory.facts | Where-Object { $_.layer -ceq 'GOAL' })
        Assert-Rag ($ragGoals.Count -eq 1) 'В памяти должна быть ровно одна общая цель.'
        switch ($ragN) {
            0 {
                $ragInitialGoal = Find-RagFact $ragChat 'GOAL' 'выпуск|релиз'
                $ragOriginalBranch = Find-RagFact $ragChat 'TERMS' '(?<![\w-])release-payment(?![\w-])'
                $ragOriginalBan = Find-RagFact $ragChat 'CONSTRAINTS' '(?i)force\s*push'
                Assert-Rag ($ragInitialGoal.sourceTurnId -ceq $ragTurn.id -and $ragOriginalBranch.sourceTurnId -ceq $ragTurn.id -and $ragOriginalBan.sourceTurnId -ceq $ragTurn.id) 'Начальные факты не ссылаются на первый user turn.'
                Assert-Rag ($ragOriginalBan.value -match 'запре|нельзя|не\s+(использ|делать|выполнять|допуск)') 'Не извлечён запрет force push.'
            }
            1 {
                $ragRenamedBranch = Find-RagFact $ragChat 'TERMS' '(?<![\w-])release-payments-v2(?![\w-])'
                Assert-Rag ($ragRenamedBranch.key -ceq $ragOriginalBranch.key -and $ragRenamedBranch.sourceTurnId -ceq $ragTurn.id) 'Переименование не обновило исходную запись с новым user provenance.'
                Assert-Rag (@($ragChat.memory.facts | Where-Object { $_.layer -ceq 'TERMS' -and $_.value -match '(?<![\w-])release-payment(?![\w-])' }).Count -eq 0) 'После переименования осталось прежнее имя.'
                Assert-Rag ((Fact-Signature $ragGoals[0]) -ceq (Fact-Signature $ragInitialGoal)) 'Переименование изменило общую цель или её происхождение.'
                Assert-Rag ((Fact-Signature (Find-RagFact $ragChat 'CONSTRAINTS' '(?i)force\s*push')) -ceq (Fact-Signature $ragOriginalBan)) 'Переименование изменило действующее ограничение.'
            }
            2 {
                Assert-Rag (@($ragChat.memory.facts | Where-Object { $_.layer -ceq 'CONSTRAINTS' -and ($_.key -ceq $ragOriginalBan.key -or $_.value -match '(?i)force\s*push') }).Count -eq 0) 'Явно отменённый запрет остался в памяти.'
                $ragRemoval = @($ragTurn.preparation.changes | Where-Object { $_.layer -ceq 'CONSTRAINTS' -and $_.key -ceq $ragOriginalBan.key -and $null -eq $_.value })
                Assert-Rag ($ragRemoval.Count -eq 1 -and $ragTurn.question.Contains($ragRemoval[0].quote)) 'Нет явного удаления с цитатой текущего пользователя.'
                Assert-Rag ((Fact-Signature $ragGoals[0]) -ceq (Fact-Signature $ragInitialGoal)) 'Отмена ограничения изменила общую цель.'
                Assert-Rag ((Fact-Signature (Find-RagFact $ragChat 'TERMS' 'release-payments-v2')) -ceq (Fact-Signature $ragRenamedBranch)) 'Отмена ограничения изменила имя ветки.'
            }
            3 {
                Assert-Rag ($ragGoals[0].value -match 'коммит|ошиб|регресс' -and $ragGoals[0].value -cne $ragInitialGoal.value -and $ragGoals[0].sourceTurnId -ceq $ragTurn.id) 'Явная новая цель не заменила прежнюю с новым provenance.'
                Assert-Rag ((Fact-Signature (Find-RagFact $ragChat 'TERMS' 'release-payments-v2')) -ceq (Fact-Signature $ragRenamedBranch)) 'Смена цели неожиданно стёрла или изменила термин.'
                Assert-Rag (@($ragChat.memory.facts | Where-Object { $_.layer -ceq 'CONSTRAINTS' -and ($_.key -ceq $ragOriginalBan.key -or $_.value -match '(?i)force\s*push') }).Count -eq 0) 'При смене цели вернулось отменённое ограничение.'
            }
        }
        Check-Isolation ($ragN + 1)
        $ragReport.checks += "Шаг $($ragN + 1): preparation/lifecycle/provenance/isolation OK; book status=$($ragTurn.result.status)"; Save-RagReport
        $ragReport.checks[-1]
    }
    $ragReport.llmStagesAttempted = ($ragReport.results | ForEach-Object { $_.turn.llmStagesAttempted } | Measure-Object -Sum).Sum
    $ragReport.status = 'PASSED'; Save-RagReport
    "Память: создание, переименование, отмена, новая цель и изоляция проверены. Book statuses оцениваются отдельно. Trace: $ragReportPath"
} catch {
    $ragReport.status = 'FAILED'; $ragReport.error = $_.Exception.Message; Save-RagReport
    throw "Memory lifecycle не прошёл: $($_.Exception.Message). Trace сохранён: $ragReportPath. Автоматического повтора нет."
}
