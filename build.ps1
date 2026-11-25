# Builds frame.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
$ErrorActionPreference = 'Stop'
$here  = $PSScriptRoot
$tools = Join-Path (Split-Path $here) 'tools'
$env:JAVA_HOME = (Get-ChildItem "$tools\jdk" -Directory | Select-Object -First 1).FullName
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$sdk = "$tools\sdk"
$bt  = (Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name | Select-Object -Last 1).FullName
$jar = (Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name | Select-Object -Last 1).FullName + '\android.jar'
$out = "$here\build"
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\gen","$out\obj","$out\dex" | Out-Null

function Run($exe, [string[]]$a) { & $exe @a; if ($LASTEXITCODE -ne 0) { throw "$exe failed ($LASTEXITCODE)" } }

Run "$bt\aapt2.exe" @('compile', '--dir', "$here\res", '-o', "$out\res.zip")
Run "$bt\aapt2.exe" @('link', '-o', "$out\base.apk", '-I', $jar, '--manifest', "$here\AndroidManifest.xml",
    '--java', "$out\gen", '--min-sdk-version', '28', '--target-sdk-version', '34', "$out\res.zip")

$srcs = Get-ChildItem "$here\src","$out\gen" -Recurse -Filter *.java | ForEach-Object FullName
Run javac (@('--release', '8', '-classpath', $jar, '-encoding', 'UTF-8', '-d', "$out\obj") + $srcs)
Run jar @('cf', "$out\classes.jar", '-C', "$out\obj", '.')
Run "$bt\d8.bat" @('--release', '--min-api', '28', '--lib', $jar, '--output', "$out\dex", "$out\classes.jar")

Copy-Item "$out\base.apk" "$out\unsigned.apk"
Run jar @('uf', "$out\unsigned.apk", '-C', "$out\dex", 'classes.dex')
# Assets added with jar (forward-slash paths; aapt2 -A on Windows writes backslashes), stored uncompressed (0).
if (Test-Path "$here\assets") { Run jar @('uf0', "$out\unsigned.apk", '-C', $here, 'assets') }
Run "$bt\zipalign.exe" @('-f', '4', "$out\unsigned.apk", "$out\aligned.apk")

$ks = "$here\debug.keystore"
if (-not (Test-Path $ks)) {
    Run keytool @('-genkeypair', '-keystore', $ks, '-storepass', 'android', '-keypass', 'android', '-alias', 'frame',
        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Frame')
}
Run "$bt\apksigner.bat" @('sign', '--ks', $ks, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
    '--out', "$here\frame.apk", "$out\aligned.apk")
Write-Host "Built $here\frame.apk"
