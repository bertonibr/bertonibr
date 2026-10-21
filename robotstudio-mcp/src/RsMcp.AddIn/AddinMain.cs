using System;
using System.Threading.Tasks;
using System.Windows.Forms;
using ABB.Robotics.RobotStudio;
using ABB.Robotics.RobotStudio.Environment;

namespace RsMcp.AddIn
{
    /// <summary>
    /// Ponto de entrada do add-in. Cria a aba "Claude" no ribbon, o painel de chat
    /// e o named pipe que o servidor MCP usa para chamar a API do RobotStudio.
    /// </summary>
    public class Addin
    {
        private static PipeServer _pipe;
        private static ToolWindow _chatWindow;

        // Chamado pelo RobotStudio na thread da UI.
        public static void AddinMain()
        {
            try { Workspace.Prepare(); }
            catch (Exception ex) { Log("Falha ao preparar a pasta de trabalho: " + ex.Message); }

            var ui = new UiInvoker();
            var commands = new CommandDispatcher(ui);
            _pipe = new PipeServer(commands.Dispatch);
            _pipe.Start();

            var tab = new RibbonTab("RsMcpTab", "Claude");
            UIEnvironment.RibbonTabs.Add(tab);
            var group = new RibbonGroup("RsMcpGroup", "Claude");
            var button = new CommandBarButton("RsMcpOpenChat", "Abrir chat");
            button.DefaultEnabled = true;
            button.ExecuteCommand += (s, e) => ShowChat();
            group.Controls.Add(button);
            tab.Groups.Add(group);

            Log("RsMcp carregado. Pipe: " + PipeServer.PipeName);
        }

        private static void ShowChat()
        {
            if (_chatWindow == null)
            {
                _chatWindow = new ToolWindow("RsMcpChat", new ChatPanel(), "Claude");
                UIEnvironment.Windows.AddDocked(_chatWindow, DockStyle.Right);
            }
            _chatWindow.Visible = true;
        }

        public static void Log(string text)
        {
            try { Logger.AddMessage(new LogMessage("[RsMcp] " + text)); }
            catch (Exception) { /* log nunca derruba o add-in */ }
        }
    }

    /// <summary>
    /// A API do RobotStudio so pode ser usada na thread da UI. O pipe roda em outra thread
    /// e usa esta classe para executar cada comando na thread certa.
    /// </summary>
    public sealed class UiInvoker
    {
        private readonly Control _control;

        public UiInvoker()
        {
            _control = new Control();
            _control.CreateControl(); // cria o handle na thread atual (UI)
        }

        public T Run<T>(Func<T> func)
        {
            if (!_control.InvokeRequired) return func();
            T result = default(T);
            Exception error = null;
            _control.Invoke(new Action(() =>
            {
                try { result = func(); }
                catch (Exception ex) { error = ex; }
            }));
            if (error != null) throw error;
            return result;
        }

        // Metodos async da API (ex.: StartAsync): comecam na thread da UI e a espera fica na thread
        // que chamou. Nunca chame da thread da UI: esperar nela trava o RobotStudio.
        public T RunAsync<T>(Func<Task<T>> func)
        {
            return Run(func).GetAwaiter().GetResult();
        }

        // Executa na thread da UI sem esperar (ex.: abrir um assistente modal).
        public void Post(Action action)
        {
            _control.BeginInvoke(new Action(() =>
            {
                try { action(); }
                catch (Exception ex) { Addin.Log(ex.Message); }
            }));
        }
    }
}
