using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace RsMcp.AddIn
{
    /// <summary>
    /// Procura bibliotecas (.rslib) de robos, posicionadores e trilhos nas pastas usuais do RobotStudio.
    /// A pasta da ABB Library muda entre versoes, por isso a busca e ampla e o resultado fica em cache.
    /// </summary>
    public static class LibraryFinder
    {
        private static List<string> _cache;

        public static object Find(string filter, bool refresh)
        {
            if (_cache == null || refresh) _cache = Scan();
            var tokens = (filter ?? "").Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries).Select(Normalize).ToList();
            return _cache
                .Where(path => tokens.All(t => Normalize(Path.GetFileName(path)).Contains(t)))
                .Take(60)
                .Select(path => new { name = Path.GetFileNameWithoutExtension(path), path })
                .ToList();
        }

        // "IRB 2600-12" e "IRB2600_12" viram a mesma coisa.
        private static string Normalize(string text)
        {
            return text.ToUpperInvariant().Replace("_", "").Replace("-", "").Replace(" ", "");
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
                Walk(root, found);
            return found.Distinct(StringComparer.OrdinalIgnoreCase).OrderBy(Path.GetFileName).ToList();
        }

        // Varredura manual: pula pastas sem permissao e links (evita ciclos) em vez de abortar.
        private static void Walk(string dir, List<string> found)
        {
            try
            {
                found.AddRange(Directory.GetFiles(dir, "*.rslib"));
                foreach (var sub in Directory.GetDirectories(dir))
                {
                    if ((File.GetAttributes(sub) & FileAttributes.ReparsePoint) != 0) continue;
                    Walk(sub, found);
                }
            }
            catch (UnauthorizedAccessException) { }
            catch (IOException) { }
        }
    }
}
