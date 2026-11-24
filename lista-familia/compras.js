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
async function dialogoNota() {
  const d = $('#dlgNota');
  $('#notaData').value = hojeISO();
  $('#notaMercado').value = '';
  $('#notaTexto').value = '';
  const mercados = [...new Set(compras().map((c) => c.mercado).filter(Boolean))];
  $('#mercadosSug').replaceChildren(...mercados.map((m) => el('option', { value: m })));
  const previa = () => {
    const its = lerNota($('#notaTexto').value);
    const tot = its.reduce((s, i) => s + i.total, 0);
    const temTexto = $('#notaTexto').value.trim().length > 0;
    $('#notaPrevia').textContent = its.length
      ? `${its.length} ${its.length === 1 ? 'produto lido' : 'produtos lidos'} — total ${reais(tot)}`
      : (temTexto ? 'Não consegui ler os produtos. O formato é: Produto; Quantidade; Valor unitário; Valor total' : 'Cole o texto da nota acima.');
    $('#notaSalvar').disabled = !its.length;
  };
  $('#notaTexto').oninput = previa;
  previa();
  d.returnValue = '';
  d.showModal();
  await new Promise((r) => d.addEventListener('close', r, { once: true }));
  if (d.returnValue !== 'ok') return;
  const itens = lerNota($('#notaTexto').value);
  if (!itens.length) return;
  const mercado = $('#notaMercado').value.trim().replace(/\s+/g, ' ') || 'Mercado';
  const data = $('#notaData').value || hojeISO();
  const total = +itens.reduce((s, i) => s + i.total, 0).toFixed(2);
  const ref = db.collection('listas').doc();
  gravar(ref.set({ tipo: 'compra', nome: mercado, mercado, data, itens, total, criadoEm: Date.now(), criadoPor: primeiroNome() }));
  toast(`Nota salva: ${mercado}, ${dataBR(data)} — ${itens.length} produtos, ${reais(total)}`, null, 5000);
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
    del.onclick = () => excluirNota(c);
    return el('li', {}, el('span', { text: `${dataBR(c.data)} · ${c.mercado}` }),
      el('span', { class: 'n-tot', text: `${(c.itens || []).length} itens · ${reais(c.total)}` }), del);
  }));
  $('#tituloNotas').textContent = `Notas lançadas (${cs.length})`;
}

function excluirNota(c) {
  if (!confirm(`Excluir a nota de ${c.mercado} (${dataBR(c.data)})?`)) return;
  const { id, ...dados } = c;
  gravar(db.collection('listas').doc(id).delete());
  toast('Nota excluída', { texto: 'Desfazer', fn: () => gravar(db.collection('listas').doc(id).set(dados)) }, 10000);
}

async function dialogoPrecos(busca) {
  const d = $('#dlgPrecos');
  $('#precoBusca').value = busca || '';
  $('#precoBusca').oninput = renderPrecos;
  d.returnValue = '';
  d.showModal();
  renderPrecos();
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
