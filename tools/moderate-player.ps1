[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Ban', 'Unban', 'Mute', 'Unmute')]
    [string]$Action,

    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9._-]{3,64}$')]
    [string]$PlayerId,

    [string]$ModerationFile = (Join-Path $PSScriptRoot '..\server-data\moderation.properties')
)

$ErrorActionPreference = 'Stop'
$banned = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$muted = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)

if (Test-Path -LiteralPath $ModerationFile -PathType Leaf) {
    foreach ($line in Get-Content -LiteralPath $ModerationFile) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Length -ne 2) { continue }
        $key = $parts[0].Trim()
        foreach ($id in $parts[1].Split(',')) {
            if ([string]::IsNullOrWhiteSpace($id)) { continue }
            if ($key -eq 'banned') {
                [void]$banned.Add($id.Trim())
            } elseif ($key -eq 'muted') {
                [void]$muted.Add($id.Trim())
            }
        }
    }
}

switch ($Action) {
    'Ban' { [void]$banned.Add($PlayerId) }
    'Unban' { [void]$banned.Remove($PlayerId) }
    'Mute' { [void]$muted.Add($PlayerId) }
    'Unmute' { [void]$muted.Remove($PlayerId) }
}

$parent = Split-Path -Parent $ModerationFile
if (-not [string]::IsNullOrWhiteSpace($parent)) {
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
}
$lines = @(
    '# Syx Kingdoms Online moderation state; reloaded by the server within five seconds.'
    'banned=' + (($banned | Sort-Object) -join ',')
    'muted=' + (($muted | Sort-Object) -join ',')
)
Set-Content -LiteralPath $ModerationFile -Value $lines -Encoding UTF8
Write-Host "$Action applied to $PlayerId in $ModerationFile"
