using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace RsMcp.AddIn
{
    /// <summary>
    /// Named pipe local. Protocolo: uma linha JSON {"method","params"} por conexao,
    /// resposta numa linha JSON {"ok":true,"result":...} ou {"ok":false,"error":"..."}.
    /// So aceita conexoes da propria maquina (named pipe local).
    /// </summary>
    public sealed class PipeServer
    {
        public const string PipeName = "RsMcpBridge";

        private readonly Func<string, Dictionary<string, object>, object> _handler;
        private readonly JavaScriptSerializer _json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };
        private Thread _thread;

        public PipeServer(Func<string, Dictionary<string, object>, object> handler)
        {
            _handler = handler;
        }

        public void Start()
        {
            _thread = new Thread(Loop) { IsBackground = true, Name = "RsMcpPipe" };
            _thread.Start();
        }

        private void Loop()
        {
            while (true)
            {
                try
                {
                    using (var pipe = new NamedPipeServerStream(PipeName, PipeDirection.InOut, 1,
                        PipeTransmissionMode.Byte, PipeOptions.None, 0, 0, CreateSecurity()))
                    {
                        pipe.WaitForConnection();
                        var reader = new StreamReader(pipe, Encoding.UTF8);
                        var writer = new StreamWriter(pipe, new UTF8Encoding(false)) { AutoFlush = true };
                        var line = reader.ReadLine();
                        if (line == null) continue;
                        writer.WriteLine(Handle(line));
                        pipe.WaitForPipeDrain();
                    }
                }
                catch (Exception ex)
                {
                    Addin.Log("Erro no pipe: " + ex.Message);
                    Thread.Sleep(500);
                }
            }
        }

        // Acesso so para o usuario atual e bloqueado pela rede.
        private static PipeSecurity CreateSecurity()
        {
            var security = new PipeSecurity();
            security.AddAccessRule(new PipeAccessRule(WindowsIdentity.GetCurrent().User,
                PipeAccessRights.FullControl, AccessControlType.Allow));
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.NetworkSid, null),
                PipeAccessRights.FullControl, AccessControlType.Deny));
            return security;
        }

        private string Handle(string line)
        {
            string method = "?";
            try
            {
                var request = _json.Deserialize<Dictionary<string, object>>(line);
                method = Convert.ToString(request["method"]);
                object raw;
                var parameters = request.TryGetValue("params", out raw) && raw is Dictionary<string, object>
                    ? (Dictionary<string, object>)raw
                    : new Dictionary<string, object>();

                var result = _handler(method, parameters);
                return _json.Serialize(new Dictionary<string, object> { { "ok", true }, { "result", result } });
            }
            catch (Exception ex)
            {
                var inner = ex.InnerException ?? ex;
                Addin.Log(method + ": " + inner.Message);
                return _json.Serialize(new Dictionary<string, object> { { "ok", false }, { "error", inner.Message } });
            }
        }
    }
}
