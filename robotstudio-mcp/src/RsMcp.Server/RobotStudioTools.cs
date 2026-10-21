using System.ComponentModel;
using ModelContextProtocol.Server;

namespace RsMcp.Server;

// Unidades nas ferramentas: mm e graus (como na UI do RobotStudio).
// O add-in converte para m e rad, que sao as unidades da API.
[McpServerToolType]
public static class RobotStudioTools
{
    [McpServerTool(Name = "ping"), Description("Testa a conexao com o RobotStudio.")]
    public static Task<string> Ping(BridgeClient bridge, CancellationToken ct)
        => bridge.CallAsync("ping", null, ct);

    [McpServerTool(Name = "station_info"), Description(
        "Resumo da estacao ativa: componentes, mecanismos, task ativa, tools, workobjects, targets e paths.")]
    public static Task<string> StationInfo(BridgeClient bridge, CancellationToken ct)
        => bridge.CallAsync("station_info", null, ct);

    [McpServerTool(Name = "new_station"), Description("Cria uma estacao vazia e a torna ativa.")]
    public static Task<string> NewStation(BridgeClient bridge, CancellationToken ct)
        => bridge.CallAsync("new_station", null, ct);

    [McpServerTool(Name = "add_library"), Description(
        "Adiciona um componente de biblioteca (.rslib), por exemplo um robo da ABB Library, " +
        "e o posiciona no mundo. Caminho absoluto no PC do usuario.")]
    public static Task<string> AddLibrary(BridgeClient bridge,
        [Description("Caminho do .rslib")] string path,
        [Description("Nome no layout (opcional)")] string? name = null,
        double x = 0, double y = 0, double z = 0, double rx = 0, double ry = 0, double rz = 0,
        CancellationToken ct = default)
        => bridge.CallAsync("add_library", new { path, name, x, y, z, rx, ry, rz }, ct);

    [McpServerTool(Name = "import_geometry"), Description(
        "Importa geometria (STEP, SAT, STL, glb...) e posiciona no mundo. STEP exige a opcao de CAD converter.")]
    public static Task<string> ImportGeometry(BridgeClient bridge,
        [Description("Caminho do arquivo")] string path,
        [Description("Nome no layout (opcional)")] string? name = null,
        double x = 0, double y = 0, double z = 0, double rx = 0, double ry = 0, double rz = 0,
        CancellationToken ct = default)
        => bridge.CallAsync("import_geometry", new { path, name, x, y, z, rx, ry, rz }, ct);

    [McpServerTool(Name = "set_position"), Description(
        "Define a posicao de um componente em relacao ao mundo. mm e graus (Euler ZYX, como no Set Position).")]
    public static Task<string> SetPosition(BridgeClient bridge,
        [Description("Nome do componente no layout")] string name,
        double x, double y, double z, double rx = 0, double ry = 0, double rz = 0,
        CancellationToken ct = default)
        => bridge.CallAsync("set_position", new { name, x, y, z, rx, ry, rz }, ct);

    [McpServerTool(Name = "list_library"), Description(
        "Procura bibliotecas .rslib (robos, posicionadores, trilhos) instaladas no PC. " +
        "Filtre por partes do nome, ex.: \"2600\" ou \"IRBP A\". Use o caminho retornado em add_library.")]
    public static Task<string> ListLibrary(BridgeClient bridge,
        [Description("Partes do nome separadas por espaco")] string filter = "",
        [Description("Refaz a busca no disco")] bool refresh = false,
        CancellationToken ct = default)
        => bridge.CallAsync("list_library", new { filter, refresh }, ct);

    [McpServerTool(Name = "create_box"), Description(
        "Cria um bloco (caixa) para representar equipamento sem CAD: mesa, base, dispositivo, cerca, esteira. " +
        "A origem fica no centro da face de baixo; x, y, z posicionam essa origem no mundo. mm e graus.")]
    public static Task<string> CreateBox(BridgeClient bridge,
        [Description("Nome unico no layout")] string name,
        [Description("Comprimento em X (mm)")] double length,
        [Description("Largura em Y (mm)")] double width,
        [Description("Altura em Z (mm)")] double height,
        double x = 0, double y = 0, double z = 0,
        [Description("Rotacao em torno de Z (graus)")] double rz = 0,
        [Description("Cor em hex, ex.: #FFC000 (opcional)")] string? color = null,
        CancellationToken ct = default)
        => bridge.CallAsync("create_box", new { name, length, width, height, x, y, z, rz, color }, ct);

    [McpServerTool(Name = "remove_component"), Description(
        "Remove um componente da estacao. Nao remova robo ligado a controlador sem confirmar com o usuario.")]
    public static Task<string> RemoveComponent(BridgeClient bridge, string name, CancellationToken ct = default)
        => bridge.CallAsync("remove_component", new { name }, ct);

    [McpServerTool(Name = "capture_view"), Description(
        "Gera uma imagem PNG da estacao para conferir o layout. view: top (vista de topo), iso ou current (vista atual). " +
        "Retorna o caminho do arquivo; abra com a ferramenta Read.")]
    public static Task<string> CaptureView(BridgeClient bridge,
        string view = "top", int width = 1600, int height = 1000, CancellationToken ct = default)
        => bridge.CallAsync("capture_view", new { view, width, height }, ct);

    [McpServerTool(Name = "attach"), Description("Anexa um componente a outro (ex.: tocha no robo). Leva o filho ate o ponto de montagem.")]
    public static Task<string> Attach(BridgeClient bridge,
        [Description("Componente filho")] string child,
        [Description("Componente pai")] string parent,
        CancellationToken ct = default)
        => bridge.CallAsync("attach", new { child, parent }, ct);

    [McpServerTool(Name = "create_controller"), Description(
        "Cria o controlador virtual pela funcao From Layout do RobotStudio. Posicione robos e mecanismos antes. " +
        "Abre o assistente no RobotStudio e espera o usuario concluir (Finish); avise o usuario disso. " +
        "Retorna o station_info quando o controlador estiver pronto.")]
    public static Task<string> CreateController(BridgeClient bridge,
        [Description("Minutos de espera pelo usuario")] double timeoutMinutes = 15,
        CancellationToken ct = default)
        => bridge.CallAsync("create_controller", new { timeoutMinutes }, ct);

    [McpServerTool(Name = "add_controller"), Description(
        "Liga a estacao a um sistema RW6 ja existente (pasta do controlador virtual) e o inicia, " +
        "mapeando os mecanismos automaticamente. Sem cliques do usuario.")]
    public static Task<string> AddController(BridgeClient bridge,
        [Description("Pasta do sistema (controlador virtual)")] string path,
        CancellationToken ct = default)
        => bridge.CallAsync("add_controller", new { path }, ct);

    [McpServerTool(Name = "list_virtual_controllers"), Description(
        "Lista os controladores virtuais que o RobotStudio conhece em disco (nome e pasta), para usar em add_controller.")]
    public static Task<string> ListVirtualControllers(BridgeClient bridge, CancellationToken ct = default)
        => bridge.CallAsync("list_virtual_controllers", null, ct);

    [McpServerTool(Name = "create_workobject"), Description(
        "Cria um workobject na task ativa. uframe em relacao ao mundo; mm e graus.")]
    public static Task<string> CreateWorkobject(BridgeClient bridge,
        string name, double x, double y, double z, double rx = 0, double ry = 0, double rz = 0,
        CancellationToken ct = default)
        => bridge.CallAsync("create_workobject", new { name, x, y, z, rx, ry, rz }, ct);

    [McpServerTool(Name = "create_target"), Description(
        "Cria um target (robtarget) no workobject indicado. Posicao e orientacao no sistema do workobject; mm e graus.")]
    public static Task<string> CreateTarget(BridgeClient bridge,
        string name,
        [Description("Workobject (ex.: wobj0)")] string workobject,
        double x, double y, double z, double rx = 0, double ry = 0, double rz = 0,
        CancellationToken ct = default)
        => bridge.CallAsync("create_target", new { name, workobject, x, y, z, rx, ry, rz }, ct);

    [McpServerTool(Name = "create_path"), Description(
        "Cria um path (PROC) com movimentos para os targets na ordem dada. " +
        "Usa a tool ativa da task, ou a informada.")]
    public static Task<string> CreatePath(BridgeClient bridge,
        [Description("Nome do path/PROC")] string name,
        [Description("Nomes dos targets, em ordem")] string[] targets,
        [Description("Linear ou Joint")] string motion = "Linear",
        [Description("Workobject dos targets")] string workobject = "wobj0",
        [Description("Tooldata (vazio = tool ativa)")] string? tool = null,
        [Description("Modulo RAPID destino")] string module = "Module1",
        CancellationToken ct = default)
        => bridge.CallAsync("create_path", new { name, targets, motion, workobject, tool, module }, ct);

    [McpServerTool(Name = "auto_configure"), Description("Roda Auto Configuration no path para escolher a confdata.")]
    public static Task<string> AutoConfigure(BridgeClient bridge, string path, CancellationToken ct = default)
        => bridge.CallAsync("auto_configure", new { path }, ct);

    [McpServerTool(Name = "sync_to_rapid"), Description("Sincroniza a estacao para o RAPID do controlador virtual.")]
    public static Task<string> SyncToRapid(BridgeClient bridge, CancellationToken ct = default)
        => bridge.CallAsync("sync_to_rapid", null, ct);

    [McpServerTool(Name = "list_rapid_modules"), Description("Lista os modulos de uma task do controlador virtual (RW6).")]
    public static Task<string> ListRapidModules(BridgeClient bridge,
        string task = "T_ROB1", CancellationToken ct = default)
        => bridge.CallAsync("list_rapid_modules", new { task }, ct);

    [McpServerTool(Name = "read_rapid_module"), Description("Le o codigo de um modulo RAPID do controlador virtual.")]
    public static Task<string> ReadRapidModule(BridgeClient bridge,
        string module, string task = "T_ROB1", CancellationToken ct = default)
        => bridge.CallAsync("read_rapid_module", new { module, task }, ct);

    [McpServerTool(Name = "write_rapid_module"), Description(
        "Grava e carrega um modulo RAPID (texto completo, MODULE...ENDMODULE) no controlador virtual, " +
        "substituindo o modulo de mesmo nome. Sem acentos no codigo.")]
    public static Task<string> WriteRapidModule(BridgeClient bridge,
        [Description("Codigo completo do modulo")] string code,
        string task = "T_ROB1", CancellationToken ct = default)
        => bridge.CallAsync("write_rapid_module", new { code, task }, ct);

    [McpServerTool(Name = "simulation"), Description("Controla a simulacao: start ou stop.")]
    public static Task<string> Simulation(BridgeClient bridge,
        [Description("start | stop")] string action, CancellationToken ct = default)
        => bridge.CallAsync("simulation", new { action }, ct);

    [McpServerTool(Name = "save_station"), Description("Salva a estacao (exige licenca Premium). Caminho opcional para Save As.")]
    public static Task<string> SaveStation(BridgeClient bridge, string? path = null, CancellationToken ct = default)
        => bridge.CallAsync("save_station", new { path }, ct);

    [McpServerTool(Name = "list_ui_commands"), Description(
        "Diagnostico: lista id e texto dos comandos do ribbon do RobotStudio que contem o filtro.")]
    public static Task<string> ListUiCommands(BridgeClient bridge,
        [Description("Trecho do id ou do texto do botao")] string filter = "", CancellationToken ct = default)
        => bridge.CallAsync("list_ui_commands", new { filter }, ct);
}
