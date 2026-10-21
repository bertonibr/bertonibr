using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace RsMcp.AddIn
{
    /// <summary>
    /// Pasta de trabalho do Claude (Documents\RsMcp): mcp.json, instrucoes, CLAUDE.md, imagens, anexos e biblioteca.
    /// O painel roda o Claude Code nesta pasta, entao o CLAUDE.md daqui vale para toda conversa.
    /// </summary>
    public static class Workspace
    {
        // Caminho absoluto: os caminhos da biblioteca vao para o RobotStudio, que roda em outra pasta.
        public static readonly string Dir =
            Path.GetFullPath(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "RsMcp"));
        public static readonly string ImagesDir = Path.Combine(Dir, "imagens");
        // Arquivos de componente (CAD, .rslib) anexados no chat, antes de irem para a biblioteca.
        public static readonly string AttachmentsDir = Path.Combine(Dir, "anexos");
        public static readonly string LibraryDir = Path.Combine(Dir, "biblioteca");
        // Gerador de fechamento (roda o skill do usuario) e os STEP gerados.
        public static readonly string FenceDir = Path.Combine(Dir, "fechamento");

        private static readonly string LibraryFoldersFile = Path.Combine(Dir, "pastas-biblioteca.txt");
        private static readonly string[] ExampleCategories = { "Seguranca", "IHM", "Cerca", "Outros" };

        private const string InstructionsFile = "rsmcp-instrucoes.md";

        private const string DefaultClaudeMd =
            "# Assistente do RobotStudio\n\n" +
            "@" + InstructionsFile + "\n\n" +
            "## Padroes da empresa (edite a vontade)\n\n" +
            "<!-- Ex.: altura padrao das mesas, robos e posicionadores mais usados, distancia minima da cerca. -->\n";

        private const string DefaultLibraryFolders =
            "# Pastas da sua biblioteca de componentes, uma por linha (caminho completo ou relativo a esta pasta).\n" +
            "# Pode ser uma pasta de rede, para a equipe usar a mesma biblioteca.\n" +
            "# Subpastas viram categorias. Um .txt com o mesmo nome do arquivo guarda observacoes (ex.: altura de montagem).\n" +
            "biblioteca\n";

        public static void Prepare()
        {
            Directory.CreateDirectory(ImagesDir);
            Directory.CreateDirectory(AttachmentsDir);
            var utf8 = new UTF8Encoding(false);

            // mcp.json aponta para o servidor publicado na pasta "server" ao lado do add-in.
            var addinDir = Path.GetDirectoryName(typeof(Workspace).Assembly.Location);
            var serverExe = Path.Combine(addinDir, "server", "RsMcp.Server.exe");
            var json = "{\"mcpServers\":{\"robotstudio\":{\"command\":\"" + serverExe.Replace("\\", "\\\\") + "\",\"args\":[]}}}";
            File.WriteAllText(Path.Combine(Dir, "mcp.json"), json, utf8);

            // Arquivos do add-in, regravados a cada versao: instrucoes e gerador de fechamento.
            // O CLAUDE.md e do usuario: so e criado se faltar.
            WriteResource("RsMcp.Instrucoes.md", Path.Combine(Dir, InstructionsFile));
            Directory.CreateDirectory(FenceDir);
            WriteResource("RsMcp.gerar_fechamento.py", Path.Combine(FenceDir, "gerar_fechamento.py"));
            var claudeMd = Path.Combine(Dir, "CLAUDE.md");
            if (!File.Exists(claudeMd)) File.WriteAllText(claudeMd, DefaultClaudeMd, utf8);

            // Biblioteca do usuario: na primeira vez cria a pasta com categorias de exemplo.
            if (!Directory.Exists(LibraryDir))
            {
                foreach (var category in ExampleCategories)
                    Directory.CreateDirectory(Path.Combine(LibraryDir, category));
            }
            if (!File.Exists(LibraryFoldersFile)) File.WriteAllText(LibraryFoldersFile, DefaultLibraryFolders, utf8);
        }

        private static void WriteResource(string resource, string target)
        {
            using (var stream = typeof(Workspace).Assembly.GetManifestResourceStream(resource))
            using (var reader = new StreamReader(stream, Encoding.UTF8))
            {
                File.WriteAllText(target, reader.ReadToEnd(), new UTF8Encoding(false));
            }
        }

        // Pastas da biblioteca do usuario, lidas de pastas-biblioteca.txt.
        public static IEnumerable<string> UserLibraryFolders()
        {
            if (!File.Exists(LibraryFoldersFile)) return new[] { LibraryDir };
            return File.ReadAllLines(LibraryFoldersFile)
                .Select(line => line.Trim())
                .Where(line => line.Length > 0 && !line.StartsWith("#"))
                .Select(line => Path.IsPathRooted(line) ? line : Path.Combine(Dir, line))
                .ToList();
        }

        // Caminho relativo a pasta de trabalho, como o Claude deve abrir com Read.
        public static string RelativeImagePath(string file)
        {
            return "imagens/" + Path.GetFileName(file);
        }

        // Nome seguro para arquivo e pasta: sem acentos, so letras, numeros, _ e -. ".." vira vazio.
        public static string Slug(string text)
        {
            var plain = new string((text ?? "").Normalize(NormalizationForm.FormD)
                .Where(c => CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark).ToArray());
            return Regex.Replace(plain, "[^A-Za-z0-9_-]+", "_").Trim('_');
        }

        public static bool IsInside(string path, string dir)
        {
            var root = Path.GetFullPath(dir).TrimEnd('\\', '/') + Path.DirectorySeparatorChar;
            return Path.GetFullPath(path).StartsWith(root, StringComparison.OrdinalIgnoreCase);
        }
    }
}
