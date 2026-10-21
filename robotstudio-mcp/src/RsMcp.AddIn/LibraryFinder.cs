using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace RsMcp.AddIn
{
    /// <summary>
    /// Procura componentes para o layout:
    /// - ABB Library: .rslib de robos, posicionadores e trilhos nas pastas usuais do RobotStudio
    ///   (a pasta muda entre versoes, por isso a busca e ampla e fica em cache);
    /// - biblioteca do usuario: pastas de pastas-biblioteca.txt (barreira de luz, IHM, cerca...).
    /// </summary>
    public static class LibraryFinder
    {
        private static readonly HashSet<string> CadExtensions = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            ".sat", ".step", ".stp", ".igs", ".iges", ".stl", ".obj", ".glb", ".gltf", ".wrl", ".jt",
            ".x_t", ".x_b", ".sldprt", ".sldasm", ".ipt", ".iam", ".dae", ".fbx", ".3ds",
        };

        private static List<string> _cache;

        public static object Find(string filter, bool refresh)
        {
            if (_cache == null || refresh) _cache = Scan();
            var tokens = Tokens(filter);
            return _cache
                .Where(path => tokens.All(t => Normalize(Path.GetFileName(path)).Contains(t)))
                .Take(60)
                .Select(path => new { name = Path.GetFileNameWithoutExtension(path), path })
                .ToList();
        }

        // Biblioteca do usuario: a subpasta vira categoria; um .txt/.md com o mesmo nome traz observacoes.
        // Sem cache: o usuario pode acrescentar arquivos a qualquer momento.
        public static object FindUser(string filter)
        {
            var tokens = Tokens(filter);
            var items = new List<object>();
            foreach (var root in Workspace.UserLibraryFolders().Where(Directory.Exists))
            {
                var files = new List<string>();
                Walk(root, "*.*", files);
                foreach (var file in files)
                {
                    var isLibrary = string.Equals(Path.GetExtension(file), ".rslib", StringComparison.OrdinalIgnoreCase);
                    if (!isLibrary && !CadExtensions.Contains(Path.GetExtension(file))) continue;
                    var category = Category(root, file);
                    var name = Path.GetFileNameWithoutExtension(file);
                    if (!tokens.All(t => Normalize(category + name).Contains(t))) continue;
                    items.Add(new
                    {
                        category,
                        name,
                        tool = isLibrary ? "add_library" : "import_geometry",
                        path = file,
                        notes = ReadNotes(file),
                    });
                }
            }
            return items.Take(100).ToList();
        }

        private static List<string> Tokens(string filter)
        {
            return (filter ?? "").Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries).Select(Normalize).ToList();
        }

        // "IRB 2600-12" e "IRB2600_12" viram a mesma coisa.
        private static string Normalize(string text)
        {
            return text.ToUpperInvariant().Replace("_", "").Replace("-", "").Replace(" ", "").Replace("/", "");
        }

        private static string Category(string root, string file)
        {
            var baseDir = root.TrimEnd('\\', '/');
            var dir = Path.GetDirectoryName(file) ?? "";
            return dir.Length > baseDir.Length ? dir.Substring(baseDir.Length).Trim('\\', '/').Replace('\\', '/') : "";
        }

        private static string ReadNotes(string file)
        {
            foreach (var ext in new[] { ".txt", ".md" })
            {
                var notes = Path.ChangeExtension(file, ext);
                if (!File.Exists(notes)) continue;
                var text = File.ReadAllText(notes).Trim();
                return text.Length > 800 ? text.Substring(0, 800) + "..." : text;
            }
            return null;
        }

        private static List<string> Scan()
        {
            var roots = new[]
            {
                // pasta do RobotStudio (acima da Bin, onde roda o RobotStudio.exe)
                Path.GetDirectoryName(AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\')),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ABB"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "ABB"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "RobotStudio"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonDocuments), "RobotStudio"),
            };
            var found = new List<string>();
            foreach (var root in roots.Where(Directory.Exists).Distinct(StringComparer.OrdinalIgnoreCase))
                Walk(root, "*.rslib", found);
            return found.Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(Path.GetFileName).ToList();
        }

        // Varredura manual: pula pastas sem permissao e links (evita ciclos) em vez de abortar.
        private static void Walk(string dir, string pattern, List<string> found)
        {
            try
            {
                found.AddRange(Directory.GetFiles(dir, pattern));
                foreach (var sub in Directory.GetDirectories(dir))
                {
                    if ((File.GetAttributes(sub) & FileAttributes.ReparsePoint) != 0) continue;
                    Walk(sub, pattern, found);
                }
            }
            catch (UnauthorizedAccessException) { }
            catch (IOException) { }
        }
    }
}
