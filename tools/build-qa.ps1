param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$BuildDir = 'build-qa-win'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Sdk) { $Sdk = 'C:\Android\Sdk' }
if (-not $Jdk) { $Jdk = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot' }
if (-not (Test-Path -LiteralPath $Jdk)) { throw "JDK not found: $Jdk" }
if (-not (Test-Path -LiteralPath $Sdk)) { throw "Android SDK not found: $Sdk" }

$buildTools = Get-ChildItem -LiteralPath (Join-Path $Sdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'No build-tools installation found' }
$bt = $buildTools.FullName
$androidJar = Join-Path $Sdk 'platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { throw "android-35 platform not found: $androidJar" }

$javaHome = $Jdk
$env:JAVA_HOME = $javaHome
$javac = Join-Path $javaHome 'bin\javac.exe'
$jar = Join-Path $javaHome 'bin\jar.exe'

$qa = Join-Path $root $BuildDir
$classes = Join-Path $qa 'classes'
$dex = Join-Path $qa 'dex'
$generated = Join-Path $qa 'generated'
New-Item -ItemType Directory -Force $qa, $classes, $dex, $generated | Out-Null
Get-ChildItem -LiteralPath $classes -File -Recurse -ErrorAction SilentlyContinue | Remove-Item -Force
Get-ChildItem -LiteralPath $dex -File -Recurse -ErrorAction SilentlyContinue | Remove-Item -Force
Get-ChildItem -LiteralPath $generated -File -Recurse -ErrorAction SilentlyContinue | Remove-Item -Force
@('res.zip', 'unsigned.apk', 'with-dex.apk', 'aligned.apk', 'classes.jar', 'qa.apk', 'qa.apk.idsig') |
    ForEach-Object { Remove-Item -LiteralPath (Join-Path $qa $_) -Force -ErrorAction SilentlyContinue }

Write-Host '1/5 Compiling resources'
& (Join-Path $bt 'aapt2.exe') compile --dir (Join-Path $root 'app\src\main\res') -o (Join-Path $qa 'res.zip')
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed: $LASTEXITCODE" }

Write-Host '2/5 Linking QA manifest'
& (Join-Path $bt 'aapt2.exe') link -o (Join-Path $qa 'unsigned.apk') `
    -I $androidJar `
    --manifest (Join-Path $root 'tests\native\AndroidManifest.xml') `
    -R (Join-Path $qa 'res.zip') `
    --java $generated `
    --custom-package com.example.reviewreminder `
    --min-sdk-version 23 --target-sdk-version 28 --debug-mode
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed: $LASTEXITCODE" }

Write-Host '3/5 Compiling app, QA and generated R.java'
$sources = @(
    Get-ChildItem -Path (Join-Path $root 'app\src\main\java'), (Join-Path $root 'tests\native\java'), $generated `
        -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
)
& $javac --release 8 -encoding UTF-8 -cp $androidJar -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

Write-Host '4/5 Dexing and repackaging'
& $jar cf (Join-Path $qa 'classes.jar') -C $classes .
& (Join-Path $bt 'd8.bat') --min-api 23 --lib $androidJar --output $dex (Join-Path $qa 'classes.jar')
if ($LASTEXITCODE -ne 0) { throw "d8 failed: $LASTEXITCODE" }
Copy-Item -LiteralPath (Join-Path $qa 'unsigned.apk') -Destination (Join-Path $qa 'with-dex.apk') -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open((Join-Path $qa 'with-dex.apk'), [System.IO.Compression.ZipArchiveMode]::Update)
$entry = $zip.GetEntry('classes.dex')
if ($entry) { $entry.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, (Join-Path $dex 'classes.dex'), 'classes.dex', [System.IO.Compression.CompressionLevel]::Optimal) > $null
$zip.Dispose()
& (Join-Path $bt 'zipalign.exe') -f 4 (Join-Path $qa 'with-dex.apk') (Join-Path $qa 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw "zipalign failed: $LASTEXITCODE" }

Write-Host '5/5 Signing'
$keystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (-not (Test-Path -LiteralPath $keystore)) {
    & (Join-Path $javaHome 'bin\keytool.exe') -genkeypair -keystore $keystore `
        -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 `
        -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
    if ($LASTEXITCODE -ne 0) { throw "keytool failed: $LASTEXITCODE" }
}
& (Join-Path $bt 'apksigner.bat') sign --ks $keystore --ks-pass pass:android `
    --out (Join-Path $qa 'qa.apk') (Join-Path $qa 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw "apksigner failed: $LASTEXITCODE" }

$apk = Join-Path $qa 'qa.apk'
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
Write-Host "QA APK: $apk"
Write-Host "SIZE: $((Get-Item -LiteralPath $apk).Length)"
Write-Host "SHA256: $hash"
