using System;
using System.Collections;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using ABB.Robotics.Math;
using ABB.Robotics.RobotStudio;
using ABB.Robotics.RobotStudio.Controllers;
using ABB.Robotics.RobotStudio.Environment;
using ABB.Robotics.RobotStudio.Stations;
using ABB.Robotics.RobotStudio.Stations.Forms;

namespace RsMcp.AddIn
{
    /// <summary>
    /// Executa os comandos recebidos pelo pipe. Entrada em mm e graus; a API usa m e rad.
    /// Comandos de estacao rodam na thread da UI; comandos de RAPID (PC SDK) rodam na thread do pipe.
    /// </summary>
    public sealed class CommandDispatcher
    {
        private readonly UiInvoker _ui;
        private readonly RapidCommands _rapid = new RapidCommands();

        public CommandDispatcher(UiInvoker ui)
        {
            _ui = ui;
        }

        public object Dispatch(string method, Dictionary<string, object> p)
        {
            switch (method)
            {
                case "ping": return "RobotStudio OK";
                case "list_library": return LibraryFinder.Find(Str(p, "filter", ""), Bool(p, "refresh"));
                case "list_my_library": return LibraryFinder.FindUser(Str(p, "filter", ""));
                case "add_controller": return _ui.RunAsync(() => AddController(p));
                case "create_controller": return CreateController(p);
                case "list_rapid_modules": return _rapid.ListModules(ActiveSystemId(), Str(p, "task", "T_ROB1"));
                case "read_rapid_module": return _rapid.ReadModule(ActiveSystemId(), Str(p, "task", "T_ROB1"), Str(p, "module"));
                case "write_rapid_module": return _rapid.WriteModule(ActiveSystemId(), Str(p, "task", "T_ROB1"), Str(p, "code"));
            }
            return _ui.Run(() => DispatchStation(method, p));
        }

        // SystemId do controlador da estacao, para o PC SDK conectar no controlador certo.
        private Guid? ActiveSystemId()
        {
            return _ui.Run(() =>
            {
                var station = Project.ActiveProject as Station;
                if (station == null) return (Guid?)null;
                var controller = (station.ActiveTask != null ? station.ActiveTask.Parent as RsIrc5Controller : null)
                    ?? station.Irc5Controllers.Cast<RsIrc5Controller>().FirstOrDefault();
                return controller != null ? (Guid?)controller.SystemId : null;
            });
        }

        private object DispatchStation(string method, Dictionary<string, object> p)
        {
            switch (method)
            {
                case "station_info": return StationInfo();
                case "new_station": return NewStation();
                case "add_library": return Undoable("Add library", () => AddLibrary(p));
                case "import_geometry": return Undoable("Import geometry", () => ImportGeometry(p));
                case "set_position": return Undoable("Set position", () => SetPosition(p));
                case "attach": return Undoable("Attach", () => Attach(p));
                case "create_box": return Undoable("Create box", () => CreateBox(p));
                case "remove_component": return Undoable("Remove", () => RemoveComponent(p));
                case "capture_view": return CaptureView(p);
                case "create_workobject": return Undoable("Create workobject", () => CreateWorkobject(p));
                case "create_target": return Undoable("Create target", () => CreateTarget(p));
                case "create_path": return Undoable("Create path", () => CreatePath(p));
                case "auto_configure": return AutoConfigure(p);
                case "sync_to_rapid": return RunUiCommand(UiCommandIds.SyncToRapid);
                case "list_virtual_controllers": return ListVirtualControllers();
                case "list_ui_commands": return ListUiCommands(p);
                case "simulation": return Simulation(p);
                case "save_station": return SaveStation(p);
                default: throw new InvalidOperationException("Comando desconhecido: " + method);
            }
        }

        // ---------- estacao ----------

        private static Station ActiveStation()
        {
            var station = Project.ActiveProject as Station;
            if (station == null) throw new InvalidOperationException("Nenhuma estacao aberta.");
            return station;
        }

        private static RsTask ActiveTask(Station station)
        {
            var task = station.ActiveTask;
            if (task == null) throw new InvalidOperationException("Estacao sem controlador/task ativa. Crie o sistema (From Layout) antes.");
            return task;
        }

        private static object StationInfo()
        {
            var station = ActiveStation();
            var info = new Dictionary<string, object>
            {
                { "name", station.Name },
                { "components", station.GraphicComponents.Cast<GraphicComponent>()
                    .Select(c => new { name = c.Name, type = c.GetType().Name, pose = PoseOut(c.Transform.GlobalMatrix) }).ToList() },
                { "controllers", station.Irc5Controllers.Cast<RsIrc5Controller>().Select(c => c.Name).ToList() },
            };
            var task = station.ActiveTask;
            if (task != null)
            {
                info["task"] = task.Name;
                info["activeTool"] = task.ActiveTool != null ? task.ActiveTool.Name : null;
                info["activeWorkobject"] = task.ActiveWorkObject != null ? task.ActiveWorkObject.Name : null;
                info["tools"] = task.DataDeclarations.OfType<RsToolData>().Select(t => t.Name).ToList();
                info["workobjects"] = task.DataDeclarations.OfType<RsWorkObject>().Select(w => w.Name).ToList();
                info["targets"] = task.Targets.Cast<RsTarget>()
                    .Select(t => new { name = t.Name, workobject = t.WorkObject.Name }).ToList();
                info["paths"] = task.PathProcedures.Cast<RsPathProcedure>()
                    .Select(pp => new { name = pp.Name, instructions = pp.Instructions.Count }).ToList();
            }
            return info;
        }

        private static object NewStation()
        {
            var station = new Station();
            Project.ActiveProject = station;
            var graphics = new GraphicControl { RootObject = station };
            var window = new DocumentWindow { Control = graphics, Caption = station.Name };
            UIEnvironment.Windows.Add(window);
            return "Estacao criada: " + station.Name;
        }

        private static object AddLibrary(Dictionary<string, object> p)
        {
            var station = ActiveStation();
            var library = GraphicComponentLibrary.Load(Str(p, "path"), true);
            var component = (GraphicComponent)library.RootComponent.CopyInstance();
            station.GraphicComponents.Add(component);
            library.Close();
            return Place(component, p);
        }

        private static object ImportGeometry(Dictionary<string, object> p)
        {
            var station = ActiveStation();
            // TODO conferir na API do RS 2025: metodo de importacao de geometria (equivale a Home > Import Geometry).
            var part = Part.Load(Str(p, "path"));
            station.GraphicComponents.Add(part);
            return Place(part, p);
        }

        private static object Place(GraphicComponent component, Dictionary<string, object> p)
        {
            var name = Str(p, "name", null);
            if (!string.IsNullOrEmpty(name)) component.Name = name;
            component.Transform.GlobalMatrix = Pose(p);
            return new { name = component.Name, pose = PoseOut(component.Transform.GlobalMatrix) };
        }

        private static object SetPosition(Dictionary<string, object> p)
        {
            var component = FindComponent(ActiveStation(), Str(p, "name"));
            component.Transform.GlobalMatrix = Pose(p);
            return new { name = component.Name, pose = PoseOut(component.Transform.GlobalMatrix) };
        }

        private static object Attach(Dictionary<string, object> p)
        {
            var station = ActiveStation();
            var child = FindComponent(station, Str(p, "child"));
            var parent = FindComponent(station, Str(p, "parent")) as Mechanism;
            if (parent == null) throw new InvalidOperationException("O pai precisa ser um mecanismo (robo, posicionador, trilho).");
            parent.Attach(child, true, Matrix4.Identity);
            return child.Name + " anexado a " + parent.Name;
        }

        // Bloco para equipamento sem CAD. Origem no centro da face de baixo, para posicionar no chao.
        private static object CreateBox(Dictionary<string, object> p)
        {
            var station = ActiveStation();
            var name = Str(p, "name");
            if (station.GraphicComponents.Cast<GraphicComponent>().Any(c => string.Equals(c.Name, name, StringComparison.OrdinalIgnoreCase)))
                throw new InvalidOperationException("Ja existe um componente chamado " + name + ".");
            double sx = Num(p, "length") * Mm, sy = Num(p, "width") * Mm, sz = Num(p, "height") * Mm;
            if (sx <= 0 || sy <= 0 || sz <= 0) throw new ArgumentException("length, width e height devem ser maiores que zero (mm).");

            var part = new Part { Name = name };
            station.GraphicComponents.Add(part);
            var corner = Matrix4.Identity;
            corner.Translation = new Vector3(-sx / 2, -sy / 2, 0);
            var body = Body.CreateSolidBox(corner, new Vector3(sx, sy, sz));
            body.Name = name;
            part.Bodies.Add(body);
            var color = Str(p, "color", null);
            // TODO conferir na API do RS 2025: propriedade de cor da Part.
            if (!string.IsNullOrEmpty(color)) part.Color = ColorTranslator.FromHtml(color);
            part.Transform.GlobalMatrix = Pose(p);
            return new { name = part.Name, pose = PoseOut(part.Transform.GlobalMatrix) };
        }

        private static object RemoveComponent(Dictionary<string, object> p)
        {
            var station = ActiveStation();
            var component = FindComponent(station, Str(p, "name"));
            station.GraphicComponents.Remove(component);
            return "Removido: " + component.Name;
        }

        // Imagem da estacao para o Claude conferir o layout, salva em Documents\RsMcp\imagens.
        private static object CaptureView(Dictionary<string, object> p)
        {
            var station = ActiveStation();
            var graphics = GraphicControl.ActiveGraphicControl;
            if (graphics == null) throw new InvalidOperationException("Nenhuma vista 3D aberta.");
            var width = (int)Num(p, "width", 1600);
            var height = (int)Num(p, "height", 1000);
            var view = Str(p, "view", "top").ToLowerInvariant();

            Bitmap image;
            if (view == "current")
            {
                image = graphics.ScreenShot(width, height);
            }
            else
            {
                // TODO conferir: sentido do lookDirection e efeito do zoom (sobrecarga do SDK 2022.2+).
                var direction = view == "iso" ? new Vector3(-0.577, -0.577, -0.577) : new Vector3(0, 0, -1);
                image = graphics.ScreenShot(station, width, height, default(ScreenshotOptions), direction, 1.0f);
            }

            Directory.CreateDirectory(Workspace.ImagesDir);
            var file = Path.Combine(Workspace.ImagesDir, "vista_" + view + "_" + DateTime.Now.ToString("yyyyMMdd_HHmmss") + ".png");
            using (image) image.Save(file, ImageFormat.Png);
            return new { file, relative = Workspace.RelativeImagePath(file) };
        }

        private static GraphicComponent FindComponent(Station station, string name)
        {
            var found = station.GraphicComponents.Cast<GraphicComponent>()
                .FirstOrDefault(c => string.Equals(c.Name, name, StringComparison.OrdinalIgnoreCase));
            if (found == null) throw new InvalidOperationException("Componente nao encontrado: " + name);
            return found;
        }

        // ---------- controlador virtual ----------

        // Liga um sistema RW6 ja existente (pasta do controlador virtual) a estacao e inicia.
        private static async Task<object> AddController(Dictionary<string, object> p)
        {
            var station = ActiveStation();
            var path = Str(p, "path");
            if (!Directory.Exists(path)) throw new DirectoryNotFoundException("Pasta do sistema nao encontrada: " + path);
            var controller = new RsIrc5Controller(path);
            station.Irc5Controllers.Add(controller);
            // null = mapeamento automatico dos mecanismos; se nao conseguir, o RobotStudio abre um dialogo.
            await controller.StartAsync(VirtualControllerRestartMode.WarmStart, null, false, true);
            return "Controlador iniciado: " + controller.Name;
        }

        // O SDK nao tem API publica para "From Layout": abre o assistente e espera o usuario concluir.
        private object CreateController(Dictionary<string, object> p)
        {
            var minutes = Num(p, "timeoutMinutes");
            var deadline = DateTime.UtcNow + TimeSpan.FromMinutes(minutes > 0 ? minutes : 15);
            var before = _ui.Run(() => ActiveStation().Irc5Controllers.Count);
            var command = _ui.Run(() => FindRibbonControl(UiCommandIds.FromLayoutCaption));
            _ui.Post(() => Execute(command));

            while (DateTime.UtcNow < deadline)
            {
                Thread.Sleep(2000);
                var ready = _ui.Run(() =>
                {
                    var station = ActiveStation();
                    return station.Irc5Controllers.Count > before && station.ActiveTask != null;
                });
                if (ready) return _ui.Run(() => StationInfo());
            }
            return "Tempo esgotado. Conclua o assistente From Layout e rode station_info.";
        }

        // Sistemas que o RobotStudio conhece em disco. Propriedades lidas por reflexao,
        // porque os nomes nao estao confirmados para o RS 2025.
        private static object ListVirtualControllers()
        {
            return ControllerManager.VirtualControllerReferences.Cast<object>().Select(Describe).ToList();
        }

        private static Dictionary<string, object> Describe(object item)
        {
            var info = new Dictionary<string, object>();
            foreach (var prop in item.GetType().GetProperties())
            {
                var type = prop.PropertyType;
                if (prop.GetIndexParameters().Length > 0) continue;
                if (type != typeof(string) && type != typeof(Guid) && type != typeof(bool) && !type.IsEnum) continue;
                try { info[prop.Name] = Convert.ToString(prop.GetValue(item, null)); }
                catch (Exception) { /* propriedade sem valor neste estado */ }
            }
            return info;
        }

        // ---------- programacao ----------

        private static RsWorkObject FindWorkobject(RsTask task, string name)
        {
            var wobj = task.DataDeclarations.OfType<RsWorkObject>()
                .FirstOrDefault(w => string.Equals(w.Name, name, StringComparison.OrdinalIgnoreCase));
            if (wobj == null) throw new InvalidOperationException("Workobject nao encontrado: " + name);
            return wobj;
        }

        private static object CreateWorkobject(Dictionary<string, object> p)
        {
            var task = ActiveTask(ActiveStation());
            var wobj = new RsWorkObject { Name = task.GetValidRapidName(Str(p, "name"), "_", 1) };
            wobj.UserFrame.Matrix = Pose(p);
            task.DataDeclarations.Add(wobj);
            return "Workobject criado: " + wobj.Name;
        }

        private static object CreateTarget(Dictionary<string, object> p)
        {
            var task = ActiveTask(ActiveStation());
            var wobj = FindWorkobject(task, Str(p, "workobject", "wobj0"));
            var robTarget = new RsRobTarget { Name = task.GetValidRapidName(Str(p, "name"), "_", 10) };
            robTarget.Frame.Matrix = Pose(p);
            task.DataDeclarations.Add(robTarget);
            var target = new RsTarget(wobj, robTarget) { Name = robTarget.Name };
            task.Targets.Add(target);
            return "Target criado: " + target.Name + " em " + wobj.Name;
        }

        private static object CreatePath(Dictionary<string, object> p)
        {
            var task = ActiveTask(ActiveStation());
            var toolName = Str(p, "tool", null);
            if (string.IsNullOrEmpty(toolName))
            {
                if (task.ActiveTool == null) throw new InvalidOperationException("Sem tool ativa. Informe 'tool'.");
                toolName = task.ActiveTool.Name;
            }
            var wobjName = FindWorkobject(task, Str(p, "workobject", "wobj0")).Name;
            var motion = string.Equals(Str(p, "motion", "Linear"), "Joint", StringComparison.OrdinalIgnoreCase)
                ? MotionType.Joint : MotionType.Linear;

            var path = new RsPathProcedure(task.GetValidRapidName(Str(p, "name"), "_", 10))
            {
                ModuleName = Str(p, "module", "Module1"),
                ShowName = true,
                Synchronize = true,
            };
            task.PathProcedures.Add(path);

            foreach (var targetName in StrList(p, "targets"))
            {
                var target = task.Targets.Cast<RsTarget>()
                    .FirstOrDefault(t => string.Equals(t.Name, targetName, StringComparison.OrdinalIgnoreCase));
                if (target == null) throw new InvalidOperationException("Target nao encontrado: " + targetName);
                path.Instructions.Add(new RsMoveInstruction(task, "Move", "Default", motion,
                    wobjName, target.RobTarget.Name, toolName));
            }
            return "Path criado: " + path.Name + " (" + path.Instructions.Count + " instrucoes)";
        }

        private static object AutoConfigure(Dictionary<string, object> p)
        {
            var task = ActiveTask(ActiveStation());
            var path = task.PathProcedures.Cast<RsPathProcedure>()
                .FirstOrDefault(pp => string.Equals(pp.Name, Str(p, "path"), StringComparison.OrdinalIgnoreCase));
            if (path == null) throw new InvalidOperationException("Path nao encontrado: " + Str(p, "path"));
            // Auto Configuration age sobre a selecao, como na UI.
            Selection.SelectedObjects.Clear();
            Selection.SelectedObjects.Add(path);
            return RunUiCommand(UiCommandIds.AutoConfiguration);
        }

        // ---------- simulacao e arquivo ----------

        private static object Simulation(Dictionary<string, object> p)
        {
            switch (Str(p, "action").ToLowerInvariant())
            {
                case "start": Simulator.Start(); return "Simulacao iniciada";
                case "stop": Simulator.Stop(); return "Simulacao parada";
                default: throw new InvalidOperationException("action deve ser start ou stop");
            }
        }

        private static object SaveStation(Dictionary<string, object> p)
        {
            var station = ActiveStation();
            var path = Str(p, "path", null);
            // TODO conferir na API do RS 2025: Save/SaveAs do projeto (exige Premium).
            if (string.IsNullOrEmpty(path)) station.Save();
            else station.SaveAs(path);
            return "Estacao salva";
        }

        // ---------- comandos da interface ----------

        private static object RunUiCommand(string id)
        {
            var control = CommandBarControl.FromID(id);
            if (control == null) throw new InvalidOperationException("Comando da UI nao encontrado: " + id + ". Use list_ui_commands.");
            Execute(control);
            return "Comando executado: " + id;
        }

        private static void Execute(CommandBarControl control)
        {
            var button = control as CommandBarButton;
            if (button == null) throw new InvalidOperationException("Comando nao executavel: " + control.Id);
            button.Execute();
        }

        private static CommandBarControl FindRibbonControl(string text)
        {
            var control = RibbonControls().FirstOrDefault(c => Matches(c, text));
            if (control == null) throw new InvalidOperationException("Comando nao encontrado na interface: " + text + ". Use list_ui_commands.");
            return control;
        }

        private static object ListUiCommands(Dictionary<string, object> p)
        {
            var filter = Str(p, "filter", "");
            return RibbonControls().Where(c => filter.Length == 0 || Matches(c, filter))
                .Select(c => new { id = c.Id, caption = c.Caption }).Take(200).ToList();
        }

        private static IEnumerable<CommandBarControl> RibbonControls()
        {
            foreach (RibbonTab tab in UIEnvironment.RibbonTabs)
                foreach (RibbonGroup group in tab.Groups)
                    foreach (var control in Flatten(group.Controls))
                        yield return control;
        }

        private static IEnumerable<CommandBarControl> Flatten(IEnumerable controls)
        {
            foreach (CommandBarControl control in controls)
            {
                yield return control;
                var popup = control as CommandBarPopup;
                if (popup == null) continue;
                foreach (var child in Flatten(popup.Controls))
                    yield return child;
            }
        }

        private static bool Matches(CommandBarControl control, string text)
        {
            return (control.Id ?? "").IndexOf(text, StringComparison.OrdinalIgnoreCase) >= 0
                || (control.Caption ?? "").IndexOf(text, StringComparison.OrdinalIgnoreCase) >= 0;
        }

        // ---------- utilitarios ----------

        private static object Undoable(string name, Func<object> action)
        {
            Project.UndoContext.BeginUndoStep("Claude: " + name);
            try { return action(); }
            finally { Project.UndoContext.EndUndoStep(); }
        }

        private const double Mm = 0.001;
        private const double Deg = Math.PI / 180.0;

        // Pose em mm/graus (Euler ZYX, como no Set Position) -> Matrix4 em m/rad.
        private static Matrix4 Pose(Dictionary<string, object> p)
        {
            var m = Matrix4.Identity;
            m.EulerZYX = new Vector3(Num(p, "rx") * Deg, Num(p, "ry") * Deg, Num(p, "rz") * Deg);
            m.Translation = new Vector3(Num(p, "x") * Mm, Num(p, "y") * Mm, Num(p, "z") * Mm);
            return m;
        }

        private static object PoseOut(Matrix4 m)
        {
            var t = m.Translation;
            var r = m.EulerZYX;
            return new
            {
                x = Math.Round(t.x / Mm, 3), y = Math.Round(t.y / Mm, 3), z = Math.Round(t.z / Mm, 3),
                rx = Math.Round(r.x / Deg, 4), ry = Math.Round(r.y / Deg, 4), rz = Math.Round(r.z / Deg, 4),
            };
        }

        private static double Num(Dictionary<string, object> p, string key, double fallback = 0.0)
        {
            object v;
            return p.TryGetValue(key, out v) && v != null ? Convert.ToDouble(v) : fallback;
        }

        private static bool Bool(Dictionary<string, object> p, string key)
        {
            object v;
            return p.TryGetValue(key, out v) && v != null && Convert.ToBoolean(v);
        }

        private static string Str(Dictionary<string, object> p, string key)
        {
            var v = Str(p, key, null);
            if (string.IsNullOrEmpty(v)) throw new ArgumentException("Parametro obrigatorio: " + key);
            return v;
        }

        private static string Str(Dictionary<string, object> p, string key, string fallback)
        {
            object v;
            return p.TryGetValue(key, out v) && v != null ? Convert.ToString(v) : fallback;
        }

        private static IEnumerable<string> StrList(Dictionary<string, object> p, string key)
        {
            object v;
            if (!p.TryGetValue(key, out v) || !(v is IEnumerable) || v is string)
                throw new ArgumentException("Parametro obrigatorio (lista): " + key);
            return ((IEnumerable)v).Cast<object>().Select(Convert.ToString).ToList();
        }
    }

    /// <summary>
    /// Comandos da UI usados quando a API nao tem metodo direto.
    /// TODO conferir no RS 2025 com list_ui_commands (os IDs podem mudar entre versoes).
    /// </summary>
    public static class UiCommandIds
    {
        public const string SyncToRapid = "SyncToRAPID";
        public const string AutoConfiguration = "AutoConfiguration";
        // Procurado pelo texto do botao (UI em ingles): Home > Virtual Controller > From Layout...
        public const string FromLayoutCaption = "From Layout";
    }
}
