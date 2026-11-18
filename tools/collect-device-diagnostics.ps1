param(
    [string]$AdbPath,
    [string]$Serial,
    [ValidateSet('baseline', 'system-hotspot', 'app-hotspot')]
    [string]$Stage = 'baseline',
    [switch]$IncludeSystemPackages
)

$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
if (-not $AdbPath) {
    $bundledAdb = Join-Path $projectPath '.tools\android-sdk\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $bundledAdb) {
        $AdbPath = $bundledAdb
    } else {
        $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
        if ($adbCommand) {
            $AdbPath = $adbCommand.Source
        }
    }
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath)) {
    throw 'ADB not found. Install Android SDK Platform-Tools and pass -AdbPath with the full path to adb.exe.'
}

$deviceLines = @(& $AdbPath devices)
if ($LASTEXITCODE -ne 0) {
    throw 'Could not list ADB devices.'
}
$connectedSerials = @($deviceLines | ForEach-Object {
    if ($_ -match '^(\S+)\s+device$') { $Matches[1] }
})
if ($Serial) {
    if ($connectedSerials -notcontains $Serial) {
        throw 'Requested device is not connected and authorized. Check USB debugging and the phone authorization prompt.'
    }
} elseif ($connectedSerials.Count -eq 1) {
    $Serial = $connectedSerials[0]
} else {
    throw 'Connect and authorize exactly one device, or select one with -Serial.'
}

$folderName = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Stage + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6)
$outputPath = Join-Path $projectPath ('diagnostics\' + $folderName)
New-Item -ItemType Directory -Path $outputPath | Out-Null
$script:diagnosticFailures = @()

function Save-AdbOutput {
    param([string]$Name, [string[]]$Arguments)
    $ErrorActionPreference = 'Continue'
    $capturedOutput = @(& $AdbPath -s $Serial @Arguments 2>&1 | ForEach-Object { "$_" })
    $exitCode = $LASTEXITCODE
    $capturedOutput | Set-Content -LiteralPath (Join-Path $outputPath ($Name + '.txt')) -Encoding UTF8
    if ($exitCode -ne 0) {
        $script:diagnosticFailures += "$Name (exit $exitCode)"
        Write-Warning "$Name failed; error saved in the report."
    }
    return $capturedOutput
}

$propertyNames = @('ro.product.manufacturer', 'ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.build.display.id', 'ro.build.fingerprint', 'ro.product.cpu.abi')
$properties = foreach ($propertyName in $propertyNames) {
    $value = Save-AdbOutput -Name $propertyName -Arguments @('shell', 'getprop', $propertyName)
    "$propertyName=$($value -join ' ')"
}
$properties | Set-Content -LiteralPath (Join-Path $outputPath 'device.txt') -Encoding UTF8
$captures = @(
    @{ Name = 'connectivity'; Arguments = @('shell', 'dumpsys', 'connectivity') },
    @{ Name = 'network-management'; Arguments = @('shell', 'dumpsys', 'network_management') },
    @{ Name = 'wifi'; Arguments = @('shell', 'dumpsys', 'wifi') },
    @{ Name = 'app-package'; Arguments = @('shell', 'dumpsys', 'package', 'com.example.hotspottoggle') },
    @{ Name = 'settings-package'; Arguments = @('shell', 'dumpsys', 'package', 'com.android.settings') },
    @{ Name = 'framework-package'; Arguments = @('shell', 'dumpsys', 'package', 'android') },
    @{ Name = 'system-packages'; Arguments = @('shell', 'pm', 'list', 'packages', '-s', '-f') },
    @{ Name = 'services'; Arguments = @('shell', 'service', 'list') },
    @{ Name = 'framework-files'; Arguments = @('shell', 'ls', '-l', '/system/framework') },
    @{ Name = 'write-settings'; Arguments = @('shell', 'appops', 'get', 'com.example.hotspottoggle', 'WRITE_SETTINGS') },
    @{ Name = 'hotspot-log'; Arguments = @('shell', 'logcat', '-d', '-v', 'threadtime', '-t', '1500', 'HotspotToggle:V', 'Tethering:V', 'ConnectivityService:W', 'WifiService:W', 'NetworkManagement:W', '*:S') }
)
foreach ($capture in $captures) {
    Write-Host "Reading $($capture.Name)..."
    $null = Save-AdbOutput -Name $capture.Name -Arguments $capture.Arguments
}

if ($IncludeSystemPackages) {
    $systemPath = Join-Path $outputPath 'system'
    New-Item -ItemType Directory -Path $systemPath | Out-Null
    $systemFiles = @('/system/framework/services.jar', '/system/framework/framework.jar', '/system/framework/framework-res.apk')
    $settingsPaths = Save-AdbOutput -Name 'settings-apk-paths' -Arguments @('shell', 'pm', 'path', 'com.android.settings')
    foreach ($settingsPath in $settingsPaths) {
        if ($settingsPath -match '^package:(/\S+)$') {
            $systemFiles += $Matches[1]
        }
    }
    $fileIndex = 0
    foreach ($systemFile in $systemFiles) {
        $fileIndex++
        $fileName = $fileIndex.ToString() + '-' + ($systemFile -split '/')[-1]
        $null = Save-AdbOutput -Name ('pull-' + $fileIndex) -Arguments @('pull', $systemFile, (Join-Path $systemPath $fileName))
    }
}

@(
    "Stage: $Stage",
    "Collected: $((Get-Date).ToString('o'))",
    'Read-only collection. No settings, hotspot state, app installation or phone files were changed.',
    'Network dumps may contain Wi-Fi names, IP addresses and hotspot credentials. Review before sharing.',
    ('Failed captures: ' + ($script:diagnosticFailures -join ', '))
) | Set-Content -LiteralPath (Join-Path $outputPath 'collection.txt') -Encoding UTF8
Write-Host "Saved diagnostics to $outputPath"
