param(
    [string]$BaseUrl = "http://127.0.0.1:8766",
    [string]$TokenFile = "../mods/fabric/run/config/mc-command-mcp.token"
)

$ErrorActionPreference = "Stop"
$tokenPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $TokenFile))
$token = (Get-Content -LiteralPath $tokenPath -Raw).Trim()
$headers = @{ Authorization = "Bearer $token" }

$status = Invoke-RestMethod "$BaseUrl/v1/status"
$validate = Invoke-RestMethod "$BaseUrl/v1/command/validate" -Method Post -Headers $headers `
    -ContentType "application/json" -Body (@{ command = "scoreboard objectives list" } | ConvertTo-Json)

$batch = Invoke-RestMethod "$BaseUrl/v1/command/batch" -Method Post -Headers $headers `
    -ContentType "application/json" -Body (@{
        commands = @(
            "scoreboard objectives add mcp_smoke dummy",
            "scoreboard players set #value mcp_smoke 7",
            "scoreboard players get #value mcp_smoke",
            "scoreboard objectives remove mcp_smoke"
        )
        stop_on_error = $true
    } | ConvertTo-Json -Depth 4)

if (-not $status.connected -or -not $validate.valid -or -not $batch.ok -or $batch.completed -ne 4) {
    throw "Bridge smoke test failed"
}

Write-Output "Bridge smoke test passed for Minecraft $($status.game_version)"
$batch.results | Select-Object command, ok, result, @{n="feedback";e={$_.feedback -join " | "}} | Format-Table -AutoSize
