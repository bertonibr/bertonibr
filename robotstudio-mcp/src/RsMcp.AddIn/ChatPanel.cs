using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace RsMcp.AddIn
{
    /// <summary>
    /// Painel de chat dentro do RobotStudio. Cada mensagem roda "claude -p" (Claude Code)
    /// em segundo plano com o servidor MCP do RobotStudio; a conversa continua com --continue.
    /// </summary>
    public sealed class ChatPanel : UserControl
    {
        private const string SystemPrompt =
            "Voce opera o ABB RobotStudio 2025 pelas ferramentas mcp__robotstudio. Controlador IRC5, RobotWare 6. " +
            "Unidades: mm e graus. Antes de alterar, rode station_info. RAPID sem acentos. " +
            "Sem controlador na estacao: posicione os mecanismos e use create_controller (o usuario so clica Finish) " +
            "ou add_controller com um sistema existente (veja list_virtual_controllers). Responda em portugues, curto.";

        private readonly RichTextBox _history = new RichTextBox();
        private readonly TextBox _input = new TextBox();
        private readonly Button _send = new Button();
        private readonly Button _newChat = new Button();
        private readonly Button _stop = new Button();

        private readonly string _workDir;
        private Process _running;
        private bool _hasConversation;

        public ChatPanel()
        {
            _workDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "RsMcp");
            Directory.CreateDirectory(_workDir);
            WriteMcpConfig();

            _history.Dock = DockStyle.Fill;
            _history.ReadOnly = true;
            _history.BackColor = SystemColors.Window;
            _history.Font = new Font("Segoe UI", 9f);

            _input.Multiline = true;
            _input.Height = 70;
            _input.Dock = DockStyle.Fill;
            _input.KeyDown += (s, e) =>
            {
                if (e.KeyCode == Keys.Enter && !e.Shift) { e.SuppressKeyPress = true; Send(); }
            };

            _send.Text = "Enviar";
            _send.Click += (s, e) => Send();
            _stop.Text = "Parar";
            _stop.Enabled = false;
            _stop.Click += (s, e) => Stop();
            _newChat.Text = "Nova conversa";
            _newChat.Click += (s, e) => { _hasConversation = false; _history.Clear(); };

            var buttons = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 32, FlowDirection = FlowDirection.RightToLeft };
            buttons.Controls.AddRange(new Control[] { _send, _stop, _newChat });
            var bottom = new Panel { Dock = DockStyle.Bottom, Height = 104 };
            bottom.Controls.Add(_input);
            bottom.Controls.Add(buttons);

            Controls.Add(_history);
            Controls.Add(bottom);

            Append("Claude", "Pronto. Digite o que fazer na estacao (Enter envia, Shift+Enter quebra linha).");
        }

        // mcp.json aponta para o servidor publicado na pasta "server" ao lado do add-in.
        private void WriteMcpConfig()
        {
            var addinDir = Path.GetDirectoryName(typeof(ChatPanel).Assembly.Location);
            var serverExe = Path.Combine(addinDir, "server", "RsMcp.Server.exe");
            var json = "{\"mcpServers\":{\"robotstudio\":{\"command\":\"" + serverExe.Replace("\\", "\\\\") + "\",\"args\":[]}}}";
            File.WriteAllText(Path.Combine(_workDir, "mcp.json"), json, new UTF8Encoding(false));
        }

        private async void Send()
        {
            var prompt = _input.Text.Trim();
            if (prompt.Length == 0 || _running != null) return;
            _input.Clear();
            Append("Voce", prompt);
            SetBusy(true);
            try
            {
                var answer = await Task.Run(() => RunClaude(prompt, _hasConversation));
                _hasConversation = true;
                Append("Claude", answer);
            }
            catch (Exception ex)
            {
                Append("Erro", ex.Message);
            }
            finally
            {
                SetBusy(false);
            }
        }

        private string RunClaude(string prompt, bool continueConversation)
        {
            var args = "-p --output-format text --mcp-config mcp.json --allowedTools mcp__robotstudio" +
                       " --append-system-prompt \"" + SystemPrompt + "\"" +
                       (continueConversation ? " --continue" : "");

            // cmd /c resolve tanto claude.exe quanto claude.cmd (instalacao via npm).
            var info = new ProcessStartInfo("cmd.exe", "/c claude " + args)
            {
                WorkingDirectory = _workDir,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
            };

            using (var process = Process.Start(info))
            {
                _running = process;
                var stdout = process.StandardOutput.ReadToEndAsync();
                var stderr = process.StandardError.ReadToEndAsync();
                using (var stdin = new StreamWriter(process.StandardInput.BaseStream, new UTF8Encoding(false)))
                {
                    stdin.Write(prompt);
                }
                process.WaitForExit();
                _running = null;

                var output = stdout.Result.Trim();
                if (process.ExitCode != 0 && output.Length == 0)
                    throw new InvalidOperationException("claude saiu com codigo " + process.ExitCode + ": " + stderr.Result.Trim());
                return output;
            }
        }

        private void Stop()
        {
            // /T encerra a arvore (cmd.exe -> claude -> servidor MCP).
            var process = _running;
            if (process == null) return;
            try { Process.Start(new ProcessStartInfo("taskkill", "/T /F /PID " + process.Id) { CreateNoWindow = true, UseShellExecute = false }); }
            catch (Exception) { /* ja terminou */ }
        }

        private void SetBusy(bool busy)
        {
            _send.Enabled = !busy;
            _newChat.Enabled = !busy;
            _stop.Enabled = busy;
            if (busy) Append("...", "trabalhando");
        }

        private void Append(string who, string text)
        {
            _history.SelectionFont = new Font(_history.Font, FontStyle.Bold);
            _history.AppendText(who + ": ");
            _history.SelectionFont = _history.Font;
            _history.AppendText(text + Environment.NewLine + Environment.NewLine);
            _history.ScrollToCaret();
        }
    }
}
