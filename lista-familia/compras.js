'use strict';

/* =========================================================
   Notas fiscais e histórico de preços
   - Cada nota lançada vira um documento em "listas" com
     tipo "compra" (não aparece como lista na tela inicial;
     assim não precisa mudar as regras do Firestore):
     { tipo:'compra', data:'AAAA-MM-DD', mercado, itens:[{nome,qtd,unit,total}], total }
   - O texto colado vem do Claude (foto da nota → texto), uma
     linha por produto: "Produto; Quantidade; Valor unitário; Valor total".
   Usa funções e estado de app.js (S, db, el, $, norm, toast…).
   ========================================================= */

const PEDIDO_CLAUDE = `Transcreva os produtos desta nota fiscal, um por linha, exatamente no formato:
Produto; Quantidade; Valor unitário; Valor total
Use vírgula nos decimais (ex.: 5,49) e não escreva mais nada além das linhas.`;

const ehCompra = (l) => !!l && l.tipo === 'compra';
const compras = () => [...S.listas.values()].filter(ehCompra)
  .sort((a, b) => (b.data || '').localeCompare(a.data || '') || (b.criadoEm || 0) - (a.criadoEm || 0));

const reais = (v) => 'R$ ' + (Number(v) || 0).toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const dataBR = (iso) => (iso ? iso.split('-').reverse().join('/') : '');
const hojeISO = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

// "1.234,56" / "R$ 5,49" / "0,5 kg" / "5.49" → número
function numBR(s) {
  if (s == null) return NaN;
  let t = String(s).replace(/R\$|\s/gi, '').replace(/[a-zA-Z]+$/, '');
  const m = t.match(/-?[\d.,]+/);
  if (!m) return NaN;
  t = m[0];
  if (t.includes(',')) t = t.replace(/\./g, '').replace(',', '.');
  else if ((t.match(/\./g) || []).length > 1) t = t.replace(/\./g, '');
  return parseFloat(t);
}

// Lê o texto colado e devolve [{nome, qtd, unit, total}]
function lerNota(texto) {
  const itens = [];
  // Com ";" separando os campos, o "|" (ou a quebra de linha) separa um produto do outro.
  // Sem ";", os campos podem vir separados por tab ou "|".
  const comPontoVirgula = texto.includes(';');
  const linhas = comPontoVirgula ? texto.split(/\r?\n|\|/) : texto.split(/\r?\n/);
  for (let linha of linhas) {
    linha = linha.replace(/^\s*(?:[-–—*•·]+|\d+[.)-]\s)\s*/, '').trim();
    if (!linha) continue;
    let partes = linha.split(comPontoVirgula ? /\s*[;\t]\s*/ : /\s*[\t|]\s*/).map((p) => p.trim()).filter((p) => p !== '');
    if (partes.length < 2) continue;
    const nome = partes[0].replace(/\s+/g, ' ');
    if (!/[a-zà-ú]/i.test(nome)) continue;
    const nums = partes.slice(1).map(numBR);
    if (nums.some((n) => Number.isNaN(n))) continue;   // cabeçalho ou linha estranha
    let qtd = 1, unit, total;
    if (nums.length >= 3) [qtd, unit, total] = nums;
    else if (nums.length === 2) { [qtd, total] = nums; unit = qtd ? total / qtd : total; }
    else { total = nums[0]; unit = total; }
    if (!(total >= 0)) continue;
    if (!(unit >= 0)) unit = qtd ? total / qtd : total;
    itens.push({ nome, qtd: +qtd.toFixed(3), unit: +unit.toFixed(2), total: +total.toFixed(2) });
  }
  return itens;
}

/* ---------- Lançar nota ---------- */
/* ---------- Lançar / abrir uma nota ----------
   A nota pode ser salva só com a foto ("guardada") e os produtos lidos depois:
   tocando nela em "Notas", abre de novo para colar o texto ou "Ler produtos".
   As fotos ficam em documentos "foto_…" na coleção "itens" (listaId "__foto__"),
   carregados só quando a nota é aberta. */
const NF = { nota: null, fotosNovas: [], fotosIds: [] };

async function dialogoNota(nota) {
  const d = $('#dlgNota');
  NF.nota = nota || null;
  NF.fotosNovas = [];
  NF.fotosIds = (nota && nota.fotos) || [];
  $('#dlgNotaTitulo').textContent = nota ? 'Nota fiscal' : 'Lançar nota fiscal';
  $('#notaData').value = (nota && nota.data) || hojeISO();
  $('#notaMercado').value = (nota && nota.mercado !== 'Mercado' && nota.mercado) || '';
  const fmt = (v) => String(+(+v).toFixed(3)).replace('.', ',');
  $('#notaTexto').value = nota ? (nota.itens || []).map((i) => `${i.nome}; ${fmt(i.qtd)}; ${fmt(i.unit)}; ${fmt(i.total)}`).join('\n') : '';
  const mercados = [...new Set(compras().map((c) => c.mercado).filter(Boolean))];
  $('#mercadosSug').replaceChildren(...mercados.map((m) => el('option', { value: m })));
  $('#notaTexto').oninput = previaNota;
  previaNota();
  renderFotosNota();
  d.returnValue = '';
  d.showModal();
  await new Promise((r) => d.addEventListener('close', r, { once: true }));
  if (d.returnValue !== 'ok') return;
  await salvarNota();
}

function previaNota() {
  const its = lerNota($('#notaTexto').value);
  const tot = its.reduce((s, i) => s + i.total, 0);
  const temTexto = $('#notaTexto').value.trim().length > 0;
  const nFotos = NF.fotosIds.length + NF.fotosNovas.length;
  $('#notaPrevia').textContent = its.length
    ? `${its.length} ${its.length === 1 ? 'produto lido' : 'produtos lidos'} — total ${reais(tot)}`
    : (temTexto ? 'Não consegui ler os produtos. O formato é: Produto; Quantidade; Valor unitário; Valor total'
      : (nFotos ? (NF.fotosNovas.length ? 'Toque em "Guardar nota" para guardar a foto. Os produtos podem ser lidos agora ou depois.' : 'Produtos ainda não lidos. Toque em "Ler produtos da foto" ou cole o texto acima.') : 'Tire a foto da nota ou cole o texto acima.'));
  $('#notaSalvar').disabled = !its.length && !nFotos;
  $('#notaSalvar').textContent = its.length ? 'Salvar nota' : 'Guardar nota';
  $('#btnLerNota').hidden = !nFotos;
}

function renderFotosNota() {
  const box = $('#notaFotos');
  const fotos = [
    ...NF.fotosIds.map((id) => ({ id })),
    ...NF.fotosNovas.map((b64, i) => ({ b64, nova: i })),
  ];
  box.replaceChildren(...fotos.map((f) => {
    const img = el('img', { alt: 'Foto da nota', loading: 'lazy' });
    if (f.b64) img.src = 'data:image/jpeg;base64,' + f.b64;
    else carregarFoto(f.id).then((b64) => { if (b64) img.src = 'data:image/jpeg;base64,' + b64; });
    const ver = el('button', { type: 'button', class: 'mini-foto', ariaLabel: 'Ver foto' }, img);
    ver.onclick = () => verFotos(fotos.map((x) => x.b64 ? Promise.resolve(x.b64) : carregarFoto(x.id)), fotos.indexOf(f));
    return ver;
  }));
  box.hidden = !fotos.length;
  $('#btnFotoNota').textContent = fotos.length ? '📷 Adicionar outra foto' : '📷 Tirar foto da nota';
  previaNota();
}

const cacheFotos = new Map();
async function carregarFoto(id) {
  if (cacheFotos.has(id)) return cacheFotos.get(id);
  try {
    const snap = await db.collection('itens').doc(id).get();
    const b64 = snap.exists ? snap.data().img : null;
    if (b64) cacheFotos.set(id, b64);
    return b64;
  } catch (e) { console.error(e); return null; }
}

// Mostra as fotos em tela cheia (uma embaixo da outra), rolando até a escolhida
async function verFotos(promessas, inicio = 0) {
  const d = $('#dlgFoto');
  const box = $('#dlgFotoLista');
  box.replaceChildren(el('p', { class: 'foto-carregando', text: 'Carregando foto…' }));
  if (!d.open) d.showModal();
  const imgs = [];
  for (const p of promessas) {
    const b64 = await p;
    const img = el('img', { alt: 'Foto da nota' });
    if (b64) img.src = 'data:image/jpeg;base64,' + b64;
    else imgs.push(el('p', { class: 'foto-carregando', text: 'Esta foto ainda não foi baixada. Abra com internet uma vez.' }));
    if (b64) imgs.push(img);
  }
  box.replaceChildren(...imgs);
  const alvo = box.children[inicio];
  if (alvo) alvo.scrollIntoView({ block: 'start' });
}

function verFotosNota(c) {
  verFotos((c.fotos || []).map(carregarFoto));
}

// Baixa em segundo plano as fotos ainda não vistas, para abrirem depois sem internet
let baixando = false;
async function baixarFotosParaOffline() {
  if (baixando || !navigator.onLine) return;
  baixando = true;
  try {
    for (const c of compras()) for (const id of c.fotos || []) if (!cacheFotos.has(id)) await carregarFoto(id);
  } finally { baixando = false; }
}

async function adicionarFotos(arquivos) {
  for (const a of arquivos) {
    try { NF.fotosNovas.push(await reduzirFoto(a)); }
    catch (e) { console.error(e); toast('Não consegui abrir essa foto.'); }
  }
  $('#notaFotoInput').value = '';
  renderFotosNota();
}

async function salvarNota() {
  const itens = lerNota($('#notaTexto').value);
  if (!itens.length && !NF.fotosIds.length && !NF.fotosNovas.length) return;
  const mercado = $('#notaMercado').value.trim().replace(/\s+/g, ' ') || 'Mercado';
  const data = $('#notaData').value || hojeISO();
  const total = +itens.reduce((s, i) => s + i.total, 0).toFixed(2);
  const ref = NF.nota ? db.collection('listas').doc(NF.nota.id) : db.collection('listas').doc();
  // fotos novas: um documento por foto (fora da lista que fica sempre carregada)
  const ids = [...NF.fotosIds];
  NF.fotosNovas.forEach((img, i) => {
    const id = `foto_${ref.id}_${Date.now().toString(36)}_${i}`;
    ids.push(id);
    cacheFotos.set(id, img);
    gravar(db.collection('itens').doc(id).set({ listaId: '__foto__', notaId: ref.id, img }));
  });
  const dados = { tipo: 'compra', nome: mercado, mercado, data, itens, total, fotos: ids };
  if (NF.nota) gravar(ref.update(dados));
  else gravar(ref.set({ ...dados, criadoEm: Date.now(), criadoPor: primeiroNome() }));
  toast(itens.length
    ? `Nota salva: ${mercado}, ${dataBR(data)} — ${itens.length} produtos, ${reais(total)}`
    : `Nota guardada (${ids.length} ${ids.length === 1 ? 'foto' : 'fotos'}). Leia os produtos quando quiser em ⋮ → Preços e notas.`, null, 5000);
}

async function copiarPedido() {
  try { await navigator.clipboard.writeText(PEDIDO_CLAUDE); toast('Pedido copiado — cole no Claude junto com a foto da nota'); }
  catch (_) { prompt('Copie e cole no Claude junto com a foto:', PEDIDO_CLAUDE); }
}

/* ---------- Histórico de preços ---------- */
function todasCompras() {   // [{nome, unit, qtd, total, data, mercado}]
  const out = [];
  for (const c of compras()) for (const i of c.itens || []) out.push({ ...i, data: c.data, mercado: c.mercado, notaId: c.id });
  return out;
}

function renderPrecos() {
  const q = norm($('#precoBusca').value || '');
  const box = $('#precoResultado');
  const todas = todasCompras();
  if (!todas.length) {
    box.replaceChildren(el('p', { class: 'vazio pouco', text: 'Nenhuma nota lançada ainda. Use "Lançar nota fiscal".' }));
    return renderNotas();
  }
  if (!q) {
    box.replaceChildren(el('p', { class: 'nota', text: 'Digite o nome de um produto para ver os preços.' }));
    return renderNotas();
  }
  const palavras = q.split(/\s+/).filter(Boolean);
  const achados = todas.filter((i) => { const n = norm(i.nome); return palavras.every((p) => n.includes(p)); });
  if (!achados.length) { box.replaceChildren(el('p', { class: 'vazio pouco', text: 'Nada encontrado.' })); return renderNotas(); }
  const grupos = new Map();
  for (const a of achados) { const k = norm(a.nome); if (!grupos.has(k)) grupos.set(k, []); grupos.get(k).push(a); }
  const blocos = [];
  for (const lista of [...grupos.values()].slice(0, 30)) {
    lista.sort((a, b) => (b.data || '').localeCompare(a.data || ''));
    const menor = Math.min(...lista.map((x) => x.unit));
    blocos.push(el('div', { class: 'preco-prod' },
      el('div', { class: 'preco-nome', text: lista[0].nome }),
      el('ul', { class: 'preco-linhas' }, ...lista.map((x) => el('li', { class: x.unit === menor && lista.length > 1 ? 'barato' : '' },
        el('span', { class: 'p-data', text: dataBR(x.data) }),
        el('span', { class: 'p-merc', text: x.mercado }),
        el('span', { class: 'p-val', text: reais(x.unit) }))))));
  }
  box.replaceChildren(...blocos);
  renderNotas();
}

function renderNotas() {
  const box = $('#precoNotas');
  const cs = compras();
  box.replaceChildren(...cs.map((c) => {
    const del = el('button', { type: 'button', class: 'link perigo-link', text: 'Excluir' });
    del.onclick = (e) => { e.stopPropagation(); excluirNota(c); };
    const n = (c.itens || []).length;
    const temFoto = (c.fotos || []).length > 0;
    const info = n ? `${n} itens · ${reais(c.total)}` : '⏳ produtos não lidos';
    const li = el('li', { class: 'nota-linha' + (n ? '' : ' pendente') },
      el('span', { class: 'n-nome', text: `${dataBR(c.data)} · ${c.mercado}` }),
      el('span', { class: 'n-tot', text: info }), del);
    li.onclick = () => { $('#dlgPrecos').close('cancel'); dialogoNota(c); };
    if (temFoto) {
      const foto = el('button', { type: 'button', class: 'btn-foto-nota', text: '📷', ariaLabel: 'Ver foto da nota' });
      foto.onclick = (e) => { e.stopPropagation(); verFotosNota(c); };
      li.prepend(foto);
    }
    return li;
  }));
  const pend = cs.filter((c) => !(c.itens || []).length).length;
  $('#tituloNotas').textContent = `Notas (${cs.length})${pend ? ` · ${pend} para ler` : ''}`;
}

function excluirNota(c) {
  if (!confirm(`Excluir a nota de ${c.mercado} (${dataBR(c.data)})?`)) return;
  const { id, ...dados } = c;
  gravar(db.collection('listas').doc(id).delete());
  let desfeito = false;
  toast('Nota excluída', { texto: 'Desfazer', fn: () => { desfeito = true; gravar(db.collection('listas').doc(id).set(dados)); } }, 10000);
  // as fotos só são apagadas depois que o "Desfazer" some
  setTimeout(() => { if (!desfeito) for (const f of c.fotos || []) gravar(db.collection('itens').doc(f).delete()); }, 11000);
}

async function dialogoPrecos(busca) {
  const d = $('#dlgPrecos');
  $('#precoBusca').value = busca || '';
  $('#precoBusca').oninput = renderPrecos;
  d.returnValue = '';
  d.showModal();
  renderPrecos();
  baixarFotosParaOffline();
  await new Promise((r) => d.addEventListener('close', r, { once: true }));
}

/* ---------- Último preço (pequenininho na lista de Mercado) ---------- */
let ultimoCache = { ver: -1, mapa: null };
function ultimoPreco(texto) {
  const ver = S.verListas;
  if (ultimoCache.ver !== ver) ultimoCache = { ver, mapa: new Map(), todas: todasCompras() };
  const k = norm(texto);
  if (ultimoCache.mapa.has(k)) return ultimoCache.mapa.get(k);
  const palavras = k.split(/\s+/).filter((p) => p.length >= 2);
  let melhor = null, melhorPts = -1;
  if (palavras.length) {
    for (const i of ultimoCache.todas) {
      const n = norm(i.nome);
      const ws = n.split(/[^a-z0-9]+/);
      // todas as palavras do item precisam aparecer no começo de alguma palavra do produto
      // aceita abreviação da nota: "condensado" ↔ "COND", "integral" ↔ "INTEG"
      if (!palavras.every((p) => ws.some((w) => w.startsWith(p) || (w.length >= 3 && p.startsWith(w))))) continue;
      const pts = (n.startsWith(palavras[0]) ? 2 : 0);
      if (pts > melhorPts || (pts === melhorPts && (i.data || '') > (melhor.data || ''))) { melhor = i; melhorPts = pts; }
    }
  }
  ultimoCache.mapa.set(k, melhor);
  return melhor;
}

/* ---------- Ler a foto da nota com o Claude (leitura automática) ----------
   Chamada direta do navegador à API da Anthropic (o app não tem servidor).
   A chave fica no banco de vocês (documento "cfg_anthropic" em "itens",
   que só as contas da família leem) — nunca no código do GitHub. */
const CHAVE_DOC = 'cfg_anthropic';
const MODELO_NOTA = 'claude-opus-5-5';
const chaveClaude = () => ((S.itens.get(CHAVE_DOC) || {}).chave || '');

const ESQUEMA_NOTA = {
  type: 'object',
  additionalProperties: false,
  required: ['mercado', 'data', 'total_nota', 'itens'],
  properties: {
    mercado: { type: 'string' },
    data: { type: 'string' },
    total_nota: { type: 'number' },
    itens: {
      type: 'array',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['nome', 'qtd', 'unit', 'total'],
        properties: { nome: { type: 'string' }, qtd: { type: 'number' }, unit: { type: 'number' }, total: { type: 'number' } },
      },
    },
  },
};

const PEDIDO_FOTO = `Estas fotos são de uma nota fiscal de supermercado brasileira (NFC-e ou cupom). Se houver mais de uma foto, são partes da mesma nota, em ordem.
Extraia:
- mercado: o nome do estabelecimento como as pessoas o chamam (nome fantasia; se só houver razão social, uma forma curta e legível dela).
- data: a data de emissão no formato AAAA-MM-DD (vazio se não aparecer).
- total_nota: o valor total pago da nota.
- itens: todos os produtos, na ordem da nota, com nome (como impresso, mas em letras normais em vez de tudo maiúsculo), qtd (quantidade ou peso em kg), unit (valor unitário) e total (valor total do item). Se um item tiver desconto, total já com o desconto.
Não invente produtos: se algum trecho estiver ilegível, deixe-o de fora.`;

// Reduz a foto para guardar: maior lado 2000 px, JPEG, abaixo de ~700 KB
// (limite de 1 MB por documento no banco).
async function reduzirFoto(arquivo) {
  const url = URL.createObjectURL(arquivo);
  try {
    const img = await new Promise((ok, erro) => { const i = new Image(); i.onload = () => ok(i); i.onerror = erro; i.src = url; });
    let lado = 2000, q = 0.72, b64 = '';
    for (let t = 0; t < 6; t++) {
      const f = Math.min(1, lado / Math.max(img.naturalWidth, img.naturalHeight));
      const c = document.createElement('canvas');
      c.width = Math.round(img.naturalWidth * f); c.height = Math.round(img.naturalHeight * f);
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      b64 = c.toDataURL('image/jpeg', q).split(',')[1];
      if (b64.length < 700000) break;
      q = Math.max(0.45, q - 0.1); lado = Math.round(lado * 0.85);
    }
    return b64;
  } finally { URL.revokeObjectURL(url); }
}

async function lerFotosNota(fotos) {
  const resp = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-api-key': chaveClaude(),
      'anthropic-version': '2026-10-21',
      'anthropic-beta': 'server-side-fallback-2026-07-01',
      'anthropic-dangerous-direct-browser-access': 'true',
    },
    body: JSON.stringify({
      model: MODELO_NOTA,
      max_tokens: 16000,
      fallbacks: 'default',
      output_config: { effort: 'medium', format: { type: 'json_schema', schema: ESQUEMA_NOTA } },
      messages: [{
        role: 'user',
        content: [
          ...fotos.map((data) => ({ type: 'image', source: { type: 'base64', media_type: 'image/jpeg', data } })),
          { type: 'text', text: PEDIDO_FOTO },
        ],
      }],
    }),
  });
  if (!resp.ok) {
    let msg = '';
    try { msg = (await resp.json()).error?.message || ''; } catch (_) {}
    const e = new Error(msg || `Erro ${resp.status}`); e.status = resp.status; throw e;
  }
  const r = await resp.json();
  if (r.stop_reason === 'refusal') throw new Error('O Claude não quis ler esta imagem.');
  if (r.stop_reason === 'max_tokens') throw new Error('A nota é longa demais para uma leitura. Tente em partes.');
  const texto = (r.content || []).filter((b) => b.type === 'text').map((b) => b.text).join('');
  return JSON.parse(texto);
}

// "Ler produtos": manda as fotos da nota (guardadas ou novas) para o Claude
async function lerProdutosDaNota() {
  if (!chaveClaude()) {
    toast('Para ler pela foto, ligue a leitura automática em ⋮ → Leitura automática de notas. Ou cole o texto da nota no campo abaixo.', null, 7000);
    return;
  }
  const btn = $('#btnLerNota');
  const txtOrig = btn.textContent;
  btn.disabled = true; btn.textContent = '⏳ Lendo a nota…';
  $('#notaPrevia').textContent = 'Lendo a foto com o Claude… pode levar uns 30 segundos.';
  try {
    const fotos = [];
    for (const id of NF.fotosIds) { const b = await carregarFoto(id); if (b) fotos.push(b); }
    fotos.push(...NF.fotosNovas);
    if (!fotos.length) throw new Error('não encontrei a foto');
    const n = await lerFotosNota(fotos);
    if (n.mercado && !$('#notaMercado').value.trim()) $('#notaMercado').value = n.mercado.trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(n.data || '')) $('#notaData').value = n.data;
    const fmt = (v) => String(+(+v).toFixed(3)).replace('.', ',');
    $('#notaTexto').value = (n.itens || []).map((i) => `${i.nome}; ${fmt(i.qtd)}; ${fmt(i.unit)}; ${fmt(i.total)}`).join('\n');
    previaNota();
    const soma = (n.itens || []).reduce((s, i) => s + (+i.total || 0), 0);
    if (n.total_nota) {
      const dif = Math.abs(soma - n.total_nota);
      $('#notaPrevia').textContent += dif < 0.05
        ? ' · bate com o total da nota ✓'
        : ` · ⚠ total impresso na nota: ${reais(n.total_nota)} (diferença de ${reais(dif)} — confira)`;
    }
  } catch (e) {
    console.error(e);
    if (e.status === 401) toast('A chave do Claude não funcionou. Confira em ⋮ → Leitura automática de notas.', null, 6000);
    else if (e.status === 429 || e.status === 529) toast('O Claude está ocupado agora. Tente de novo em instantes.', null, 5000);
    else toast('Não consegui ler a foto: ' + (e.message || e), null, 6000);
    previaNota();
  } finally {
    btn.disabled = false; btn.textContent = txtOrig;
  }
}

function configurarChave() {
  const atual = chaveClaude();
  const v = prompt(
    (atual ? 'Leitura automática está LIGADA.\nPara trocar, cole a nova chave. Para desligar, apague e toque em OK.\n\n'
           : 'Para o app ler a foto da nota sozinho, cole aqui a chave de API da Anthropic (começa com sk-ant-).\nVocê cria em console.anthropic.com → API keys.\n\n')
    + 'A chave fica guardada só no banco de vocês dois.', atual ? atual.slice(0, 12) + '…' : '');
  if (v === null) return;
  const chave = v.trim();
  if (chave.endsWith('…')) return;   // não mexeu
  const ref = db.collection('itens').doc(CHAVE_DOC);
  if (!chave) { gravar(ref.delete()); S.itens.delete(CHAVE_DOC); toast('Leitura automática desligada'); return; }
  if (!/^sk-ant-/.test(chave)) { toast('Essa não parece uma chave da Anthropic (começa com sk-ant-).', null, 5000); return; }
  S.itens.set(CHAVE_DOC, { id: CHAVE_DOC, listaId: '__config__', chave });
  gravar(ref.set({ listaId: '__config__', chave }));
  toast('Leitura automática ligada ✓ — agora é só tirar a foto da nota', null, 5000);
}
