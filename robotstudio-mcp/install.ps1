# Compila o add-in e o servidor MCP e instala na pasta de add-ins do RobotStudio.
# Rode no PowerShell como administrador (a pasta fica em Program Files), com o RobotStudio fechado.
param(
    [string]$RobotStudioBin = "C:\Program Files (x86)\ABB\RobotStudio 2025\Bin",
    [string]$AddinDir = ""
)
$ErrorActionPreference = "Stop"
if (-not $AddinDir) { $AddinDir = Join-Path $RobotStudioBin "Addins\RsMcp" }
if (-not (Test-Path (Join-Path $RobotStudioBin "ABB.Robotics.RobotStudio.dll"))) {
    throw "RobotStudio nao encontrado em '$RobotStudioBin'. Passe -RobotStudioBin <pasta Bin>."
}

$src = Join-Path $PSScriptRoot "src"

dotnet build (Join-Path $src "RsMcp.AddIn\RsMcp.AddIn.csproj") -c Release -p:RobotStudioBin="$RobotStudioBin"
if ($LASTEXITCODE -ne 0) { throw "Falha ao compilar o add-in." }

dotnet publish (Join-Path $src "RsMcp.Server\RsMcp.Server.csproj") -c Release -r win-x64 --self-contained false -o (Join-Path $AddinDir "server")
if ($LASTEXITCODE -ne 0) { throw "Falha ao publicar o servidor MCP." }

$addinOut = Join-Path $src "RsMcp.AddIn\bin\Release\net48"
Copy-Item (Join-Path $addinOut "RsMcp.AddIn.dll") $AddinDir -Force
Copy-Item (Join-Path $addinOut "RsMcp.AddIn.rsaddin") $AddinDir -Force

Write-Host "Instalado em $AddinDir. Abra o RobotStudio: aba 'Claude' > 'Abrir chat'."
