using System;
using System.IO;
using System.Linq;
using System.Text;

namespace RsMcp.AddIn
{
    /// <summary>
    /// Grava na biblioteca do usuario os componentes anexados no chat.
    /// Destino: primeira pasta de pastas-biblioteca.txt (normalmente Documents\RsMcp\biblioteca).
    /// So aceita arquivos da pasta de anexos e so escreve dentro da biblioteca.
    /// </summary>
    public static class UserLibrary
    {
        public static object SaveFromAttachment(string file, string category, string name, string notes, bool replace)
        {
            var source = Path.GetFullPath(file ?? "");
            if (!Workspace.IsInside(source, Workspace.AttachmentsDir))
                throw new InvalidOperationException("So da para guardar arquivos anexados no chat (pasta anexos).");
            if (!File.Exists(source)) throw new FileNotFoundException("Anexo nao encontrado: " + file);

            var segments = (category ?? "").Split('/', '\\').Select(Workspace.Slug).Where(s => s.Length > 0).ToArray();
            var fileName = Workspace.Slug(string.IsNullOrWhiteSpace(name) ? Path.GetFileNameWithoutExtension(source) : name);
            if (fileName.Length == 0) throw new ArgumentException("Nome invalido: " + name);

            var root = Workspace.UserLibraryFolders().FirstOrDefault() ?? Workspace.LibraryDir;
            var dir = Path.Combine(new[] { root }.Concat(segments).ToArray());
            Directory.CreateDirectory(dir);
            var target = Path.Combine(dir, fileName + Path.GetExtension(source).ToLowerInvariant());
            if (File.Exists(target))
            {
                if (!replace)
                    throw new IOException("Ja existe na biblioteca: " + target + ". Pergunte se substitui (replace) ou use outro nome.");
                File.Delete(target);
            }
            File.Move(source, target);
            if (!string.IsNullOrWhiteSpace(notes)) WriteNotes(target, notes);

            return new { path = target, category = string.Join("/", segments), name = fileName, tool = LibraryFinder.InsertTool(target) };
        }

        // Observacoes ficam num .txt com o mesmo nome do item. Texto vazio apaga.
        public static object SetNotes(string path, string notes)
        {
            var target = Path.GetFullPath(path ?? "");
            if (!File.Exists(target) || !Workspace.UserLibraryFolders().Any(root => Workspace.IsInside(target, root)))
                throw new InvalidOperationException("Item fora da biblioteca do usuario: " + path);

            var notesFile = Path.ChangeExtension(target, ".txt");
            if (string.IsNullOrWhiteSpace(notes))
            {
                if (File.Exists(notesFile)) File.Delete(notesFile);
                return "Observacoes removidas: " + Path.GetFileName(target);
            }
            WriteNotes(target, notes);
            return "Observacoes gravadas: " + notesFile;
        }

        private static void WriteNotes(string target, string notes)
        {
            File.WriteAllText(Path.ChangeExtension(target, ".txt"), notes.Trim(), new UTF8Encoding(false));
        }
    }
}
