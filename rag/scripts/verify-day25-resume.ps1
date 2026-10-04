# Bounded recovery of the recorded synthetic regression, not an automatic LLM retry.
# Keeps the failed turn and original report. Refuses a second run before any paid call.
param([Parameter(Mandatory)][string]$ReportPath, [string]$ApiUrl = 'http://localhost:8382/api/v1')
$ErrorActionPreference = 'Stop'
$ragApi = $ApiUrl.TrimEnd('/')
$ragBasePath = [IO.Path]::GetFullPath($ReportPath)
$ragBase = Get-Content -LiteralPath $ragBasePath -Raw | ConvertFrom-Json
$ragCases = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../evaluation/day25-scenarios.json') -Raw | ConvertFrom-Json
$ragChats = @{}
function Assert-Rag([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
# The only supported starting point is the known failure after R12, with C1..6 completed.
# Check every existing question against public fixtures so this cannot replay an unrelated user's chat.
foreach ($ragCase in $ragCases) {
    $ragId = $ragBase.chats.($ragCase.id)
    Assert-Rag (![string]::IsNullOrWhiteSpace($ragId)) 'Missing synthetic chat ID.'
    $ragChat = Invoke-RestMethod "$ragApi/conversations/$ragId" -TimeoutSec 30
    $ragExpected = if ($ragCase.id -eq 'recovery') { 12 } else { 6 }
    Assert-Rag ($ragChat.turns.Count -eq $ragExpected) 'Unexpected history length; no paid request was sent.'
    Assert-Rag ($ragChat.conversation.settings.indexId -ceq $ragBase.indexId) 'Unexpected index.'
    for ($ragN = 0; $ragN -lt $ragExpected; $ragN++) {
        Assert-Rag ($ragChat.turns[$ragN].question -ceq $ragCase.questions[$ragN]) 'History is not the public synthetic scenario.'
    }
    if ($ragCase.id -eq 'recovery') {
        Assert-Rag ($ragChat.turns[-1].preparation.issues -contains 'invalid_dialogue_preparation') 'The expected failed preparation is absent; do not rerun a successful question.'
    }
    $ragChats[$ragCase.id] = $ragChat
}
$ragPath = Join-Path $PSScriptRoot "../data/day25-resume-live-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff')).json"
Assert-Rag (!(Test-Path -LiteralPath $ragPath)) 'Report already exists.'
$ragReport = @{ at = [DateTimeOffset]::UtcNow.ToString('o'); baseReport = [IO.Path]::GetFileName($ragBasePath); status = 'RUNNING'; maxCalls = 35; results = @(); checks = @(); scenarios = @(); note = 'Explicit post-fix recovery of public synthetic chats only: keep failed R12, append intentional R13 repeat, then finish C7..12. Seven new turns maximum; no HTTP retry or hidden replacement of failure. Read baseReport for the first 18 turns.' }
function Save-RagReport { [IO.File]::WriteAllText($ragPath, ($ragReport | ConvertTo-Json -Depth 90), [Text.UTF8Encoding]::new($false)) }
$ragDocuments = @{}
Save-RagReport
"Seven new synthetic turns, at most 35 calls. Original failure retained. Trace: $ragPath"
try {
    foreach ($ragCase in $ragCases) {
        $ragChat = $ragChats[$ragCase.id]
        $ragPositions = if ($ragCase.id -eq 'recovery') { @(12) } else { @(7..12) }
        foreach ($ragPosition in $ragPositions) {
            $ragRequest = @{ requestId = [Guid]::NewGuid().ToString(); question = $ragCase.questions[$ragPosition - 1]; expectedRevision = $ragChat.conversation.revision }
            $ragChat = Invoke-RestMethod "$ragApi/conversations/$($ragChat.conversation.id)/turns" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes(($ragRequest | ConvertTo-Json))) -TimeoutSec 900
            $ragTurn = $ragChat.turns[-1]
            $ragReport.results += @{ scenario = $ragCase.id; logicalPosition = $ragPosition; physicalPosition = $ragChat.turns.Count; intentionalRepeat = ($ragCase.id -eq 'recovery'); turn = $ragTurn }; Save-RagReport
            Assert-Rag ($ragTurn.requestId -ceq $ragRequest.requestId -and $ragTurn.question -ceq $ragRequest.question) 'Wrong saved request.'
            Assert-Rag ($ragTurn.status -ceq 'COMPLETED' -and !$ragTurn.issue -and $ragTurn.preparation.issues.Count -eq 0 -and $null -ne $ragTurn.result.retrieval) 'Preparation/retrieval failed; trace retained.'
            Assert-Rag ($ragTurn.result.status -cin @('ANSWERED','UNKNOWN','INVALID_EVIDENCE')) 'Technical error is not a valid refusal.'
            Assert-Rag ($ragTurn.result.request.question -ceq $ragRequest.question -and $ragTurn.result.retrieval.searchQuery -ceq $ragTurn.preparation.query) 'Question/query mismatch.'
            foreach ($ragFact in $ragChat.memory.facts) {
                $ragOrigin = @($ragChat.turns | Where-Object { $_.id -ceq $ragFact.sourceTurnId })
                Assert-Rag ($ragOrigin.Count -eq 1 -and ![string]::IsNullOrWhiteSpace($ragFact.quote) -and $ragOrigin[0].question.Contains($ragFact.quote)) 'Memory has no exact user provenance in its own chat.'
            }
            foreach ($ragExpectation in $ragCase.memoryExpectations | Where-Object { $_.fromPosition -le $ragPosition -and (!$_.throughPosition -or $_.throughPosition -ge $ragPosition) }) {
                $ragFacts = @($ragChat.memory.facts | Where-Object { $_.layer -eq $ragExpectation.layer -and $_.value -match $ragExpectation.pattern })
                Assert-Rag ($ragFacts.Count -gt 0) "Missing required memory: $($ragExpectation.id)."
                if ($ragExpectation.sourcePosition) { Assert-Rag (@($ragFacts | Where-Object { $_.sourceTurnId -ceq $ragChat.turns[[int]$ragExpectation.sourcePosition - 1].id }).Count -gt 0) 'Wrong original memory turn.' }
            }
            if ($ragTurn.result.status -ceq 'ANSWERED') {
                Assert-Rag ($ragTurn.result.claims.Count -gt 0 -and $ragTurn.result.sources.Count -gt 0) 'Published answer lacks evidence.'
                Assert-Rag ($ragTurn.result.supportCheck.status -ceq 'PASSED' -and $ragTurn.result.supportCheck.claims.Count -eq $ragTurn.result.claims.Count -and @($ragTurn.result.supportCheck.claims | Where-Object { $_.verdict -cne 'SUPPORTED' }).Count -eq 0) 'Incomplete support verdict.'
                $ragExpectedStages = if ($ragTurn.result.repair) { 5 } else { 3 }
                Assert-Rag ($ragTurn.llmStagesAttempted -eq $ragExpectedStages) 'Stage accounting mismatch.'
                foreach ($ragClaim in $ragTurn.result.claims) { foreach ($ragCitation in $ragClaim.citations) {
                    $ragHit = $ragTurn.result.retrieval.included | Where-Object { $_.chunk.chunkId -ceq $ragCitation.source.chunkId } | Select-Object -First 1
                    Assert-Rag ($null -ne $ragHit -and $ragHit.chunk.text.Contains($ragCitation.quote) -and $ragHit.chunk.source -ceq $ragCitation.source.source -and $ragHit.chunk.section -ceq $ragCitation.source.section) 'Citation is not from this retrieval.'
                    $ragDocId = $ragCitation.source.documentId
                    if (!$ragDocuments.ContainsKey($ragDocId)) { $ragDocuments[$ragDocId] = Invoke-RestMethod "$ragApi/indexes/$($ragBase.indexId)/documents/$ragDocId" -TimeoutSec 30 }
                    Assert-Rag ($ragDocuments[$ragDocId].text.Substring($ragCitation.canonicalStart, $ragCitation.canonicalEndExclusive - $ragCitation.canonicalStart) -ceq $ragCitation.quote) 'Snapshot coordinates mismatch.'
                } }
            } else { Assert-Rag ($ragTurn.result.claims.Count -eq 0 -and $ragTurn.result.sources.Count -eq 0) 'Rejected answer has public claims.' }
            if ($ragTurn.totalUsage) { Assert-Rag ($ragTurn.totalUsage.totalTokens -eq $ragTurn.preparation.usage.totalTokens + $ragTurn.result.totalUsage.totalTokens) 'Token accounting mismatch.' }
            $ragReport.checks += "$($ragCase.id) logical=$ragPosition physical=$($ragChat.turns.Count): provenance, evidence, memory, accounting OK; status=$($ragTurn.result.status)"; Save-RagReport
            $ragReport.checks[-1]
        }
        $ragGoal = @($ragChat.memory.facts | Where-Object { $_.layer -ceq 'GOAL' })
        Assert-Rag ($ragGoal.Count -eq 1 -and $ragGoal[0].value -match $ragCase.goalContains) 'Goal lost.'
        Assert-Rag ($ragChat.turns[-1].omittedHistoryTurnCount -ge 5 -and $ragChat.turns[-1].includedHistoryTurnIds -cnotcontains $ragGoal[0].sourceTurnId) 'Goal was not verified beyond the tail.'
        $ragAnswered = @($ragChat.turns | Where-Object { $_.result.status -ceq 'ANSWERED' }).Count
        Assert-Rag ($ragAnswered / $ragChat.turns.Count -ge 0.65) 'Too few published answers, including the preserved failed turn.'
        $ragReport.scenarios += @{ id = $ragCase.id; turns = $ragChat.turns.Count; answered = $ragAnswered; finalMemory = $ragChat.memory; finalTurnId = $ragChat.turns[-1].id }; Save-RagReport
    }
    $ragReport.status = 'PASSED'; Save-RagReport
    "Completed recovery (13 messages including preserved failure) and collaboration (12). Trace: $ragPath"
} catch { $ragReport.status = 'FAILED'; $ragReport.error = $_.Exception.Message; Save-RagReport; throw }
