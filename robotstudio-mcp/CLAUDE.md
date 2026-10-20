# RsMcp: instruções para o Claude Code no PC do usuário

Projeto escrito numa sessão na nuvem, sem Windows nem RobotStudio:
- `src/RsMcp.Server` (.NET 8, servidor MCP stdio): compilado e testado lá.
- `src/RsMcp.AddIn` (.NET 4.8, add-in do RobotStudio): **nunca compilado**. As chamadas da API do RobotStudio e do PC SDK foram escritas de memória; valide-as aqui contra as DLLs reais.

Ambiente do usuário: Windows, RobotStudio 2025, controlador IRC5 com RobotWare 6. O usuário fala português e quer respostas curtas e objetivas.

## Arquitetura (resumo)

Painel de chat (`ChatPanel.cs`) → `claude -p --mcp-config mcp.json` → `RsMcp.Server.exe` → named pipe `RsMcpBridge` → `PipeServer.cs` → `CommandDispatcher.cs`.
- Uma linha JSON por requisição: `{"method","params"}` → `{"ok","result"|"error"}`.
- Ferramentas recebem mm e graus (Euler ZYX); o add-in converte para m e rad.
- Comandos de estação rodam na thread da UI (`UiInvoker`). RAPID (`RapidCommands.cs`, PC SDK) roda na thread do pipe.
- O `ChatPanel` grava `Documents\RsMcp\mcp.json` ao abrir, apontando para `<pasta do add-in>\server\RsMcp.Server.exe`.

## Tarefa: instalar e validar

1. **Pré-requisitos**: `dotnet --list-sdks` (precisa de 8.x). Localize a pasta Bin do RobotStudio:
   `Get-ChildItem "C:\Program Files*\ABB" -Recurse -Filter ABB.Robotics.RobotStudio.dll -ErrorAction SilentlyContinue`
2. **Formato e pasta de add-ins**: procure `.rsaddin` existentes na pasta do RobotStudio, em `$env:ProgramData\ABB` e em `$env:LOCALAPPDATA\ABB`.
   - Copie o esquema XML de um deles para `src/RsMcp.AddIn/RsMcp.AddIn.rsaddin`.
   - Ajuste o padrão de `$AddinDir` em `install.ps1` para a pasta que o RS 2025 realmente lê. Prefira uma pasta por usuário, se existir, porque dispensa administrador.
3. **Compilar o add-in**: `dotnet build src\RsMcp.AddIn\RsMcp.AddIn.csproj -c Release -p:RobotStudioBin="<Bin>"`.
   - Corrija os erros pelas assinaturas públicas reais: IntelliSense ou Object Browser do Visual Studio, documentação do SDK (developercenter.robotstudio.com) ou reflexão em PowerShell (`Add-Type -Path` + `GetMethods()`).
   - Maior risco (marcado com `TODO conferir` ou escrito sem validação): `Part.Load`, `Station.Save`/`SaveAs`, `UiCommandIds` com `CommandBarButton.Execute`, construtor de `RsMoveInstruction`, `Frame.Matrix`/`UserFrame.Matrix`, `Matrix4.EulerZYX`, construtor de `ToolWindow`, `Simulator.Start`/`Stop`, PC SDK (`Controller.Connect`, `Module.SaveToFile`, `FileSystem.PutFile`/`GetFile`).
   - Corrija de verdade; não comente nem remova uma ferramenta só para compilar. Se uma função não existir na API, diga ao usuário e proponha a alternativa.
4. **Instalar**: o RobotStudio precisa estar fechado.
   - Se a pasta de add-ins exigir administrador, peça ao usuário para rodar `.\install.ps1` num PowerShell como administrador (você não consegue elevar).
   - Se houver pasta por usuário, rode você mesmo.
5. **Testar com o usuário**, numa estação de teste nova:
   1. Abrir o RobotStudio. A aba "Claude" deve aparecer e o Output deve mostrar `[RsMcp] RsMcp carregado`.
   2. Abrir o chat (isso grava o `mcp.json`).
   3. No terminal, em `$HOME\Documents\RsMcp`: `claude -p "use a ferramenta ping do robotstudio" --mcp-config mcp.json --allowedTools mcp__robotstudio`.
   4. Validar as ferramentas em ordem: `new_station`, `add_library` (procure um `.rslib` de robô IRB), criar o controlador pela UI (Robot System > From Layout, RW6), `create_workobject`, `create_target`, `create_path`, `auto_configure`, `sync_to_rapid`, `list_rapid_modules`, `read_rapid_module`, `write_rapid_module`, `simulation`.
   5. Por último, testar o painel de chat dentro do RobotStudio.

## Regras

- Não salve, feche nem altere estações do usuário sem pedido. Teste numa estação nova.
- Não reinicie nem resete controlador e não rode programa sem pedido explícito.
- RAPID sem acentos.
- Correções: commit no branch `ccr-e6c95204-httqsk`, sem `bin/` e `obj/`. Faça push quando o usuário pedir.
