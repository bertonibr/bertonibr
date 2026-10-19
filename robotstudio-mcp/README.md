# RsMcp: Claude dentro do RobotStudio

Chat dentro do ABB RobotStudio 2025 que cria estação e programa offline (IRC5 / RobotWare 6), usando o Claude Code e um servidor MCP.

```
Painel "Claude" no RobotStudio ──> claude -p (Claude Code, sua assinatura)
                                        │ MCP (stdio)
                                        ▼
                                 RsMcp.Server.exe (.NET 8)
                                        │ named pipe "RsMcpBridge" (só local, só seu usuário)
                                        ▼
                                 Add-in RsMcp (.NET 4.8, dentro do RobotStudio)
                                        ├─ API do RobotStudio (estação, targets, paths)
                                        └─ PC SDK (RAPID no controlador virtual)
```

O add-in usa a API do RobotStudio, sem controlar mouse nem teclado. O PC fica livre enquanto o Claude trabalha; o RobotStudio só precisa estar aberto.

## Requisitos

- Windows com RobotStudio 2025. A licença Premium é necessária para salvar a estação e importar geometria.
- .NET SDK 8 (`winget install Microsoft.DotNet.SDK.8`).
- Claude Code instalado e logado (`claude` funcionando no terminal).

## Instalação

```powershell
# PowerShell como administrador, com o RobotStudio fechado
cd robotstudio-mcp
.\install.ps1
# se o RobotStudio estiver em outra pasta:
.\install.ps1 -RobotStudioBin "D:\ABB\RobotStudio 2025\Bin"
```

Depois abra o RobotStudio e use a aba **Claude > Abrir chat**. Se a aba não aparecer, carregue o `.rsaddin` manualmente pela aba **Add-Ins**, ou confira no manual do SDK qual pasta de add-ins o RobotStudio 2025 lê.

## Ferramentas MCP

As ferramentas recebem mm e graus (Euler ZYX, como no Set Position).

| Ferramenta | O que faz |
|---|---|
| `ping`, `station_info` | Testa a conexão e resume a estação (componentes, task, tools, wobjs, targets, paths) |
| `new_station` | Cria uma estação vazia |
| `add_library` | Adiciona um `.rslib` (robô, posicionador...) e posiciona |
| `import_geometry` | Importa CAD e posiciona |
| `set_position`, `attach` | Posiciona um componente; anexa um componente a um mecanismo |
| `create_workobject`, `create_target`, `create_path` | Programação offline na task ativa |
| `auto_configure`, `sync_to_rapid` | Equivalem a Auto Configuration e Synchronize to RAPID |
| `list_rapid_modules`, `read_rapid_module`, `write_rapid_module` | RAPID no controlador virtual (PC SDK) |
| `simulation` | Inicia e para a simulação |
| `save_station` | Salva a estação |

O controlador virtual é criado pela UI (**Robot System > From Layout**). Depois disso as ferramentas de task e RAPID passam a funcionar.

## Estado

- `RsMcp.Server` compila e foi testado via stdio: lista as ferramentas e responde quando o RobotStudio não está aberto.
- `RsMcp.AddIn` ainda **não foi compilado**, porque depende das DLLs do RobotStudio, que só existem no Windows. Os pontos marcados com `TODO conferir` usam chamadas que precisam ser validadas contra o SDK 2025:
  - importação de geometria (`Part.Load`);
  - salvar a estação (`Save`/`SaveAs`);
  - IDs dos comandos da UI (`UiCommandIds`) para sync e auto configuration.

## Usar sem o painel (opcional)

O mesmo servidor funciona no Claude Code ou no Claude Desktop:

```powershell
claude mcp add robotstudio -- "C:\Program Files (x86)\ABB\RobotStudio 2025\Bin\Addins\RsMcp\server\RsMcp.Server.exe"
```
