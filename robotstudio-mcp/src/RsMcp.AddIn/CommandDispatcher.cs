using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using ABB.Robotics.Math;
using ABB.Robotics.RobotStudio;
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
                case "list_rapid_modules": return _rapid.ListModules(Str(p, "task", "T_ROB1"));
                case "read_rapid_module": return _rapid.ReadModule(Str(p, "task", "T_ROB1"), Str(p, "module"));
                case "write_rapid_module": return _rapid.WriteModule(Str(p, "task", "T_ROB1"), Str(p, "code"));
            }
            return _ui.Run(() => DispatchStation(method, p));
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
                case "create_workobject": return Undoable("Create workobject", () => CreateWorkobject(p));
                case "create_target": return Undoable("Create target", () => CreateTarget(p));
                case "create_path": return Undoable("Create path", () => CreatePath(p));
                case "auto_configure": return AutoConfigure(p);
                case "sync_to_rapid": return RunUiCommand(UiCommandIds.SyncToRapid);
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

        private static GraphicComponent FindComponent(Station station, string name)
        {
            var found = station.GraphicComponents.Cast<GraphicComponent>()
                .FirstOrDefault(c => string.Equals(c.Name, name, StringComparison.OrdinalIgnoreCase));
            if (found == null) throw new InvalidOperationException("Componente nao encontrado: " + name);
            return found;
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

        private static object RunUiCommand(string id)
        {
            var control = CommandBarControl.FromID(id) as CommandBarButton;
            if (control == null) throw new InvalidOperationException("Comando da UI nao encontrado: " + id + ". Veja UiCommandIds.");
            control.Execute();
            return "Comando executado: " + id;
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

        private static double Num(Dictionary<string, object> p, string key)
        {
            object v;
            return p.TryGetValue(key, out v) && v != null ? Convert.ToDouble(v) : 0.0;
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
    /// IDs de comandos da UI usados quando a API nao tem metodo direto.
    /// TODO conferir no RS 2025 (os IDs podem mudar entre versoes).
    /// </summary>
    public static class UiCommandIds
    {
        public const string SyncToRapid = "SyncToRAPID";
        public const string AutoConfiguration = "AutoConfiguration";
    }
}
