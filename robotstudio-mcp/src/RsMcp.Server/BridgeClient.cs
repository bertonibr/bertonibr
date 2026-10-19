using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace RsMcp.Server;

/// <summary>
/// Cliente do named pipe exposto pelo add-in. Protocolo: uma linha JSON por requisicao
/// {"method": "...", "params": {...}} e uma linha JSON por resposta {"ok": bool, "result"|"error": ...}.
/// </summary>
public sealed class BridgeClient
{
    public const string PipeName = "RsMcpBridge";
    private readonly SemaphoreSlim _lock = new(1, 1);

    public async Task<string> CallAsync(string method, object? parameters = null, CancellationToken ct = default)
    {
        var request = new JsonObject
        {
            ["method"] = method,
            ["params"] = JsonSerializer.SerializeToNode(parameters ?? new { }),
        };

        await _lock.WaitAsync(ct);
        try
        {
            using var pipe = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
            try
            {
                await pipe.ConnectAsync(5000, ct);
            }
            catch (TimeoutException)
            {
                return "ERRO: RobotStudio nao respondeu. Abra o RobotStudio 2025 com o add-in RsMcp carregado.";
            }

            using var writer = new StreamWriter(pipe, new UTF8Encoding(false), leaveOpen: true) { AutoFlush = true };
            using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, leaveOpen: true);

            await writer.WriteLineAsync(request.ToJsonString());
            var line = await reader.ReadLineAsync(ct);
            if (line is null)
                return "ERRO: conexao com o add-in encerrada sem resposta.";

            var response = JsonNode.Parse(line);
            if (response?["ok"]?.GetValue<bool>() == true)
                return response["result"]?.ToJsonString() ?? "ok";
            return "ERRO: " + (response?["error"]?.ToString() ?? line);
        }
        finally
        {
            _lock.Release();
        }
    }
}
