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

- Windows com RobotStudio 2025 e **licença Premium**: o SDK da ABB exige Premium para rodar add-ins que usam a API do RobotStudio.
- .NET SDK 8 (`winget install Microsoft.DotNet.SDK.8`).
- Claude Code instalado e logado (`claude` funcionando no terminal).

## Instalação com o Claude Code no seu PC (recomendado)

1. No PowerShell:
   ```powershell
   winget install -e --id Git.Git
   winget install -e --id Microsoft.DotNet.SDK.8
   irm https://claude.ai/install.ps1 | iex
   ```
2. Feche e abra o PowerShell de novo, depois rode:
   ```powershell
   git clone -b ccr-e6c95204-httqsk https://github.com/bertonibr/bertonibr.git $HOME\RsMcp
   cd $HOME\RsMcp\robotstudio-mcp
   claude
   ```
   Para acompanhar pelo app Claude no celular, use `claude remote-control` no lugar de `claude`.
3. Peça: **"Instale o RsMcp seguindo o CLAUDE.md"**. O Claude compila, corrige o que for preciso e guia os testes.

## Instalação manual

```powershell
# PowerShell como administrador, com o RobotStudio fechado
cd robotstudio-mcp
.\install.ps1
# se o RobotStudio estiver em outra pasta:
.\install.ps1 -RobotStudioBin "D:\ABB\RobotStudio 2025\Bin"
```

Depois abra o RobotStudio e use a aba **Claude > Abrir chat**. Se a aba não aparecer, carregue o `.rsaddin` manualmente pela aba **Add-Ins**, ou confira no manual do SDK qual pasta de add-ins o RobotStudio 2025 lê.

## Montar layout a partir de imagem

1. No chat, anexe a imagem do layout: botão **Anexar...**, arrastar o arquivo ou **Ctrl+V** de um print. Aceita PNG, JPG e PDF.
2. O Claude lista o que identificou e pergunta o que faltar, até 3 perguntas por vez e sempre com uma sugestão: modelo do robô, origem, escala, alturas.
3. Ele monta em etapas (robô → posicionador e mesas → peça → cerca). Depois de cada etapa, tira uma imagem da estação, compara com a sua e mostra o que fez.
4. Equipamento sem CAD vira um bloco provisório colorido, que é trocado pelo CAD quando você mandar o arquivo.
5. Com o layout aprovado, ele cria o controlador.

Para registrar padrões da sua empresa (altura das mesas, robôs mais usados, distância da cerca), edite `Documents\RsMcp\CLAUDE.md`. O add-in não sobrescreve esse arquivo.

## Sua biblioteca de componentes

Guarde barreiras de luz, IHMs, botoeiras, painéis de cerca e outros itens em `Documents\RsMcp\biblioteca`. O Claude procura ali antes de criar um bloco provisório; quando acha o item, só pergunta onde colocar e quantos são.

**Pelo chat (mais fácil)**: anexe o arquivo (CAD ou `.rslib`) e diga o que é, por exemplo: *"barreira de luz Sick C4000, altura 300 mm do chão"*. O Claude guarda na categoria certa, com nome e observações, e avisa onde ficou. Peça ou dispositivo de um projeto específico não vai para a biblioteca; na dúvida, ele pergunta.

- **Formatos**: `.rslib` ou CAD (STEP, SAT, STL, glb...).
- **Categorias**: cada subpasta vira uma categoria (já vêm `Seguranca`, `IHM`, `Cerca` e `Outros`).
- **Observações**: um `.txt` com o mesmo nome do arquivo, por exemplo `Barreira_Sick_C4000.txt` com "Altura: 300 mm do chão. Fixar na coluna da cerca." O Claude segue essas observações.
- **Outras pastas**: inclua uma por linha em `Documents\RsMcp\pastas-biblioteca.txt`. Pode ser uma pasta de rede, para a equipe usar a mesma biblioteca.
- **Dica**: deixe a origem do modelo no ponto que encosta no chão ou na fixação. Assim ele posiciona certo de primeira.

## Ferramentas MCP

As ferramentas recebem mm e graus (Euler ZYX, como no Set Position).

| Ferramenta | O que faz |
|---|---|
| `ping`, `station_info` | Testa a conexão e resume a estação (componentes, task, tools, wobjs, targets, paths) |
| `new_station` | Cria uma estação vazia |
| `add_library` | Adiciona um `.rslib` (robô, posicionador...) e posiciona |
| `import_geometry` | Importa CAD e posiciona |
| `set_position`, `attach` | Posiciona um componente; anexa um componente a um mecanismo |
| `list_library` | Procura robôs, posicionadores e trilhos (`.rslib`) instalados no PC |
| `list_my_library` | Lista a sua biblioteca de componentes, com categoria e observações |
| `save_to_library`, `set_library_notes` | Guarda na biblioteca um arquivo anexado no chat; grava as observações de um item |
| `create_box`, `remove_component` | Bloco provisório para equipamento sem CAD (mesa, base, cerca...); remove componentes |
| `capture_view` | Gera uma imagem da estação (topo, iso ou vista atual) para o Claude conferir o layout |
| `create_controller` | Cria o controlador virtual pelo From Layout: abre o assistente e espera você clicar Finish |
| `add_controller`, `list_virtual_controllers` | Liga a estação a um sistema RW6 já existente e o inicia, sem cliques |
| `create_workobject`, `create_target`, `create_path` | Programação offline na task ativa |
| `auto_configure`, `sync_to_rapid` | Equivalem a Auto Configuration e Synchronize to RAPID |
| `list_rapid_modules`, `read_rapid_module`, `write_rapid_module` | RAPID no controlador virtual (PC SDK) |
| `simulation` | Inicia e para a simulação |
| `save_station` | Salva a estação |
| `list_ui_commands` | Diagnóstico: lista os comandos do ribbon |

O SDK da ABB não tem função pública para o "From Layout". Por isso o `create_controller` abre o assistente: você só confere e clica Finish, e o Claude continua sozinho. Para criar sem nenhum clique, o Claude no seu PC vai investigar uma função interna do RobotStudio 2025 (ver `CLAUDE.md`).

## Estado

- `RsMcp.Server` compila e foi testado via stdio: lista as ferramentas e responde quando o RobotStudio não está aberto.
- `RsMcp.AddIn` ainda **não foi compilado inteiro**, porque depende das DLLs do RobotStudio, que só existem no Windows. As partes sem API da ABB (chat, anexos, busca de bibliotecas e pipe) já compilam para .NET 4.8. Os pontos marcados com `TODO conferir` usam chamadas que precisam ser validadas contra o SDK 2025:
  - importação de geometria (`Part.Load`);
  - salvar a estação (`Save`/`SaveAs`);
  - IDs dos comandos da UI (`UiCommandIds`) para sync, auto configuration e From Layout;
  - controlador virtual (`RsIrc5Controller`, `StartAsync`, `ControllerManager`);
  - layout: cor do bloco (`Part.Color`) e captura da vista de topo (`ScreenShot`).

## Usar sem o painel (opcional)

O mesmo servidor funciona no Claude Code ou no Claude Desktop:

```powershell
claude mcp add robotstudio -- "C:\Program Files (x86)\ABB\RobotStudio 2025\Bin\Addins\RsMcp\server\RsMcp.Server.exe"
```
