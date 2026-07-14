param(
    [string]$JavaHome = "$env:APPDATA\.minecraft\runtime\java-runtime-epsilon"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
    throw "Java 25 was not found at $JavaHome. Pass -JavaHome or install Minecraft 26.2 once."
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$env:Path"
$fabric = Join-Path $PSScriptRoot "..\mods\fabric"
New-Item -ItemType Directory -Force (Join-Path $fabric "run") | Out-Null
Set-Content -LiteralPath (Join-Path $fabric "run\eula.txt") -Value "eula=true" -Encoding ASCII
& (Join-Path $fabric "gradlew.bat") -p $fabric runServer
