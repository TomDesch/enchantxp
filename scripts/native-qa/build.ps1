[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$ApiJar,
    [string]$PluginJar,
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$')][string]$BuildName = ('native-build-' + [guid]::NewGuid().ToString('N'))
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'qa-controls.ps1')
$project = Get-QaProject
Assert-QaJava $JavaHome 21 -Compiler
if (-not $PluginJar) { $PluginJar = $project.Plugin }
$api = (Resolve-Path -LiteralPath $ApiJar).Path
$plugin = (Resolve-Path -LiteralPath $PluginJar).Path
foreach ($file in @($api, $plugin)) { Assert-QaRegularPath $file }
$directory = New-QaDirectory $project.Work $BuildName
$classes = Join-Path $directory 'classes'
New-Item -ItemType Directory $classes | Out-Null
$classpath = "$api;$plugin"
& (Join-Path $JavaHome 'bin\javac.exe') --release 8 -encoding UTF-8 -cp $classpath -d $classes `
    (Join-Path $PSScriptRoot 'EnchantQA.java') (Join-Path $PSScriptRoot 'OwnershipRegression.java')
if ($LASTEXITCODE -ne 0) { throw "Native QA compilation failed: $LASTEXITCODE" }
$metadata = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'plugin.yml')).Replace('@version@', $project.Version)
[IO.File]::WriteAllText((Join-Path $classes 'plugin.yml'), $metadata, [Text.UTF8Encoding]::new($false))
$artifact = Join-Path $directory 'EnchantQA.jar'
& (Join-Path $JavaHome 'bin\jar.exe') --create --file $artifact -C $classes .
if ($LASTEXITCODE -ne 0) { throw "Native QA packaging failed: $LASTEXITCODE" }
& (Join-Path $JavaHome 'bin\java.exe') -cp "$classes;$classpath" local.enchantqa.OwnershipRegression `
    (Join-Path $directory 'ownership-regression')
if ($LASTEXITCODE -ne 0) { throw "Native ownership regression failed: $LASTEXITCODE" }
Write-Output "QA_JAR=$artifact"
Write-Output "QA_SHA256=$((Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash)"
