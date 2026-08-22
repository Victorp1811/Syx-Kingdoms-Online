[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Source,
    [string]$Destination = '',
    [int]$Dimension = 1024,
    [ValidateRange(0, 25)]
    [double]$OceanBorderPercent = 4
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$Destination = if ([string]::IsNullOrWhiteSpace($Destination)) {
    Join-Path $PSScriptRoot '..\mod\V71\assets\sprite\world\generatorMaps\Syx_Kingdoms_Online.png'
} else {
    $Destination
}
$destinationPath = [IO.Path]::GetFullPath($Destination)
$destinationDirectory = Split-Path -Parent $destinationPath
New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
$temporaryPath = Join-Path $destinationDirectory 'Syx_Kingdoms_Online.normalized.png'

$sourceImage = [Drawing.Image]::FromFile($sourcePath)
$output = New-Object Drawing.Bitmap $Dimension, $Dimension, ([Drawing.Imaging.PixelFormat]::Format24bppRgb)
try {
    $output.SetResolution(96, 96)
    $graphics = [Drawing.Graphics]::FromImage($output)
    try {
        $graphics.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
        $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::HighQuality
        $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.Clear([Drawing.Color]::Black)
        $padding = [int][Math]::Round($Dimension * $OceanBorderPercent / 100.0)
        $innerDimension = $Dimension - $padding * 2
        if ($innerDimension -lt 1) {
            throw 'Ocean border leaves no drawable heightmap area.'
        }
        $graphics.DrawImage($sourceImage, $padding, $padding, $innerDimension, $innerDimension)
    } finally {
        $graphics.Dispose()
    }
    $output.Save($temporaryPath, [Drawing.Imaging.ImageFormat]::Png)
} finally {
    $output.Dispose()
    $sourceImage.Dispose()
}

Move-Item -LiteralPath $temporaryPath -Destination $destinationPath -Force
Write-Host "Normalized native world heightmap: $destinationPath ($Dimension x $Dimension, $OceanBorderPercent% ocean border)"
