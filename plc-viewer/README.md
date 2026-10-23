# Visualizador de backup CLP Allen-Bradley

Gera uma página HTML única (abre no navegador, sem instalar nada) a partir de um
backup `.ACD` (Studio 5000 / RSLogix 5000) ou de um `.L5X` exportado.

## Uso

```bash
pip install -r requirements.txt
python acd_viewer.py MEU_PROJETO.ACD          # gera MEU_PROJETO.html
python acd_viewer.py MEU_PROJETO.L5X -o x.html
```

## PDF com todo o ladder

```bash
npm install playwright && npx playwright install chromium
node gerar_pdf.js MEU_PROJETO.html MEU_PROJETO_Ladder.pdf "MEU_PROJETO · Ladder"
```

Abrir `MEU_PROJETO.html#print` no navegador e imprimir também funciona.

## O que mostra

- Visão geral: controlador, tarefas, programas, contagens
- Árvore Tarefas → Programas → Rotinas, AOIs, tags, UDTs e módulos de I/O
- Ladder desenhado (contatos, bobinas, blocos, ramificações) com comentários dos rungs
  e descrição das tags
- Referência cruzada: clique em qualquer tag para ver todos os rungs onde é usada
- Busca global (Enter) por tag, instrução ou texto de comentário

## Limitações

- A leitura do `.ACD` usa a biblioteca open source `acd-tools` (engenharia reversa, alpha).
  Ladder, tags, UDTs, AOIs e módulos são extraídos; o conteúdo de rotinas **ST, FBD e SFC
  não é decodificado**. Para essas rotinas, exporte um `.L5X` no Studio 5000 e gere a
  página a partir dele.
- Alguns módulos aparecem com catálogo `CIP-x-y-z` quando a biblioteca não conhece o código comercial.
- Somente leitura: não edita nem faz download para o CLP.
