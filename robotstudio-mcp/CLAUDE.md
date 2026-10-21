# RsMcp: instruções para o Claude Code no PC do usuário

Projeto escrito numa sessão na nuvem, sem Windows nem RobotStudio:
- `src/RsMcp.Server` (.NET 8, servidor MCP stdio): compilado e testado lá.
- `src/RsMcp.AddIn` (.NET 4.8, add-in do RobotStudio): **nunca compilado inteiro**. As chamadas da API do RobotStudio e do PC SDK foram escritas de memória; valide-as aqui contra as DLLs reais.
  - `ChatPanel.cs`, `Workspace.cs`, `LibraryFinder.cs` e `PipeServer.cs` não usam a API da ABB e já compilaram para net48 na nuvem.

Ambiente do usuário: Windows, RobotStudio 2025, controlador IRC5 com RobotWare 6. O usuário fala português e quer respostas curtas e objetivas.

**Licença**: segundo a documentação do SDK, add-ins que referenciam `ABB.Robotics.RobotStudio.dll` só rodam com RobotStudio **Premium**. Confirme a licença do usuário antes de tudo.

## Arquitetura (resumo)

Painel de chat (`ChatPanel.cs`) → `claude -p --mcp-config mcp.json` → `RsMcp.Server.exe` → named pipe `RsMcpBridge` → `PipeServer.cs` → `CommandDispatcher.cs`.
- Uma linha JSON por requisição: `{"method","params"}` → `{"ok","result"|"error"}`.
- Ferramentas recebem mm e graus (Euler ZYX); o add-in converte para m e rad.
- Comandos de estação rodam na thread da UI (`UiInvoker`). RAPID (`RapidCommands.cs`, PC SDK) roda na thread do pipe.
- Ao carregar, o add-in prepara `Documents\RsMcp` (`Workspace.cs`):
  - `mcp.json`, apontando para `<pasta do add-in>\server\RsMcp.Server.exe`;
  - `rsmcp-instrucoes.md`, regravado a partir de `Instrucoes.md` (recurso embutido), com as regras do assistente e o fluxo de layout por imagem;
  - `CLAUDE.md`, criado só se faltar: é do usuário (padrões da empresa) e importa as instruções;
  - `imagens\`, onde ficam os anexos do chat e as capturas do `capture_view`.
- O painel roda `claude -p` nessa pasta, com permissão só para `mcp__robotstudio` e `Read(./imagens/**)`.

## Tarefa: instalar e validar

1. **Pré-requisitos**: `dotnet --list-sdks` (precisa de 8.x). Localize a pasta Bin do RobotStudio:
   `Get-ChildItem "C:\Program Files*\ABB" -Recurse -Filter ABB.Robotics.RobotStudio.dll -ErrorAction SilentlyContinue`
2. **Formato e pasta de add-ins**: procure `.rsaddin` existentes na pasta do RobotStudio, em `$env:ProgramData\ABB` e em `$env:LOCALAPPDATA\ABB`.
   - Copie o esquema XML de um deles para `src/RsMcp.AddIn/RsMcp.AddIn.rsaddin`.
   - Ajuste o padrão de `$AddinDir` em `install.ps1` para a pasta que o RS 2025 realmente lê. Prefira uma pasta por usuário, se existir, porque dispensa administrador.
3. **Compilar o add-in**: `dotnet build src\RsMcp.AddIn\RsMcp.AddIn.csproj -c Release -p:RobotStudioBin="<Bin>"`.
   - Corrija os erros pelas assinaturas públicas reais: IntelliSense ou Object Browser do Visual Studio, documentação do SDK (developercenter.robotstudio.com) ou reflexão em PowerShell (`Add-Type -Path` + `GetMethods()`).
   - Maior risco (marcado com `TODO conferir` ou escrito sem validação):
     - estação: `Part.Load`, `Station.Save`/`SaveAs`, construtor de `RsMoveInstruction`, `Frame.Matrix`/`UserFrame.Matrix`, `Matrix4.EulerZYX`, `Simulator.Start`/`Stop`;
     - layout: `Body.CreateSolidBox(Matrix4, Vector3)`, `Part.Color`, `GraphicComponents.Remove`, `GraphicControl.ActiveGraphicControl`, `ScreenShot(ProjectObject, int, int, ScreenshotOptions, Vector3, float)`;
     - interface: `UiCommandIds`, `CommandBarButton.Execute`, varredura do ribbon (`UIEnvironment.RibbonTabs`, `RibbonGroup.Controls`, `CommandBarPopup.Controls`, `CommandBarControl.Id`/`Caption`), construtor de `ToolWindow`;
     - controlador: `new RsIrc5Controller(path)`, `station.Irc5Controllers`, `StartAsync(VirtualControllerRestartMode, IEnumerable<Mechanism>, bool, bool)`, `RsIrc5Controller.SystemId`, `ControllerManager.VirtualControllerReferences`;
     - PC SDK: `Controller.Connect`, `ControllerInfo.SystemId`, `Module.SaveToFile`, `FileSystem.PutFile`/`GetFile`.
   - Corrija de verdade; não comente nem remova uma ferramenta só para compilar. Se uma função não existir na API, diga ao usuário e proponha a alternativa.
4. **Instalar**: o RobotStudio precisa estar fechado.
   - Se a pasta de add-ins exigir administrador, peça ao usuário para rodar `.\install.ps1` num PowerShell como administrador (você não consegue elevar).
   - Se houver pasta por usuário, rode você mesmo.
5. **Testar com o usuário**, numa estação de teste nova:
   1. Abrir o RobotStudio. A aba "Claude" deve aparecer e o Output deve mostrar `[RsMcp] RsMcp carregado`.
   2. Conferir se `Documents\RsMcp` tem `mcp.json`, `rsmcp-instrucoes.md`, `CLAUDE.md` e `imagens\`.
   3. No terminal, em `$HOME\Documents\RsMcp`: `claude -p "use a ferramenta ping do robotstudio" --mcp-config mcp.json --allowedTools mcp__robotstudio`.
   4. Validar as ferramentas em ordem: `new_station`, `list_library` (deve achar os `.rslib` de robôs IRB; se não achar, descubra a pasta da ABB Library e inclua em `LibraryFinder`), `add_library`, `create_box`, `capture_view`, `remove_component`, `create_controller`, `create_workobject`, `create_target`, `create_path`, `auto_configure`, `sync_to_rapid`, `list_rapid_modules`, `read_rapid_module`, `write_rapid_module`, `simulation`. Depois `list_virtual_controllers` e `add_controller` numa segunda estação.
   5. `capture_view` com `view=top` deve mostrar a estação vista de cima. Se vier de baixo, em branco ou cortada, ajuste o `lookDirection` e o zoom em `CaptureView` até ficar certo.
   6. Por último, testar o painel de chat: anexar uma imagem (botão, arrastar e Ctrl+V) e pedir para montar um layout simples.

## Controlador virtual (pedido do usuário: o Claude deve criar o controlador)

- O SDK não tem API pública para "From Layout" (conferido na documentação e no fórum da ABB). A API pública só liga e inicia um sistema que já existe em disco: é o que o `add_controller` faz.
- `create_controller` abre o assistente From Layout (procura o botão pelo texto; confira com `list_ui_commands` e filtro "Layout") e espera o controlador aparecer na estação. O usuário só clica Finish.
- **Tarefa extra**: o usuário quer criar sem cliques. Procure nos assemblies do RS 2025, por reflexão de metadados (sem descompilar), um método que crie sistema a partir do layout. Comece pelos públicos.
  - Se só houver método interno: explique ao usuário que pode quebrar quando o RobotStudio for atualizado e só implemente com o OK dele.
  - Nesse caso, implemente dentro do `create_controller` (parâmetros: nome, versão do RobotWare, opções), mantendo o assistente como alternativa se falhar.

## Regras

- Não salve, feche nem altere estações do usuário sem pedido. Teste numa estação nova.
- Não reinicie nem resete controlador e não rode programa sem pedido explícito.
- RAPID sem acentos.
- Correções: commit no branch `ccr-e6c95204-httqsk`, sem `bin/` e `obj/`. Faça push quando o usuário pedir.
