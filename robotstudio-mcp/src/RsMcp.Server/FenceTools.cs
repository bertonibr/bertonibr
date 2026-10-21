using System.ComponentModel;
using System.Diagnostics;
using System.Text;
using System.Text.Json;
using ModelContextProtocol.Server;

namespace RsMcp.Server;

/// <summary>
/// Fechamento de seguranca: roda Documents\RsMcp\fechamento\gerar_fechamento.py (gravado pelo add-in),
/// que executa o gerador CadQuery do skill "fechamento-seguranca-celula" instalado no PC do usuario.
/// O skill (padroes e gerador do usuario) fica so no PC: nao vai para o repositorio.
/// Interface: argv[1] = arquivo JSON da configuracao; a saida e o resumo impresso pelo gerador do skill.
/// </summary>
[McpServerToolType]
public static class FenceTools
{
    private static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "RsMcp", "fechamento");
    private static readonly TimeSpan Timeout = TimeSpan.FromMinutes(15);

    [McpServerTool(Name = "generate_fence"), Description(
        "Gera o STEP do fechamento de seguranca com o gerador do skill fechamento-seguranca-celula. " +
        "config = JSON com NOME, H, Z0, MAXP, DOOR_ANG e LINHAS no formato do skill " +
        "(listas no lugar de tuplas; null para o vao \"resto\"), em coordenadas do mundo da estacao (mm). " +
        "OUT opcional (padrao: Documents\\RsMcp\\fechamento\\<NOME>.step). " +
        "Retorna pecas, postes, metros lineares, interferencias e o caminho do STEP; " +
        "depois importe com import_geometry em 0,0,0 e confira com capture_view.")]
    public static async Task<string> GenerateFence(
        [Description("Configuracao em JSON")] string config,
        CancellationToken ct = default)
    {
        var script = Path.Combine(Dir, "gerar_fechamento.py");
        if (!File.Exists(script))
            return "ERRO: gerador nao instalado em " + script + ". Veja a secao de fechamento no CLAUDE.md do projeto.";
        try
        {
            using var _ = JsonDocument.Parse(config);
        }
        catch (JsonException ex)
        {
            return "ERRO: config nao e um JSON valido: " + ex.Message;
        }

        Directory.CreateDirectory(Dir);
        var configFile = Path.Combine(Dir, $"config_{DateTime.Now:yyyyMMdd_HHmmss}.json");
        await File.WriteAllTextAsync(configFile, config, ct);

        foreach (var python in PythonCandidates())
        {
            Process? process;
            try
            {
                process = Process.Start(new ProcessStartInfo(python)
                {
                    ArgumentList = { script, configFile },
                    WorkingDirectory = Dir,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    StandardOutputEncoding = Encoding.UTF8,
                    StandardErrorEncoding = Encoding.UTF8,
                    Environment = { ["PYTHONIOENCODING"] = "utf-8" },
                });
            }
            catch (System.ComponentModel.Win32Exception)
            {
                continue; // este executavel nao existe; tenta o proximo
            }
            if (process is null) continue;

            using (process)
            {
                var stdout = process.StandardOutput.ReadToEndAsync(ct);
                var stderr = process.StandardError.ReadToEndAsync(ct);
                using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
                limit.CancelAfter(Timeout);
                try
                {
                    await process.WaitForExitAsync(limit.Token);
                }
                catch (OperationCanceledException)
                {
                    process.Kill(entireProcessTree: true);
                    return $"ERRO: o gerador passou de {Timeout.TotalMinutes:0} min e foi interrompido.";
                }
                var output = (await stdout).Trim();
                var errors = (await stderr).Trim();
                return process.ExitCode == 0
                    ? output
                    : $"ERRO (codigo {process.ExitCode}): {Tail(errors, 3000)}\n{Tail(output, 1000)}";
            }
        }
        return "ERRO: Python nao encontrado. Instale o Python 3 e o CadQuery (ver CLAUDE.md) ou defina RSMCP_PYTHON.";
    }

    // RSMCP_PYTHON tem prioridade; no Windows o launcher "py" vem antes de "python".
    private static IEnumerable<string> PythonCandidates()
    {
        var custom = Environment.GetEnvironmentVariable("RSMCP_PYTHON");
        if (!string.IsNullOrWhiteSpace(custom)) yield return custom;
        if (OperatingSystem.IsWindows()) yield return "py";
        yield return "python";
        yield return "python3";
    }

    private static string Tail(string text, int max) => text.Length <= max ? text : "..." + text[^max..];
}
