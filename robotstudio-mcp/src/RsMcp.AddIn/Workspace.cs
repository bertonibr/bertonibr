using System;
using System.IO;
using System.Text;

namespace RsMcp.AddIn
{
    /// <summary>
    /// Pasta de trabalho do Claude (Documents\RsMcp): mcp.json, instrucoes, CLAUDE.md e imagens.
    /// O painel roda o Claude Code nesta pasta, entao o CLAUDE.md daqui vale para toda conversa.
    /// </summary>
    public static class Workspace
    {
        public static readonly string Dir =
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "RsMcp");
        public static readonly string ImagesDir = Path.Combine(Dir, "imagens");

        private const string InstructionsFile = "rsmcp-instrucoes.md";

        private const string DefaultClaudeMd =
            "# Assistente do RobotStudio\n\n" +
            "@" + InstructionsFile + "\n\n" +
            "## Padroes da empresa (edite a vontade)\n\n" +
            "<!-- Ex.: altura padrao das mesas, robos e posicionadores mais usados, distancia minima da cerca. -->\n";

        public static void Prepare()
        {
            Directory.CreateDirectory(ImagesDir);
            var utf8 = new UTF8Encoding(false);

            // mcp.json aponta para o servidor publicado na pasta "server" ao lado do add-in.
            var addinDir = Path.GetDirectoryName(typeof(Workspace).Assembly.Location);
            var serverExe = Path.Combine(addinDir, "server", "RsMcp.Server.exe");
            var json = "{\"mcpServers\":{\"robotstudio\":{\"command\":\"" + serverExe.Replace("\\", "\\\\") + "\",\"args\":[]}}}";
            File.WriteAllText(Path.Combine(Dir, "mcp.json"), json, utf8);

            // Instrucoes do add-in: regravadas a cada versao. O CLAUDE.md e do usuario: so e criado se faltar.
            using (var stream = typeof(Workspace).Assembly.GetManifestResourceStream("RsMcp.Instrucoes.md"))
            using (var reader = new StreamReader(stream, Encoding.UTF8))
            {
                File.WriteAllText(Path.Combine(Dir, InstructionsFile), reader.ReadToEnd(), utf8);
            }
            var claudeMd = Path.Combine(Dir, "CLAUDE.md");
            if (!File.Exists(claudeMd)) File.WriteAllText(claudeMd, DefaultClaudeMd, utf8);
        }

        // Caminho relativo a pasta de trabalho, como o Claude deve abrir com Read.
        public static string RelativeImagePath(string file)
        {
            return "imagens/" + Path.GetFileName(file);
        }
    }
}
