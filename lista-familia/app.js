'use strict';

/* =========================================================
   Lista Família — listas compartilhadas (PWA)
   - Dados no Cloud Firestore (Firebase), com cópia offline
     no aparelho (IndexedDB): funciona sem internet e envia
     as mudanças quando a conexão volta.
   - Login: Google OAuth por redirecionamento (como o Caderno,
     funciona no app instalado no iPhone) → Firebase Auth.
   - Quem pode ler/escrever é definido nas regras do Firestore
     (só os e-mails da família).
   ========================================================= */

const CFG = window.LISTA_CONFIG || {};
const EMU = new URLSearchParams(location.search).has('emu') && /^(localhost|127\.0\.0\.1)$/.test(location.hostname);
const OAUTH_KEY = 'listaFamilia.oauth';

const TIPOS = {
  mercado: { icone: '🛒', nome: 'Mercado' },
  viagem: { icone: '🧳', nome: 'Viagem' },
  geral: { icone: '📝', nome: 'Geral' },
};

const $ = (s) => document.querySelector(s);
const $$ = (s) => document.querySelectorAll(s);

const S = {
  user: null,
  listas: new Map(),
  itens: new Map(),
  atual: null,            // id da lista aberta
  pend: { listas: false, itens: false },
  carregado: { listas: false, itens: false },
  unsub: [],
  recemAdicionado: null,
  criando: null,          // id de lista recém-criada (ainda sem retorno do banco)
};

let auth, db;

/* ---------- Utilidades ---------- */
function el(tag, attrs = {}, ...filhos) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k.startsWith('data-')) e.setAttribute(k, v);
    else e[k] = v;
  }
  for (const f of filhos) if (f != null) e.append(f);
  return e;
}

const norm = (s) => s.trim().toLocaleLowerCase('pt-BR').normalize('NFD').replace(/[̀-ͯ]/g, '');
const primeiroNome = () => ((S.user && (S.user.displayName || S.user.email)) || '').split(/[\s@]/)[0];

let toastTimer = 0;
function toast(msg, acao, ms) {
  const t = $('#toast');
  $('#toastMsg').textContent = msg;
  const b = $('#toastAcao');
  b.hidden = !acao;
  if (acao) { b.textContent = acao.texto; b.onclick = () => { t.hidden = true; acao.fn(); }; }
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, ms || (acao ? 6000 : 3000));
}

function mostrarTela(id) {
  for (const t of $$('.tela')) t.hidden = t.id !== id;
}

function erroEscrita(err) {
  console.error(err);
  if (err && err.code === 'permission-denied') toast('Sem permissão para salvar — confira as regras do Firestore.');
  else toast('Erro ao salvar: ' + ((err && err.message) || err));
}

/* Escritas não esperam o servidor: offline, a promessa só termina
   quando a internet volta. O Firestore já mostra a mudança na hora. */
function gravar(p) { p.catch(erroEscrita); }

async function emLotes(ops) {
  for (let i = 0; i < ops.length; i += 450) {
    const b = db.batch();
    for (const op of ops.slice(i, i + 450)) op(b);
    gravar(b.commit());
  }
}

/* ---------- Status de sincronização ---------- */
function atualizarStatus() {
  let st = 'ok', titulo = 'Sincronizado';
  if (!navigator.onLine) { st = 'offline'; titulo = 'Sem internet — salvo no aparelho; envia quando voltar'; }
  else if (S.pend.listas || S.pend.itens) { st = 'enviando'; titulo = 'Enviando…'; }
  for (const s of $$('.status')) { s.dataset.st = st; s.title = titulo; }
}
window.addEventListener('online', atualizarStatus);
window.addEventListener('offline', atualizarStatus);

/* ---------- Login ---------- */
function redirectUri() { return location.origin + location.pathname.replace(/index\.html$/, ''); }

function entrarGoogle() {
  if (EMU) return entrarEmulador();
  const state = Math.random().toString(36).slice(2) + Date.now().toString(36);
  try { localStorage.setItem(OAUTH_KEY, state); } catch (_) {}
  const p = new URLSearchParams({
    client_id: CFG.googleClientId,
    redirect_uri: redirectUri(),
    response_type: 'token',
    scope: 'openid email profile',
    state,
    prompt: 'select_account',
  });
  location.href = 'https://accounts.google.com/o/oauth2/v2/auth?' + p;
}

// Emulador local (testes): login falso com o e-mail digitado.
async function entrarEmulador() {
  const email = (new URLSearchParams(location.search).get('email')) || prompt('E-mail de teste');
  if (!email) return;
  const cred = firebase.auth.GoogleAuthProvider.credential(JSON.stringify({
    sub: 'u-' + email, email, email_verified: true, name: email.split('@')[0],
  }));
  await auth.signInWithCredential(cred);
}

// Volta do Google com #access_token=… → entra no Firebase.
async function tratarRetornoGoogle() {
  const h = location.hash;
  if (!h.includes('access_token=') && !h.includes('error=')) return;
  const q = new URLSearchParams(h.slice(1));
  history.replaceState(null, '', location.pathname + location.search);
  let esperado = null;
  try { esperado = localStorage.getItem(OAUTH_KEY); localStorage.removeItem(OAUTH_KEY); } catch (_) {}
  if (!q.get('state') || q.get('state') !== esperado) return;
  if (q.get('error')) { $('#msgEntrar').textContent = 'Login cancelado (' + q.get('error') + ').'; return; }
  try {
    await auth.signInWithCredential(firebase.auth.GoogleAuthProvider.credential(null, q.get('access_token')));
  } catch (err) {
    console.error(err);
    $('#msgEntrar').textContent = 'Não foi possível entrar: ' + (err.code || err.message);
  }
}

function sair() {
  if (!confirm('Sair da conta? As listas continuam salvas na nuvem.')) return;
  pararEscuta();
  auth.signOut().then(() => location.reload());
}

/* ---------- Dados em tempo real ---------- */
function pararEscuta() { S.unsub.forEach((u) => u()); S.unsub = []; }

function erroLeitura(err) {
  console.error(err);
  pararEscuta();
  mostrarTela('telaEntrar');
  $('#msgEntrar').textContent = err.code === 'permission-denied'
    ? `A conta ${S.user && S.user.email} não tem acesso às listas.\nPeça para incluir este e-mail nas regras do Firestore.`
    : 'Erro ao carregar: ' + (err.code || err.message);
  $('#btnEntrar').textContent = 'Entrar com outra conta';
  $('#btnEntrar').onclick = () => auth.signOut().then(entrarGoogle);
}

function escutar() {
  pararEscuta();
  const opt = { includeMetadataChanges: true };
  S.unsub.push(db.collection('listas').onSnapshot(opt, (snap) => {
    S.listas = new Map(snap.docs.map((d) => [d.id, { id: d.id, ...d.data() }]));
    S.pend.listas = snap.metadata.hasPendingWrites;
    S.carregado.listas = true;
    render();
  }, erroLeitura));
  S.unsub.push(db.collection('itens').onSnapshot(opt, (snap) => {
    S.itens = new Map(snap.docs.map((d) => [d.id, { id: d.id, ...d.data() }]));
    S.pend.itens = snap.metadata.hasPendingWrites;
    S.carregado.itens = true;
    render();
  }, erroLeitura));
}

/* Modo "comprar" (listas de Mercado): marcado = comprar, desmarcado = não precisa.
   Nos outros tipos: marcado = feito. O campo no banco é o mesmo ("feito"). */
const modoComprar = (l) => !!l && l.tipo === 'mercado';
const ATIVO = (l) => modoComprar(l);                       // valor de "feito" que significa "na lista para fazer/comprar"
const ativo = (it, l) => !!it.feito === ATIVO(l);
let ocultarNaoPrecisa = false;
try { ocultarNaoPrecisa = localStorage.getItem('listaFamilia.ocultar') === '1'; } catch (_) {}

const itensDa = (listaId) => [...S.itens.values()].filter((i) => i.listaId === listaId);
const tocarLista = (b, listaId) => b.update(db.collection('listas').doc(listaId), { mod: Date.now() });

/* ---------- Ações: listas ---------- */
async function dialogoLista(lista) {
  const d = $('#dlgLista');
  $('#dlgListaTitulo').textContent = lista ? 'Renomear lista' : 'Nova lista';
  $('#dlgListaNome').value = lista ? lista.nome : '';
  const tipo = lista ? lista.tipo : 'mercado';
  for (const r of d.querySelectorAll('input[name=tipo]')) r.checked = r.value === tipo;
  d.returnValue = '';
  d.showModal();
  $('#dlgListaNome').focus();
  await new Promise((r) => d.addEventListener('close', r, { once: true }));
  if (d.returnValue !== 'ok') return;
  const nome = $('#dlgListaNome').value.trim();
  const novoTipo = d.querySelector('input[name=tipo]:checked').value;
  if (!nome) return;
  if (lista) {
    gravar(db.collection('listas').doc(lista.id).update({ nome, tipo: novoTipo, mod: Date.now() }));
  } else {
    const ref = db.collection('listas').doc();
    S.criando = ref.id;
    gravar(ref.set({ nome, tipo: novoTipo, criadoEm: Date.now(), mod: Date.now(), criadoPor: primeiroNome() }));
    abrirLista(ref.id);
  }
}

function duplicarLista(lista) {
  const ref = db.collection('listas').doc();
  const agora = Date.now();
  const ops = [(b) => b.set(ref, { nome: lista.nome + ' (cópia)', tipo: lista.tipo, criadoEm: agora, mod: agora, criadoPor: primeiroNome() })];
  for (const it of itensDa(lista.id)) {
    ops.push((b) => b.set(db.collection('itens').doc(), {
      listaId: ref.id, texto: it.texto, feito: false, ordem: it.ordem, criadoPor: primeiroNome(), criadoEm: agora,
    }));
  }
  S.criando = ref.id;
  emLotes(ops);
  abrirLista(ref.id, true);
  toast('Lista duplicada, com tudo desmarcado');
}

function excluirLista(lista) {
  const n = itensDa(lista.id).length;
  if (!confirm(`Excluir a lista "${lista.nome}"${n ? ` e seus ${n} itens` : ''}? Isso apaga para todos.`)) return;
  const ops = itensDa(lista.id).map((it) => (b) => b.delete(db.collection('itens').doc(it.id)));
  ops.push((b) => b.delete(db.collection('listas').doc(lista.id)));
  emLotes(ops);
  voltar();
}

// Limpa a lista (desmarca tudo; no Mercado também volta as quantidades para 1),
// pedindo confirmação e oferecendo "Desfazer" que restaura exatamente o estado anterior.
function desmarcarTudo(lista) {
  const comprar = modoComprar(lista);
  const marcados = itensDa(lista.id).filter((i) => i.feito);
  if (!marcados.length) return toast(comprar ? 'A lista já está limpa' : 'Nada marcado');
  const mexer = itensDa(lista.id).filter((i) => i.feito || (comprar && (i.qtd || 1) > 1));
  const n = marcados.length;
  const pergunta = comprar
    ? `Limpar a lista "${lista.nome}"?\n\n${n} ${n === 1 ? 'item marcado para comprar será desmarcado' : 'itens marcados para comprar serão desmarcados'}. Você pode desfazer logo em seguida.`
    : `Desmarcar ${n} ${n === 1 ? 'item' : 'itens'} da lista "${lista.nome}"?`;
  if (!confirm(pergunta)) return;
  const antes = mexer.map((i) => ({ id: i.id, feito: !!i.feito, qtd: i.qtd || 1, feitoPor: i.feitoPor || null }));
  const ref = (id) => db.collection('itens').doc(id);
  emLotes([...mexer.map((it) => (b) => b.update(ref(it.id), comprar ? { feito: false, qtd: 1 } : { feito: false })),
    (b) => tocarLista(b, lista.id)]);
  toast(comprar ? `Lista limpa (${n} ${n === 1 ? 'item' : 'itens'})` : `${n} desmarcados`, {
    texto: 'Desfazer',
    fn: () => {
      const existem = antes.filter((a) => S.itens.has(a.id));   // ignora itens apagados nesse meio-tempo
      emLotes([...existem.map((a) => (b) => b.update(ref(a.id), { feito: a.feito, qtd: a.qtd, feitoPor: a.feitoPor })),
        (b) => tocarLista(b, lista.id)]);
      toast('Lista restaurada');
    },
  }, 10000);
}

function apagarMarcados(lista) {
  const feitos = itensDa(lista.id).filter((i) => i.feito);
  if (!feitos.length) return;
  emLotes([...feitos.map((it) => (b) => b.delete(db.collection('itens').doc(it.id))), (b) => tocarLista(b, lista.id)]);
  toast(`${feitos.length} ${feitos.length === 1 ? 'item apagado' : 'itens apagados'}`, {
    texto: 'Desfazer',
    fn: () => emLotes(feitos.map((it) => (b) => { const { id, ...dados } = it; b.set(db.collection('itens').doc(id), dados); })),
  });
}

function textoDaLista(lista) {
  const its = ordenar(itensDa(lista.id));
  const linhas = [`${TIPOS[lista.tipo].icone} ${lista.nome}`];
  const q = (i) => ((i.qtd || 1) > 1 ? `${i.qtd}x ` : '');
  if (modoComprar(lista)) {
    for (const i of its.filter((x) => ativo(x, lista))) linhas.push('▢ ' + q(i) + i.texto);
  } else {
    for (const i of its.filter((x) => !x.feito)) linhas.push('▢ ' + q(i) + i.texto);
    for (const i of its.filter((x) => x.feito)) linhas.push('✔ ' + q(i) + i.texto);
  }
  return linhas.join('\n');
}

async function compartilhar(lista) {
  const text = textoDaLista(lista);
  try {
    if (navigator.share) { await navigator.share({ title: lista.nome, text }); return; }
  } catch (e) { if (e.name === 'AbortError') return; }
  try { await navigator.clipboard.writeText(text); toast('Lista copiada — é só colar no WhatsApp'); }
  catch (_) { prompt('Copie a lista:', text); }
}

/* ---------- Ações: itens ---------- */
// Separa um texto colado (WhatsApp, notas…) em itens: uma linha por item,
// ou separados por vírgula/ponto e vírgula. Tira marcadores como "-", "•", "1.", "☐".
function separarItens(texto) {
  return texto
    .split(/\r?\n|;|,(?!\d)/)
    .map((t) => t.replace(/^\s*(?:[-–—*•·▢☐□✔✓✅🛒]+|\d+[.)-])\s*/u, '').trim().replace(/\s+/g, ' '))
    .filter((t) => t && t.length <= 200);
}

function adicionarVarios(texto) {
  if (!S.atual) return 0;
  const linhas = Array.isArray(texto) ? texto : separarItens(texto);
  const l = S.listas.get(S.atual), val = ATIVO(l);
  const existentes = new Map(itensDa(S.atual).map((i) => [norm(i.texto), i]));
  const ops = [];
  let novos = 0, ordem = Date.now();
  for (const t of linhas) {
    const k = norm(t);
    const ex = existentes.get(k);
    if (ex && ativo(ex, l)) continue;         // já está na lista
    const o = ordem++;
    if (ex) ops.push((b) => b.update(db.collection('itens').doc(ex.id), { feito: val, ordem: o }));
    else {
      const ref = db.collection('itens').doc();
      ops.push((b) => b.set(ref, { listaId: S.atual, texto: t, feito: val, ordem: o, criadoPor: primeiroNome(), criadoEm: Date.now() }));
    }
    existentes.set(k, { feito: val });
    novos++;
  }
  if (novos) { const id = S.atual; ops.push((b) => tocarLista(b, id)); emLotes(ops); }
  const rep = linhas.length - novos;
  toast(novos ? `${novos} ${novos === 1 ? 'item adicionado' : 'itens adicionados'}${rep ? ` (${rep} já estavam)` : ''}` : 'Todos esses itens já estão na lista');
  return novos;
}

/* ---------- Lista padrão (catálogo em padrao.js) ---------- */
const PADRAO = window.LISTA_PADRAO || {};
const SECOES = {};   // tipo → Map(norm(item) → seção)
for (const [tipo, secs] of Object.entries(PADRAO)) {
  SECOES[tipo] = new Map();
  for (const s of secs) for (const i of s.itens) SECOES[tipo].set(norm(i), s.secao);
}
const secaoDe = (tipo, texto) => (SECOES[tipo] && SECOES[tipo].get(norm(texto))) || 'Outros';

async function dialogoPadrao() {
  const l = S.listas.get(S.atual);
  if (!l || !PADRAO[l.tipo]) return;
  const pend = new Set(itensDa(l.id).filter((i) => ativo(i, l)).map((i) => norm(i.texto)));
  const box = $('#dlgPadraoItens');
  box.replaceChildren();
  for (const s of PADRAO[l.tipo]) {
    const chips = s.itens.map((nome) => {
      const ja = pend.has(norm(nome));
      const cb = el('input', { type: 'checkbox', value: nome, checked: ja, disabled: ja });
      return el('label', { class: 'chip' + (ja ? ' ja' : '') }, cb, el('span', { text: nome }));
    });
    box.append(el('h3', { class: 'sec', text: s.secao }), el('div', { class: 'chips' }, ...chips));
  }
  const atualizar = () => {
    const n = box.querySelectorAll('input:checked:not(:disabled)').length;
    $('#dlgPadraoOk').textContent = n ? `Adicionar ${n}` : 'Adicionar';
    $('#dlgPadraoOk').disabled = !n;
  };
  box.onchange = atualizar;
  atualizar();
  const d = $('#dlgPadrao');
  d.returnValue = '';
  d.showModal();
  box.scrollTop = 0;
  await new Promise((r) => d.addEventListener('close', r, { once: true }));
  if (d.returnValue !== 'ok') return;
  const escolhidos = [...box.querySelectorAll('input:checked:not(:disabled)')].map((c) => c.value);
  if (escolhidos.length) adicionarVarios(escolhidos);
}

async function dialogoColar() {
  const d = $('#dlgColar');
  $('#dlgColarTexto').value = '';
  d.returnValue = '';
  d.showModal();
  $('#dlgColarTexto').focus();
  await new Promise((r) => d.addEventListener('close', r, { once: true }));
  if (d.returnValue === 'ok') adicionarVarios($('#dlgColarTexto').value);
}

function adicionarItem(texto) {
  texto = texto.trim().replace(/\s+/g, ' ');
  if (!texto || !S.atual) return;
  const existente = itensDa(S.atual).find((i) => norm(i.texto) === norm(texto));
  const l = S.listas.get(S.atual), val = ATIVO(l);
  if (existente && ativo(existente, l)) { toast(`"${existente.texto}" já está na lista`); S.recemAdicionado = existente.id; render(); return; }
  const b = db.batch();
  if (existente) {
    b.update(db.collection('itens').doc(existente.id), { feito: val, ordem: Date.now() });
    S.recemAdicionado = existente.id;
  } else {
    const ref = db.collection('itens').doc();
    b.set(ref, { listaId: S.atual, texto, feito: val, ordem: Date.now(), criadoPor: primeiroNome(), criadoEm: Date.now() });
    S.recemAdicionado = ref.id;
  }
  tocarLista(b, S.atual);
  gravar(b.commit());
}

function alternarItem(it) {
  const b = db.batch();
  b.update(db.collection('itens').doc(it.id), { feito: !it.feito, feitoPor: !it.feito ? primeiroNome() : null });
  tocarLista(b, it.listaId);
  gravar(b.commit());
}

function mudarQtd(it, delta) {
  const q = Math.min(999, Math.max(1, (it.qtd || 1) + delta));
  if (q !== (it.qtd || 1)) gravar(db.collection('itens').doc(it.id).update({ qtd: q }));
}

async function editarItem(it) {
  const d = $('#dlgItem');
  $('#dlgItemTexto').value = it.texto;
  $('#dlgItemQtd').value = it.qtd || 1;
  $('#dlgItemQuem').textContent = it.criadoPor ? `Adicionado por ${it.criadoPor}` : '';
  d.returnValue = '';
  d.showModal();
  await new Promise((r) => d.addEventListener('close', r, { once: true }));
  if (d.returnValue === 'del') {
    const { id, ...dados } = it;
    gravar(db.collection('itens').doc(id).delete());
    toast(`"${it.texto}" excluído`, { texto: 'Desfazer', fn: () => gravar(db.collection('itens').doc(id).set(dados)) });
  } else if (d.returnValue === 'ok') {
    const texto = $('#dlgItemTexto').value.trim().replace(/\s+/g, ' ');
    const qtd = Math.min(999, Math.max(1, parseInt($('#dlgItemQtd').value, 10) || 1));
    const mud = {};
    if (texto && texto !== it.texto) mud.texto = texto;
    if (qtd !== (it.qtd || 1)) mud.qtd = qtd;
    if (Object.keys(mud).length) gravar(db.collection('itens').doc(it.id).update(mud));
  }
}

/* ---------- Menus ---------- */
async function menu(titulo, opcoes, info) {
  const d = $('#dlgMenu');
  $('#dlgMenuTitulo').textContent = titulo;
  const box = $('#dlgMenuOpcoes');
  box.replaceChildren();
  if (info) box.append(el('div', { class: 'info', text: info }));
  opcoes.forEach((o, i) => box.append(el('button', { value: String(i), class: o.perigo ? 'perigo' : '', text: o.texto })));
  d.returnValue = '';
  d.showModal();
  await new Promise((r) => d.addEventListener('close', r, { once: true }));
  const o = opcoes[Number(d.returnValue)];
  if (d.returnValue !== '' && d.returnValue !== 'cancel' && o) o.fn();
}

function menuInicio() {
  menu('Lista Família', [
    { texto: 'Sair da conta', fn: sair },
  ], S.user ? `Conectado como ${S.user.email}` : '');
}

function menuLista() {
  const l = S.listas.get(S.atual);
  if (!l) return;
  menu(l.nome, [
    ...(PADRAO[l.tipo] ? [{ texto: '🛒  Escolher da lista padrão', fn: dialogoPadrao }] : []),
    { texto: '📋  Adicionar vários itens (colar lista)', fn: dialogoColar },
    { texto: '✏️  Renomear / mudar tipo', fn: () => dialogoLista(l) },
    { texto: '↗️  Compartilhar como texto', fn: () => compartilhar(l) },
    { texto: modoComprar(l) ? '↺  Desmarcar tudo (nada para comprar)' : '↺  Desmarcar tudo', fn: () => desmarcarTudo(l) },
    ...(modoComprar(l) ? [] : [{ texto: '🧹  Apagar marcados', fn: () => apagarMarcados(l) }]),
    { texto: '⧉  Duplicar lista', fn: () => duplicarLista(l) },
    { texto: '🗑  Excluir lista', fn: () => excluirLista(l), perigo: true },
  ]);
}

/* ---------- Navegação ---------- */
function abrirLista(id, substituir) {
  S.atual = id;
  const st = { lista: id };
  if (substituir || (history.state && history.state.lista)) history.replaceState(st, '');
  else history.pushState(st, '');
  $('#novoItem').value = '';
  render();
  window.scrollTo(0, 0);
}

function voltar() {
  if (history.state && history.state.lista) history.back();
  else { S.atual = null; render(); }
}

window.addEventListener('popstate', () => {
  for (const d of $$('dialog[open]')) d.close('cancel');
  S.atual = (history.state && history.state.lista) || null;
  render();
});

/* ---------- Desenho ---------- */
const ordenar = (its) => its.sort((a, b) => (a.ordem || 0) - (b.ordem || 0));

function render() {
  atualizarStatus();
  if (!S.user) return;
  if (!S.carregado.listas || !S.carregado.itens) return mostrarTela('telaCarregando');
  if (S.atual && !S.listas.has(S.atual)) {
    if (S.atual === S.criando) return; // lista recém-criada: aparece no próximo instante
    S.atual = null;
    if (history.state && history.state.lista) history.replaceState(null, '');
  }
  if (S.listas.has(S.criando)) S.criando = null;
  if (S.atual) renderLista(); else renderInicio();
}

function renderInicio() {
  mostrarTela('telaInicio');
  const cont = {};
  for (const it of S.itens.values()) {
    const c = cont[it.listaId] || (cont[it.listaId] = { tot: 0, feitos: 0 });
    c.tot++; if (it.feito) c.feitos++;
  }
  const listas = [...S.listas.values()].sort((a, b) => (b.mod || 0) - (a.mod || 0));
  $('#semListas').hidden = listas.length > 0;
  $('#listas').replaceChildren(...listas.map((l) => {
    const c = cont[l.id] || { tot: 0, feitos: 0 };
    const falta = c.tot - c.feitos;
    const barra = el('i');
    barra.style.width = c.tot ? (100 * c.feitos / c.tot) + '%' : '0';
    const texto = modoComprar(l)
      ? (c.feitos ? `${c.feitos} para comprar` : (c.tot ? 'nada para comprar' : 'vazia'))
      : (c.tot ? (falta ? `faltam ${falta}` : 'tudo ok ✓') : 'vazia');
    const card = el('button', { class: 'card', 'data-id': l.id },
      el('span', { class: 'ico', text: (TIPOS[l.tipo] || TIPOS.geral).icone }),
      el('span', { class: 'nome', text: l.nome }),
      el('span', { class: 'cont', text: texto }),
      modoComprar(l) ? null : el('div', { class: 'barra' }, barra));
    card.onclick = () => abrirLista(l.id);
    return card;
  }));
}

function qtdEl(it) {
  const q = it.qtd || 1;
  const l = S.listas.get(it.listaId);
  if (!ativo(it, l)) return (q > 1 && !modoComprar(l)) ? el('span', { class: 'qtd-feito', text: `${q}x` }) : null;
  return el('span', { class: 'qtd' + (q > 1 ? ' mais1' : '') },
    el('button', { class: 'menos', type: 'button', text: '−', ariaLabel: 'Diminuir quantidade', disabled: q <= 1 }),
    el('span', { class: 'n', text: String(q), ariaLabel: 'Quantidade' }),
    el('button', { class: 'mais', type: 'button', text: '+', ariaLabel: 'Aumentar quantidade' }));
}

function linhaItem(it) {
  const li = el('li', { class: 'item' + (it.feito ? ' feito' : '') + (S.recemAdicionado === it.id ? ' novo' : ''), 'data-id': it.id },
    el('span', { class: 'chk', role: 'checkbox', ariaChecked: String(!!it.feito) }),
    el('span', { class: 'txt', text: it.texto }),
    qtdEl(it),
    el('button', { class: 'edit', text: '✎', ariaLabel: 'Editar ' + it.texto }));
  li.onclick = (e) => {
    if (e.target.closest('.qtd .mais')) mudarQtd(it, +1);
    else if (e.target.closest('.qtd .menos')) mudarQtd(it, -1);
    else if (e.target.closest('.qtd')) { /* toque no número não marca o item */ }
    else if (e.target.closest('.edit')) editarItem(it);
    else alternarItem(it);
  };
  return li;
}

function renderLista() {
  mostrarTela('telaLista');
  const l = S.listas.get(S.atual);
  $('#tituloLista').textContent = `${(TIPOS[l.tipo] || TIPOS.geral).icone} ${l.nome}`;
  document.title = l.nome + ' — Lista Família';
  const its = ordenar(itensDa(l.id));
  const comprar = modoComprar(l);
  const pend = its.filter((i) => ativo(i, l));
  const feitos = its.filter((i) => !ativo(i, l));
  $('#telaLista').classList.toggle('modo-comprar', comprar);
  // Agrupa por seção, na ordem do catálogo (itens fora dele vão para "Outros")
  const agrupar = (lista) => {
    if (!PADRAO[l.tipo] || !lista.length) return lista.length ? [el('ul', { class: 'itens' }, ...lista.map(linhaItem))] : [];
    const grupos = new Map([...PADRAO[l.tipo].map((s) => s.secao), 'Outros'].map((s) => [s, []]));
    for (const it of lista) grupos.get(secaoDe(l.tipo, it.texto)).push(it);
    const partes = [];
    for (const [sec, g] of grupos) {
      if (g.length) partes.push(el('h3', { class: 'sec', text: `${sec} (${g.length})` }), el('ul', { class: 'itens' }, ...g.map(linhaItem)));
    }
    return partes;
  };
  const topo = [];
  if (comprar) {
    const limpar = el('button', { class: 'link', type: 'button', text: 'Limpar', title: 'Desmarcar tudo (nada para comprar)' });
    limpar.onclick = () => desmarcarTudo(l);
    limpar.hidden = !pend.length;
    topo.push(el('h2', { class: 'titulo-bloco' }, el('span', { text: `🛒 Para comprar (${pend.length})` }), limpar));
    if (!pend.length && its.length) topo.push(el('p', { class: 'vazio pouco', text: 'Nada para comprar. Marque abaixo o que está faltando.' }));
  }
  $('#pendentes').replaceChildren(...topo, ...agrupar(pend));
  $('#btnPadrao').hidden = !PADRAO[l.tipo];
  const esconder = comprar && ocultarNaoPrecisa;
  $('#feitos').replaceChildren(...(esconder ? [] : (comprar ? agrupar(feitos) : [el('ul', { class: 'itens' }, ...feitos.map(linhaItem))])));
  $('#listaVazia').hidden = its.length > 0;
  $('#secFeitos').hidden = feitos.length === 0;
  $('#tituloFeitos').textContent = comprar ? `Não precisa comprar (${feitos.length})` : `Marcados (${feitos.length})`;
  $('#btnLimparFeitos').textContent = comprar ? (esconder ? 'Mostrar' : 'Ocultar') : 'Apagar marcados';
  S.recemAdicionado = null;

  // Sugestões: itens já usados em listas do mesmo tipo e que não estão na lista aqui
  const pendentes = new Set(pend.map((i) => norm(i.texto)));
  const vistos = new Map();
  for (const it of S.itens.values()) {
    const lista = S.listas.get(it.listaId);
    if (!lista || lista.tipo !== l.tipo) continue;
    const k = norm(it.texto);
    if (!pendentes.has(k) && !vistos.has(k)) vistos.set(k, it.texto);
  }
  const dl = $('#sugestoes');
  const novas = [...vistos.values()].sort((a, b) => a.localeCompare(b, 'pt-BR'));
  if (dl.dataset.v !== novas.join('\n')) {
    dl.dataset.v = novas.join('\n');
    dl.replaceChildren(...novas.map((t) => el('option', { value: t })));
  }
}

/* ---------- Eventos ---------- */
function ligarEventos() {
  $('#btnEntrar').onclick = entrarGoogle;
  $('#btnNovaLista').onclick = () => dialogoLista(null);
  $('#btnVoltar').onclick = voltar;
  $('#btnMenuInicio').onclick = menuInicio;
  $('#btnMenuLista').onclick = menuLista;
  $('#tituloLista').onclick = () => { const l = S.listas.get(S.atual); if (l) dialogoLista(l); };
  $('#btnLimparFeitos').onclick = () => {
    const l = S.listas.get(S.atual);
    if (!l) return;
    if (modoComprar(l)) {
      ocultarNaoPrecisa = !ocultarNaoPrecisa;
      try { localStorage.setItem('listaFamilia.ocultar', ocultarNaoPrecisa ? '1' : '0'); } catch (_) {}
      render();
    } else apagarMarcados(l);
  };
  $('#formItem').onsubmit = (e) => {
    e.preventDefault();
    const inp = $('#novoItem');
    adicionarItem(inp.value);
    inp.value = '';
    inp.focus();
  };
  $('#btnColar').onclick = dialogoColar;
  $('#btnPadrao').onclick = dialogoPadrao;
  $('#dlgPadraoTodos').onclick = () => {
    const cbs = [...$('#dlgPadraoItens').querySelectorAll('input:not(:disabled)')];
    const marcar = cbs.some((c) => !c.checked);
    cbs.forEach((c) => { c.checked = marcar; });
    $('#dlgPadraoItens').onchange();
  };
  // Colar no campo um texto com várias linhas → adiciona tudo de uma vez
  $('#novoItem').addEventListener('paste', (e) => {
    const t = (e.clipboardData || window.clipboardData).getData('text');
    if (t && /\r?\n/.test(t.trim())) {
      e.preventDefault();
      adicionarVarios(t);
      $('#novoItem').value = '';
    }
  });
  // Enter no diálogo de lista não deve fechar sem tipo
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && S.atual && !$('dialog[open]') && document.activeElement !== $('#novoItem')) voltar();
  });
}

/* ---------- Início ---------- */
async function iniciar() {
  ligarEventos();
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});

  if (!EMU && !CFG.firebase) { mostrarTela('telaConfig'); return; }

  firebase.initializeApp(EMU ? { apiKey: 'demo', projectId: 'demo-lista-familia', authDomain: 'localhost' } : CFG.firebase);
  auth = firebase.auth();
  db = firebase.firestore();
  if (EMU) {
    auth.useEmulator('http://127.0.0.1:9099', { disableWarnings: true });
    db.useEmulator('127.0.0.1', 8080);
  }
  try { await db.enablePersistence({ synchronizeTabs: true }); } catch (e) { console.warn('Sem cache offline:', e.code); }

  const retorno = tratarRetornoGoogle();
  if (history.state && history.state.lista) S.atual = history.state.lista;

  auth.onAuthStateChanged(async (user) => {
    S.user = user;
    if (user) {
      $('#msgEntrar').textContent = '';
      escutar();
      render();
    } else {
      pararEscuta();
      await retorno;
      if (!auth.currentUser) mostrarTela('telaEntrar');
    }
  });
}

iniciar();
