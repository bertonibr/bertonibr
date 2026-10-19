using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using RsMcp.Server;

// Servidor MCP (stdio). Cada ferramenta repassa o comando ao add-in do RobotStudio
// pelo named pipe "RsMcpBridge". stdout e do protocolo MCP: log so em stderr.
var builder = Host.CreateApplicationBuilder(args);
builder.Logging.AddConsole(o => o.LogToStandardErrorThreshold = LogLevel.Trace);

builder.Services
    .AddSingleton<BridgeClient>()
    .AddMcpServer()
    .WithStdioServerTransport()
    .WithToolsFromAssembly();

await builder.Build().RunAsync();
