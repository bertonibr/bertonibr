# Caderno — anotações à mão para iPad (PWA)

App web instalável para escrever com Apple Pencil, no estilo de um caderno digital.

## Recursos
- Biblioteca de cadernos (capa colorida, renomear, excluir)
- Caneta com **pressão** da Apple Pencil, marca-texto e borracha de traço
- Cores, cor personalizada e 3 espessuras por ferramenta
- **Só a Pencil escreve** (rejeição da palma): o dedo rola a página; liga sozinho ao detectar a Pencil
- Pinça com dois dedos para zoom, botões de zoom
- Papéis: branco, pautado, quadriculado, pontilhado
- Adicionar, limpar e excluir páginas; desfazer/refazer (Ctrl/⌘+Z)
- **Selecionar escrita (laço)**: contornar com a seta e mover, redimensionar, mudar cor, copiar/colar (outra página), duplicar e excluir
- **Fotos na página**: inserir várias, mover, redimensionar (proporcional ou livre), girar, frente/trás, duplicar e excluir — dá para escrever por cima
- **Importar PDF** ou imagem como páginas e escrever por cima
- **Exportar PDF** (compartilha pela folha do iPad)
- Funciona **offline**; tudo fica salvo no próprio iPad (IndexedDB)

## Sincronização com Google Drive
Os cadernos são salvos numa pasta oculta do app no seu Drive (escopo `drive.appdata`:
o app só vê os próprios arquivos). Sincroniza ao abrir, alguns segundos após editar e
a cada minuto; se dois aparelhos editarem o mesmo caderno, os traços são juntados.

Configuração (uma vez): crie um **ID do cliente OAuth** do tipo *Aplicativo da Web* no
Google Cloud Console, com a Google Drive API ativada,
origem `https://bertonibr.github.io` e URI de redirecionamento
`https://bertonibr.github.io/bertonibr/caderno/`, e coloque o ID em `config.js`.

## Instalar no iPad
1. Publique a pasta (ex.: GitHub Pages) e abra o link no **Safari**.
2. Toque em **Compartilhar → Adicionar à Tela de Início**.
3. Abra pelo ícone: roda em tela cheia, como um app.

> Os dados ficam no armazenamento do app no iPad. Exporte para PDF como backup.

## Rodar localmente
```bash
cd caderno
python3 -m http.server 8000
# abra http://localhost:8000
```

Bibliotecas incluídas em `lib/`: jsPDF 2.5.1 (MIT) e pdf.js 3.11.174 (Apache-2.0).
