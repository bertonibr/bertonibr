using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace RsMcp.AddIn
{
    /// <summary>
    /// Painel de chat dentro do RobotStudio. Cada mensagem roda "claude -p" (Claude Code)
    /// em segundo plano na pasta de trabalho (Workspace), com o servidor MCP do RobotStudio;
    /// a conversa continua com --continue. Imagens anexadas vao para a subpasta "imagens".
    /// </summary>
    public sealed class ChatPanel : UserControl
    {
        private static readonly string[] AttachExtensions = { ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".pdf" };

        private readonly RichTextBox _history = new RichTextBox();
        private readonly TextBox _input = new TextBox();
        private readonly Label _attachLabel = new Label();
        private readonly Button _send = new Button();
        private readonly Button _attach = new Button();
        private readonly Button _newChat = new Button();
        private readonly Button _stop = new Button();
        private readonly List<string> _attachments = new List<string>();

        private Process _running;
        private bool _busy;
        private bool _hasConversation;

        public ChatPanel()
        {
            _history.Dock = DockStyle.Fill;
            _history.ReadOnly = true;
            _history.BackColor = SystemColors.Window;
            _history.Font = new Font("Segoe UI", 9f);

            _input.Multiline = true;
            _input.Dock = DockStyle.Fill;
            _input.AllowDrop = true;
            _input.KeyDown += (s, e) =>
            {
                if (e.KeyCode == Keys.Enter && !e.Shift) { e.SuppressKeyPress = true; Send(); }
                else if (e.Control && e.KeyCode == Keys.V && Clipboard.ContainsImage()) { e.SuppressKeyPress = true; AttachClipboardImage(); }
            };
            _input.DragEnter += (s, e) =>
                e.Effect = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None;
            _input.DragDrop += (s, e) =>
            {
                foreach (var file in (string[])e.Data.GetData(DataFormats.FileDrop)) Attach(file);
            };

            _attachLabel.Dock = DockStyle.Top;
            _attachLabel.Height = 18;
            _attachLabel.ForeColor = SystemColors.GrayText;
            _attachLabel.Cursor = Cursors.Hand;
            _attachLabel.Click += (s, e) => ClearAttachments();

            _send.Text = "Enviar";
            _send.Click += (s, e) => Send();
            _stop.Text = "Parar";
            _stop.Enabled = false;
            _stop.Click += (s, e) => Stop();
            _attach.Text = "Imagem...";
            _attach.Click += (s, e) => PickFiles();
            _newChat.Text = "Nova conversa";
            _newChat.Click += (s, e) => { _hasConversation = false; _history.Clear(); ClearAttachments(); };

            var buttons = new FlowLayoutPanel { Dock = DockStyle.Bottom, Height = 32, FlowDirection = FlowDirection.RightToLeft };
            buttons.Controls.AddRange(new Control[] { _send, _stop, _attach, _newChat });
            var bottom = new Panel { Dock = DockStyle.Bottom, Height = 122 };
            bottom.Controls.Add(_input);
            bottom.Controls.Add(buttons);
            bottom.Controls.Add(_attachLabel);

            Controls.Add(_history);
            Controls.Add(bottom);

            Append("Claude", "Pronto. Digite o que fazer na estacao. Para montar um layout, anexe a imagem " +
                             "(botao Imagem..., arrastar o arquivo ou Ctrl+V de um print).");
        }

        // ---------- anexos ----------

        private void PickFiles()
        {
            using (var dialog = new OpenFileDialog
            {
                Multiselect = true,
                Filter = "Imagens e PDF|*.png;*.jpg;*.jpeg;*.gif;*.webp;*.bmp;*.pdf",
            })
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                foreach (var file in dialog.FileNames) Attach(file);
            }
        }

        private void Attach(string source)
        {
            var ext = Path.GetExtension(source).ToLowerInvariant();
            if (!AttachExtensions.Contains(ext))
            {
                Append("Erro", "Formato nao suportado: " + Path.GetFileName(source) + ". Use PNG, JPG ou PDF.");
                return;
            }
            var name = DateTime.Now.ToString("yyyyMMdd_HHmmss_") +
                       Regex.Replace(Path.GetFileNameWithoutExtension(source), "[^A-Za-z0-9_-]", "_");
            SafeAttach(() =>
            {
                if (ext != ".bmp")
                {
                    var copy = Path.Combine(Workspace.ImagesDir, name + ext);
                    File.Copy(source, copy, true);
                    return copy;
                }
                // O Claude nao le BMP: converte para PNG.
                var png = Path.Combine(Workspace.ImagesDir, name + ".png");
                using (var image = Image.FromFile(source)) image.Save(png, ImageFormat.Png);
                return png;
            });
        }

        private void AttachClipboardImage()
        {
            SafeAttach(() =>
            {
                var png = Path.Combine(Workspace.ImagesDir, DateTime.Now.ToString("yyyyMMdd_HHmmss") + "_colado.png");
                using (var image = Clipboard.GetImage()) image.Save(png, ImageFormat.Png);
                return png;
            });
        }

        // Erro ao anexar aparece no chat em vez de derrubar o evento da interface.
        private void SafeAttach(Func<string> save)
        {
            try
            {
                Directory.CreateDirectory(Workspace.ImagesDir);
                AddAttachment(save());
            }
            catch (Exception ex)
            {
                Append("Erro", "Nao foi possivel anexar: " + ex.Message);
            }
        }

        private void AddAttachment(string file)
        {
            _attachments.Add(Workspace.RelativeImagePath(file));
            UpdateAttachLabel();
        }

        private void ClearAttachments()
        {
            _attachments.Clear();
            UpdateAttachLabel();
        }

        private void UpdateAttachLabel()
        {
            _attachLabel.Text = _attachments.Count == 0
                ? ""
                : "Anexos (clique para remover): " + string.Join(", ", _attachments.Select(Path.GetFileName));
        }

        // ---------- conversa ----------

        private async void Send()
        {
            var text = _input.Text.Trim();
            if (_busy || (text.Length == 0 && _attachments.Count == 0)) return;
            if (text.Length == 0) text = "Analise a imagem anexada.";

            var prompt = text;
            var shown = text;
            if (_attachments.Count > 0)
            {
                prompt += Environment.NewLine + Environment.NewLine +
                          "Imagens anexadas (abra com a ferramenta Read): " + string.Join(", ", _attachments);
                shown += Environment.NewLine + "[anexos: " + string.Join(", ", _attachments.Select(Path.GetFileName)) + "]";
            }
            _input.Clear();
            ClearAttachments();
            Append("Voce", shown);
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
            // As instrucoes vem do CLAUDE.md da pasta de trabalho (ver Workspace).
            var args = "-p --output-format text --mcp-config mcp.json" +
                       " --allowedTools mcp__robotstudio \"Read(./imagens/**)\"" +
                       (continueConversation ? " --continue" : "");

            // cmd /c resolve tanto claude.exe quanto claude.cmd (instalacao via npm).
            var info = new ProcessStartInfo("cmd.exe", "/c claude " + args)
            {
                WorkingDirectory = Workspace.Dir,
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
            _busy = busy;
            _send.Enabled = !busy;
            _attach.Enabled = !busy;
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
