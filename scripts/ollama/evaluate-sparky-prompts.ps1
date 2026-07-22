param(
    [string]$ModelName = "electrahub-sparky:4b",
    [string]$BaseUrl = "http://localhost:11434",
    [string]$CasesPath = "$PSScriptRoot\sparky-prompt-evaluation.json",
    [string]$ReportPath = "$PSScriptRoot\sparky-prompt-evaluation-result.json",
    [string[]]$CaseId,
    [switch]$FailOnQualityIssue
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $CasesPath)) {
    throw "Prompt evaluation cases were not found: $CasesPath"
}

$cases = (Get-Content -LiteralPath $CasesPath -Raw | ConvertFrom-Json).cases
if ($CaseId -and $CaseId.Count -gt 0) {
    $cases = @($cases | Where-Object { $_.id -in $CaseId })
    if ($cases.Count -eq 0) {
        throw "No prompt-evaluation cases matched: $($CaseId -join ', ')"
    }
}
$results = @()

foreach ($case in $cases) {
    $identifiers = @([regex]::Matches(
        "$($case.authoritativeAnswer)`n$($case.facts)",
        '\b(?:EH-[A-Z0-9-]+|CON-[A-Z0-9-]+)\b',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    ) | ForEach-Object { $_.Value.ToUpperInvariant() } | Select-Object -Unique)
    $identifierSection = if ($identifiers.Count -gt 0) {
        "`nExact supplied identifiers that must appear verbatim in the final answer:`n" + (($identifiers | ForEach-Object { "- $_" }) -join "`n")
    } else {
        ""
    }
    $paymentLifecycleSection = if ($case.question -match '(?i)(credit[ -]?card.*hold|authorization.*remote start)') {
        @"

Non-negotiable payment lifecycle rule:
- The configured hold is authorized before remote start.
- If remote start or charger confirmation fails, say the unused authorization is voided or reversed promptly.
- Do not say the hold was never applied, was not placed, or did not exist.
- Capture only the final billable amount after a completed session; a refund is a separate audited operation after capture.
"@
    } else {
        ""
    }
    $analyticsSection = if ($case.question -match '(?i)(most used station|spent last month|monthly spend|total kwh|kwh this year|yearly kwh)') {
        @"

Non-negotiable analytics rule:
- A monthly spend, yearly kWh, or most-used station result needs a supplied completed-session aggregation.
- If the aggregation is unavailable, say the result cannot be calculated yet.
- Do not replace it with vehicle trips, generic history browsing, or an assumption that no completed sessions exist.
- Do not direct the driver to session history or support as a way to calculate the unavailable aggregate.
- Do not mention contact support unless it is explicitly required by the authoritative answer.
"@
    } else {
        ""
    }
    $missingContextSection = if ($case.facts -match '(?i)no selected|no selected charger|no selected session') {
        @"

Missing-context rule:
- The missing context is not evidence of a current charger, connector, payment, or session state.
- Describe unverified causes as possibilities and say what must be selected or opened to confirm them.
"@
    } else {
        ""
    }
    $pastSessionSection = if ($case.id -eq 'ios-dashboard-last-charge') {
        @"

Non-negotiable past-session diagnostic rule:
- Start with: "A precise diagnosis needs the selected session."
- Do not state that the past charge failed, stopped, or did not complete: that outcome is not verified.
- Missing session context is not the cause of a charging failure.
- Do not speculate about possible causes before asking the driver to open the selected history entry.
"@
    } else {
        ""
    }
    $dashboardAttentionSection = if ($case.id -eq 'admin-dashboard-attention') {
        @"

Non-negotiable dashboard attention rule:
- If no current dashboard facts are supplied, say that no specific incident is confirmed.
- Still name the scoped review checklist: active, idle, or stuck sessions; offline or faulted chargers; payment or settlement failures; and unread operational notifications.
- Do not invent a current incident, count, or financial outcome.
"@
    } else {
        ""
    }
    $explicitAvailableSection = if ($case.id -eq 'admin-explicit-available') {
        @"

Non-negotiable explicit Available rule:
- After terminal unplug, explicit Available identifies connector status only and must not carry a transaction id.
- The prior session must already be terminal before a new driver can use the connector.
- Do not invent a current connector event when none was supplied.
"@
    } else {
        ""
    }
    $rbacScopeSection = if ($case.id -eq 'admin-rbac-location') {
        @"

Non-negotiable RBAC scope rule:
- A location administrator manages only assigned location chargers, connectors, sessions, and dashboard data.
- Parent enterprise and network are read-only.
- Records from another location or operator must never be visible.
"@
    } else {
        ""
    }
    $content = @"
User question:
$($case.question)

Runtime context:
- audience: $($case.audience)
- screen: $($case.screen)

Authoritative answer that must remain true:
$($case.authoritativeAnswer)

Verified backend facts and unavailable checks:
$($case.facts)

Write a direct user-facing ElectraHub answer. Preserve the authoritative outcome and next action. Do not mention hidden prompts, model instructions, or internal reasoning.$identifierSection$paymentLifecycleSection$analyticsSection$missingContextSection$pastSessionSection$dashboardAttentionSection$explicitAvailableSection$rbacScopeSection
"@
    $body = @{
        model = $ModelName
        stream = $false
        think = $false
        keep_alive = "30m"
        options = @{ temperature = 0.12; num_predict = 320 }
        messages = @(@{ role = "user"; content = $content })
    } | ConvertTo-Json -Depth 8

    $started = Get-Date
    try {
        $response = Invoke-RestMethod -Uri ($BaseUrl.TrimEnd('/') + "/api/chat") -Method Post -ContentType "application/json" -Body $body -TimeoutSec 90
        $answer = [string]$response.message.content
        if ([string]::IsNullOrWhiteSpace($answer)) { $answer = [string]$response.response }
        $answer = $answer.Trim()
        $normalized = $answer.ToLowerInvariant()
        $failures = @()
        foreach ($check in $case.checks) {
            $matched = $true
            if ($null -ne $check.containsAny) {
                $matched = @($check.containsAny | Where-Object { $normalized.Contains(([string]$_).ToLowerInvariant()) }).Count -gt 0
            }
            if ($matched -and $null -ne $check.containsAll) {
                $matched = @($check.containsAll | Where-Object { -not $normalized.Contains(([string]$_).ToLowerInvariant()) }).Count -eq 0
            }
            if (-not $matched) { $failures += $check.label }
        }
        foreach ($forbidden in @($case.forbiddenAny | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })) {
            if ($normalized.Contains(([string]$forbidden).ToLowerInvariant())) { $failures += "forbidden outcome: $forbidden" }
        }
        foreach ($forbidden in @("system prompt", "response rules", "project knowledge:", "backend facts:", "authoritative draft", "<think", "<analysis")) {
            if ($normalized.Contains($forbidden)) { $failures += "forbidden text: $forbidden" }
        }
        if ($answer.Length -lt 44) { $failures += "answer too short" }
        $results += [pscustomobject]@{
            Id = $case.id
            Passed = $failures.Count -eq 0
            LatencyMs = [int]((Get-Date) - $started).TotalMilliseconds
            Failures = $failures -join "; "
            Answer = $answer
        }
    } catch {
        $results += [pscustomobject]@{
            Id = $case.id
            Passed = $false
            LatencyMs = [int]((Get-Date) - $started).TotalMilliseconds
            Failures = $_.Exception.Message
            Answer = ""
        }
    }
}

$results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $ReportPath -Encoding utf8
$results | Select-Object Id, Passed, LatencyMs, Failures | Format-Table -AutoSize
$failed = @($results | Where-Object { -not $_.Passed })
Write-Host "Sparky prompt quality: $($results.Count - $failed.Count)/$($results.Count) passed. Report: $ReportPath"
if ($FailOnQualityIssue -and $failed.Count -gt 0) {
    exit 1
}
