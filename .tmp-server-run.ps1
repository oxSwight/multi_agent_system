Set-Location X:\MIDAS
Get-Content .env | Where-Object { $_ -match '^\s*[^#]' -and $_ -match '=' } | ForEach-Object {
    $p = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($p[0].Trim(), $p[1].Trim(), 'Process')
}

function ConvertTo-Base64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
}

$key = [Convert]::FromBase64String($env:MIDAS_JWT_SECRET)
$header = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
$iat = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$payload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes("{`"chatId`":4242,`"iat`":$iat,`"exp`":$($iat+86400)}"))
$data = "$header.$payload"
$hmac = [System.Security.Cryptography.HMACSHA256]::new()
$hmac.Key = $key
$token = "$data.$(ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($data))))"
$headers = @{ Authorization = "Bearer $token"; "Content-Type" = "application/json" }

Start-Sleep -Seconds 8

$serverIdea = @"
Spring Boot 3 + PostgreSQL backend ONLY.
CRUD resume profiles, POST /match (Gemini), JWT auth.
NO Chrome extension, NO manifest.json.
MVP: one profile, text fields, single user.
execution_model: SERVER_SIDE.
"@

$body = @{ rawUserIdea = $serverIdea; autoMode = $true } | ConvertTo-Json -Compress
$resp = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/pipelines" -Method POST -Headers $headers -Body $body
$runId = $resp.runId
Write-Host "STARTED runId=$runId state=$($resp.state)"

$terminal = @('COMPLETED','ERROR')
$finalState = $null
for ($i = 0; $i -lt 180; $i++) {
    Start-Sleep -Seconds 30
    $st = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/pipelines/$runId/status" -Headers $headers
    Write-Host "$(Get-Date -Format HH:mm:ss) state=$($st.state)"
    if ($terminal -contains $st.state) {
        $finalState = $st.state
        break
    }
}

$detail = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/dashboard/runs/$runId" -Headers $headers
Write-Host ""
Write-Host "========== FINAL REPORT =========="
Write-Host "runId=$runId"
Write-Host "finalState=$finalState"
Write-Host "dbStatus=$($detail.status)"
Write-Host "costUsd=$($detail.estimatedCostUsd)"
Write-Host "promptTokens=$($detail.promptTokens) completionTokens=$($detail.completionTokens)"
Write-Host "artifactPath=$($detail.artifactPath)"
Write-Host "agents:"
foreach ($a in $detail.agentLogs) {
    Write-Host "  $($a.agentType) $($a.promptTokens)+$($a.completionTokens) finish=$($a.finishReason) err=$($a.isError) ms=$($a.executionTimeMs)"
}

if ($finalState -eq 'COMPLETED') {
    $out = Join-Path $env:TEMP "midas-server-$runId.zip"
    Invoke-WebRequest -Uri "http://localhost:8080/api/v1/pipelines/$runId/artifacts" -Headers $headers -OutFile $out
    $size = (Get-Item $out).Length
    Write-Host "artifactZip=$out sizeBytes=$size"
}

Write-Host "========== CONTROLLER LOGS =========="
docker logs midas_backend 2>&1 | Select-String -Pattern "$runId.*Controller|Invalid evidence" | Select-Object -Last 15
