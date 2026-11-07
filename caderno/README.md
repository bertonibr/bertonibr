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
- **Importar PDF** ou imagem e escrever por cima
- **Exportar PDF** (compartilha pela folha do iPad)
- Funciona **offline**; tudo fica salvo no próprio iPad (IndexedDB)

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
