using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using ABB.Robotics.Controllers;
using ABB.Robotics.Controllers.Discovery;
using ABB.Robotics.Controllers.RapidDomain;

namespace RsMcp.AddIn
{
    /// <summary>
    /// RAPID no controlador virtual (IRC5/RW6) pelo PC SDK.
    /// Conecta no controlador da estacao (pelo SystemId); sem ele, no primeiro virtual encontrado.
    /// </summary>
    public sealed class RapidCommands
    {
        private static readonly string TempDir = Path.Combine(Path.GetTempPath(), "RsMcp");

        public object ListModules(Guid? systemId, string taskName)
        {
            using (var controller = Connect(systemId))
            {
                return controller.Rapid.GetTask(taskName).GetModules().Select(m => m.Name).ToList();
            }
        }

        public object ReadModule(Guid? systemId, string taskName, string moduleName)
        {
            using (var controller = Connect(systemId))
            {
                var module = controller.Rapid.GetTask(taskName).GetModule(moduleName);
                if (module == null) throw new InvalidOperationException("Modulo nao encontrado: " + moduleName);

                // Salva no sistema de arquivos do controlador e traz para o PC.
                var fs = controller.FileSystem;
                module.SaveToFile(fs.RemoteDirectory);
                var remoteFile = moduleName + ".mod";
                Directory.CreateDirectory(TempDir);
                var localFile = Path.Combine(TempDir, remoteFile);
                fs.GetFile(remoteFile, localFile, true);
                return File.ReadAllText(localFile);
            }
        }

        public object WriteModule(Guid? systemId, string taskName, string code)
        {
            var match = Regex.Match(code ?? "", @"^\s*MODULE\s+(\w+)", RegexOptions.Multiline | RegexOptions.IgnoreCase);
            if (!match.Success) throw new ArgumentException("Codigo sem 'MODULE <nome>'.");
            if (code.Any(c => c > 127)) throw new ArgumentException("Codigo com caractere nao-ASCII (acento). Remova antes de carregar.");
            var moduleName = match.Groups[1].Value;

            Directory.CreateDirectory(TempDir);
            var localFile = Path.Combine(TempDir, moduleName + ".mod");
            File.WriteAllText(localFile, code, new UTF8Encoding(false));

            using (var controller = Connect(systemId))
            {
                var fs = controller.FileSystem;
                fs.PutFile(localFile, moduleName + ".mod", true);
                var remotePath = fs.RemoteDirectory + "/" + moduleName + ".mod";
                using (Mastership.Request(controller.Rapid))
                {
                    controller.Rapid.GetTask(taskName).LoadModuleFromFile(remotePath, RapidLoadMode.Replace);
                }
            }
            return "Modulo carregado: " + moduleName + " em " + taskName + ". Rode Check Program e Synchronize to Station.";
        }

        private static Controller Connect(Guid? systemId)
        {
            var scanner = new NetworkScanner();
            scanner.Scan();
            var virtuals = scanner.GetControllers(NetworkScannerSearchCriterias.Virtual);
            var info = virtuals.FirstOrDefault(c => systemId.HasValue && c.SystemId == systemId.Value)
                ?? virtuals.FirstOrDefault();
            if (info == null) throw new InvalidOperationException("Nenhum controlador virtual em execucao.");
            var controller = Controller.Connect(info, ConnectionType.Standalone);
            controller.Logon(UserInfo.DefaultUser);
            return controller;
        }
    }
}
