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
  ver: 0,
  verListas: 0,           // muda a cada atualização das listas/notas (cache de preços)                 // muda a cada alteração em S.itens (cache do catálogo)
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
  // Com uma janela aberta, o resto da tela fica "travado": põe o aviso dentro dela
  const abertas = [...$$('dialog[open]')];
  const dono = abertas.length ? abertas[abertas.length - 1] : document.body;
  if (t.parentElement !== dono) dono.append(t);
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

// Erro inesperado na tela: em vez de travar, mostra aviso com "Recarregar o app"
let erroMostrado = false;
function seguro(fn) {
  try { fn(); } catch (e) {
    console.error(e);
    if (!erroMostrado) { erroMostrado = true; mostrarTravado('Algo deu errado ao mostrar as listas.'); }
  }
}

function mostrarTravado(msg) {
  const box = $('#travado');
  if (!box) return;
  $('#travadoMsg').textContent = msg || 'Está demorando mais que o normal.';
  box.hidden = false;
}

// Recarrega o app do zero: apaga só a cópia offline do app (não apaga listas,
// login nem dados do Caderno) e busca a versão nova.
async function recarregarApp() {
  try {
    const regs = await navigator.serviceWorker.getRegistrations();
    for (const r of regs) if (r.scope.includes('/lista-familia/')) await r.unregister();
  } catch (_) {}
  try { for (const k of await caches.keys()) if (k.startsWith('lista-familia-')) await caches.delete(k); } catch (_) {}
  location.replace(location.pathname + '?v=' + Date.now());
}

function escutar() {
  pararEscuta();
  const opt = { includeMetadataChanges: true };
  S.unsub.push(db.collection('listas').onSnapshot(opt, (snap) => {
    S.listas = new Map(snap.docs.map((d) => [d.id, { id: d.id, ...d.data() }]));
    S.verListas++;
    S.pend.listas = snap.metadata.hasPendingWrites;
    S.carregado.listas = true;
    seguro(render);
    seguro(() => { if ($('#dlgPrecos') && $('#dlgPrecos').open) renderPrecos(); });
  }, erroLeitura));
  S.unsub.push(db.collection('itens').onSnapshot(opt, (snap) => {
    S.itens = new Map(snap.docs.map((d) => [d.id, { id: d.id, ...d.data() }]));
    S.ver++;
    S.pend.itens = snap.metadata.hasPendingWrites;
    S.carregado.itens = true;
    seguro(render);
    seguro(() => { if (CAT.tipo && !$('#dlgCat').open && !document.querySelector('.linha-novo-cat, .chip.segurando')) renderCatalogo(); });
  }, erroLeitura));
}

/* Em todas as listas: marcado = precisa (comprar/levar/fazer) e fica em cima;
   desmarcado = não precisa, fica embaixo (pode ocultar).
   (Antes só o Mercado era assim.) Textos de cada tipo em ROTULOS. */
const ROTULOS = {
  mercado: { topo: '🛒 Para comprar', baixo: 'Não precisa comprar', card: 'para comprar', nada: 'nada para comprar',
    vazio: 'Nada para comprar. Marque abaixo o que está faltando.', desmarcar: 'Desmarcar tudo (nada para comprar)' },
  viagem: { topo: '🧳 Para levar', baixo: 'Não precisa levar', card: 'para levar', nada: 'nada para levar',
    vazio: 'Nada para levar. Marque abaixo o que vai na mala.', desmarcar: 'Desmarcar tudo (nada para levar)' },
  geral: { topo: '📝 Marcados', baixo: 'Desmarcados', card: 'marcados', nada: 'nada marcado',
    vazio: 'Nada marcado. Marque abaixo o que precisa.', desmarcar: 'Desmarcar tudo' },
};
const rot = (l) => ROTULOS[l && l.tipo] || ROTULOS.geral;
/*
   Nos outros tipos: marcado = feito. O campo no banco é o mesmo ("feito"). */
const modoComprar = (l) => !!l;
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
    ? `Limpar a lista "${lista.nome}"?\n\n${n} ${n === 1 ? 'item marcado será desmarcado' : 'itens marcados serão desmarcados'}. Você pode desfazer logo em seguida.`
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

// Limpar: apaga os itens de baixo (desmarcados / "não precisa"). Os de cima não mudam.
function apagarMarcados(lista) {
  const feitos = itensDa(lista.id).filter((i) => !ativo(i, lista));
  if (!feitos.length) return toast('Nada para limpar');
  const n = feitos.length;
  if (!confirm(`Limpar a lista "${lista.nome}"?\n\n${n === 1 ? `O item de "${rot(lista).baixo}" será tirado da lista` : `Os ${n} itens de "${rot(lista).baixo}" serão tirados da lista`}. Os de cima não mudam. Você pode desfazer logo em seguida.`)) return;
  emLotes([...feitos.map((it) => (b) => b.delete(db.collection('itens').doc(it.id))), (b) => tocarLista(b, lista.id)]);
  toast(`Lista limpa (${n} ${n === 1 ? 'item tirado' : 'itens tirados'})`, {
    texto: 'Desfazer',
    fn: () => {
      emLotes(feitos.map((it) => (b) => { const { id, ...dados } = it; b.set(db.collection('itens').doc(id), dados); }));
      toast('Lista restaurada');
    },
  }, 10000);
}

function textoDaLista(lista) {
  const its = ordenar(itensDa(lista.id));
  const linhas = [`${TIPOS[lista.tipo].icone} ${lista.nome}`];
  const q = (i) => ((i.qtd || 1) > 1 ? `${i.qtd}x ` : '');
  const o = (i) => (i.obs ? ` (${i.obs})` : '');
  if (modoComprar(lista)) {
    for (const i of its.filter((x) => ativo(x, lista))) linhas.push('▢ ' + q(i) + i.texto + o(i));
  } else {
    for (const i of its.filter((x) => !x.feito)) linhas.push('▢ ' + q(i) + i.texto + o(i));
    for (const i of its.filter((x) => x.feito)) linhas.push('✔ ' + q(i) + i.texto + o(i));
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

/* ---------- Lista padrão (catálogo) ----------
   Começa com os itens de padrao.js. Na primeira mudança (adicionar, editar ou
   excluir), o catálogo é copiado para a nuvem e passa a ser compartilhado:
   cada item vira um documento em "itens" com listaId "__padrao__<tipo>"
   (assim não precisa mudar as regras do Firestore). */
const PADRAO = window.LISTA_PADRAO || {};
const SEGURAR_MS = 3000;                       // segurar ~3 s abre editar/excluir
const LISTA_CAT = (tipo) => `__padrao__${tipo}`;
const META_CAT = (tipo) => `pad_meta_${tipo}`;
const slug = (s) => norm(s).replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 60) || 'item';

// Catálogo atual: [{ secao, itens: [{ id|null, texto }] }]
const catCache = new Map();
function catalogo(tipo) {
  if (!PADRAO[tipo]) return [];
  const c = catCache.get(tipo);
  if (c && c.ver === S.ver) return c.v;
  const v = catalogoCalc(tipo);
  catCache.set(tipo, { ver: S.ver, v });
  return v;
}
function catalogoCalc(tipo) {
  const naNuvem = S.itens.has(META_CAT(tipo));
  if (!naNuvem) return PADRAO[tipo].map((s) => ({ secao: s.secao, itens: s.itens.map((texto) => ({ id: null, texto })) }));
  const ordemSec = PADRAO[tipo].map((s) => s.secao);
  const grupos = new Map(ordemSec.map((s) => [s, []]));
  const docs = [...S.itens.values()].filter((i) => i.listaId === LISTA_CAT(tipo))
    .sort((a, b) => (a.ordem || 0) - (b.ordem || 0) || a.texto.localeCompare(b.texto, 'pt-BR'));
  for (const d of docs) {
    if (!grupos.has(d.secao)) grupos.set(d.secao, []);
    grupos.get(d.secao).push({ id: d.id, texto: d.texto });
  }
  return [...grupos].map(([secao, itens]) => ({ secao, itens }));
}

function secaoDe(tipo, texto) {
  const k = norm(texto);
  for (const s of catalogo(tipo)) if (s.itens.some((i) => norm(i.texto) === k)) return s.secao;
  return 'Outros';
}

// Copia o catálogo padrão para a nuvem (uma vez). IDs fixos: se os dois
// aparelhos fizerem ao mesmo tempo, não duplica.
function garantirCatalogo(tipo) {
  if (S.itens.has(META_CAT(tipo))) return;
  const ops = [(b) => b.set(db.collection('itens').doc(META_CAT(tipo)), { listaId: '__meta__', tipo, catalogo: true })];
  let n = 0;
  for (const s of PADRAO[tipo]) {
    for (const texto of s.itens) {
      const ordem = n++;
      ops.push((b) => b.set(db.collection('itens').doc(`pad_${tipo}_${slug(texto)}`), { listaId: LISTA_CAT(tipo), texto, secao: s.secao, ordem }));
    }
  }
  emLotes(ops);
  // atualiza a memória já, para a tela refletir antes da resposta do banco
  S.itens.set(META_CAT(tipo), { id: META_CAT(tipo), listaId: '__meta__', tipo }); S.ver++;
  n = 0;
  for (const s of PADRAO[tipo]) for (const texto of s.itens) {
    const id = `pad_${tipo}_${slug(texto)}`;
    if (!S.itens.has(id)) S.itens.set(id, { id, listaId: LISTA_CAT(tipo), texto, secao: s.secao, ordem: n }); S.ver++;
    n++;
  }
}

function idCatalogo(tipo, texto) {
  const k = norm(texto);
  for (const s of catalogo(tipo)) for (const i of s.itens) if (norm(i.texto) === k) return i.id;
  return null;
}

function adicionarNoCatalogo(tipo, secao, texto) {
  texto = texto.trim().replace(/\s+/g, ' ').slice(0, 200);
  if (!texto) return false;
  garantirCatalogo(tipo);
  if (idCatalogo(tipo, texto)) { toast(`"${texto}" já está na lista padrão`); return false; }
  const ref = db.collection('itens').doc();
  const dados = { listaId: LISTA_CAT(tipo), texto, secao, ordem: Date.now() };
  S.itens.set(ref.id, { id: ref.id, ...dados }); S.ver++;
  gravar(ref.set(dados));
  return true;
}

async function editarItemCatalogo(tipo, texto) {
  garantirCatalogo(tipo);
  const id = idCatalogo(tipo, texto);
  const it = id && S.itens.get(id);
  if (!it) return;
  const d = $('#dlgCat');
  $('#dlgCatTexto').value = it.texto;
  const sel = $('#dlgCatSecao');
  sel.replaceChildren(...catalogo(tipo).map((s) => el('option', { value: s.secao, text: s.secao, selected: s.secao === it.secao })));
  d.returnValue = '';
  d.showModal();
  await new Promise((r) => d.addEventListener('close', r, { once: true }));
  const ref = db.collection('itens').doc(id);
  if (d.returnValue === 'del') {
    if (!confirm(`Excluir "${it.texto}" da lista padrão?`)) return;
    const { id: _, ...dados } = it;
    S.itens.delete(id); S.ver++;
    gravar(ref.delete());
    toast(`"${it.texto}" excluído da lista padrão`, { texto: 'Desfazer', fn: () => { S.itens.set(id, it); S.ver++; gravar(ref.set(dados)); renderCatalogo(); } }, 10000);
  } else if (d.returnValue === 'ok') {
    const texto2 = $('#dlgCatTexto').value.trim().replace(/\s+/g, ' ').slice(0, 200);
    const secao = sel.value;
    if (!texto2) return;
    if (norm(texto2) !== norm(it.texto) && idCatalogo(tipo, texto2)) { toast(`"${texto2}" já está na lista padrão`); return; }
    const mud = {};
    if (texto2 !== it.texto) mud.texto = texto2;
    if (secao !== it.secao) { mud.secao = secao; mud.ordem = Date.now(); }
    if (Object.keys(mud).length) { S.itens.set(id, { ...it, ...mud }); S.ver++; gravar(ref.update(mud)); }
  }
}

// Diálogo "O que está faltando?"
const CAT = { tipo: null, escolhidos: new Set(), ja: new Set() };

function renderCatalogo() {
  const box = $('#dlgPadraoItens');
  if (!CAT.tipo || !$('#dlgPadrao').open) return;
  const topo = box.scrollTop;
  box.replaceChildren();
  for (const s of catalogo(CAT.tipo)) {
    const chips = s.itens.map((i) => chipCatalogo(i.texto));
    const add = el('button', { type: 'button', class: 'chip-add', text: '+', ariaLabel: `Adicionar item em ${s.secao}` });
    add.onclick = () => abrirCampoNovo(add, s.secao);
    box.append(el('h3', { class: 'sec', text: s.secao }), el('div', { class: 'chips' }, ...chips, add));
  }
  box.scrollTop = topo;
  atualizarBotaoCatalogo();
}

function chipCatalogo(nome) {
  const k = norm(nome);
  const ja = CAT.ja.has(k);
  const cb = el('input', { type: 'checkbox', value: nome, checked: ja || CAT.escolhidos.has(k), disabled: ja });
  cb.onchange = () => { if (cb.checked) CAT.escolhidos.add(k); else CAT.escolhidos.delete(k); atualizarBotaoCatalogo(); };
  const chip = el('label', { class: 'chip' + (ja ? ' ja' : '') }, cb, el('span', { text: nome }));
  // Segurar ~3 s: editar ou excluir
  let timer = 0, x0 = 0, y0 = 0, disparou = false;
  const parar = () => { clearTimeout(timer); chip.classList.remove('segurando'); };
  chip.addEventListener('pointerdown', (e) => {
    if (e.button) return;
    disparou = false; x0 = e.clientX; y0 = e.clientY;
    chip.classList.add('segurando');
    timer = setTimeout(() => {
      disparou = true; chip.classList.remove('segurando');
      if (navigator.vibrate) navigator.vibrate(30);
      editarItemCatalogo(CAT.tipo, nome).then(renderCatalogo);
    }, SEGURAR_MS);
  });
  chip.addEventListener('pointermove', (e) => { if (Math.abs(e.clientX - x0) > 10 || Math.abs(e.clientY - y0) > 10) parar(); });
  chip.addEventListener('pointerup', parar);
  chip.addEventListener('pointercancel', parar);
  chip.addEventListener('pointerleave', parar);
  chip.addEventListener('contextmenu', (e) => e.preventDefault());
  chip.addEventListener('click', (e) => { if (disparou) { e.preventDefault(); disparou = false; } });
  return chip;
}

function abrirCampoNovo(botao, secao) {
  const inp = el('input', { type: 'text', class: 'novo-cat', maxLength: 200, placeholder: `Novo item em ${secao}`, enterKeyHint: 'done' });
  const ok = el('button', { type: 'button', class: 'btn primario ok-cat', text: '✓', ariaLabel: 'Adicionar à lista padrão' });
  const linha = el('div', { class: 'linha-novo-cat' }, inp, ok);
  botao.replaceWith(linha);
  const salvar = () => {
    if (inp.value.trim() && adicionarNoCatalogo(CAT.tipo, secao, inp.value)) {
      CAT.escolhidos.add(norm(inp.value.trim().replace(/\s+/g, ' ')));   // já vem marcado para adicionar
      toast(`"${inp.value.trim()}" adicionado à lista padrão`);
    }
    renderCatalogo();
  };
  ok.onclick = salvar;
  inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); salvar(); } });
  inp.focus();
  linha.scrollIntoView({ block: 'nearest' });
}

function atualizarBotaoCatalogo() {
  const n = CAT.escolhidos.size;
  $('#dlgPadraoOk').textContent = n ? `Adicionar ${n}` : 'Adicionar';
  $('#dlgPadraoOk').disabled = !n;
}

async function dialogoPadrao() {
  const l = S.listas.get(S.atual);
  if (!l || !PADRAO[l.tipo]) return;
  CAT.tipo = l.tipo;
  CAT.ja = new Set(itensDa(l.id).filter((i) => ativo(i, l)).map((i) => norm(i.texto)));
  CAT.escolhidos = new Set();
  const d = $('#dlgPadrao');
  d.returnValue = '';
  d.showModal();
  $('#dlgPadraoItens').scrollTop = 0;
  renderCatalogo();
  await new Promise((r) => d.addEventListener('close', r, { once: true }));
  const tipo = CAT.tipo;
  CAT.tipo = null;
  if (d.returnValue !== 'ok') return;
  // na ordem do catálogo
  const escolhidos = [];
  for (const s of catalogo(tipo)) for (const i of s.itens) if (CAT.escolhidos.has(norm(i.texto))) escolhidos.push(i.texto);
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
  $('#dlgItemObs').value = it.obs || '';
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
    const obs = $('#dlgItemObs').value.trim().replace(/\s+/g, ' ').slice(0, 120);
    if (obs !== (it.obs || '')) mud.obs = obs;
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
    { texto: '🧾  Lançar nota fiscal', fn: dialogoNota },
    { texto: '📈  Histórico de preços', fn: () => dialogoPrecos() },
    { texto: 'Sair da conta', fn: sair },
  ], S.user ? `Conectado como ${S.user.email}` : '');
}

function menuLista() {
  const l = S.listas.get(S.atual);
  if (!l) return;
  menu(l.nome, [
    ...(PADRAO[l.tipo] ? [{ texto: '🛒  Escolher da lista padrão', fn: dialogoPadrao }] : []),
    { texto: '📋  Adicionar vários itens (colar lista)', fn: dialogoColar },
    ...(l.tipo === 'mercado' ? [{ texto: '🧾  Lançar nota fiscal', fn: dialogoNota }, { texto: '📈  Histórico de preços', fn: () => dialogoPrecos() }] : []),
    { texto: '✏️  Renomear / mudar tipo', fn: () => dialogoLista(l) },
    { texto: '↗️  Compartilhar como texto', fn: () => compartilhar(l) },
    { texto: '↺  ' + rot(l).desmarcar, fn: () => desmarcarTudo(l) },
    { texto: `🧹  Limpar (tirar os de "${rot(l).baixo}")`, fn: () => apagarMarcados(l) },
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
  if (S.arrastando) { S.renderPendente = true; return; }   // não redesenha no meio de um arrasto
  S.renderPendente = false;
  atualizarStatus();
  if (!S.user) return;
  if (!S.carregado.listas || !S.carregado.itens) return mostrarTela('telaCarregando');
  if (S.atual && ehCompra(S.listas.get(S.atual))) S.atual = null;
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
  const listas = [...S.listas.values()].filter((l) => !ehCompra(l)).sort((a, b) => (b.mod || 0) - (a.mod || 0));
  $('#semListas').hidden = listas.length > 0;
  $('#listas').replaceChildren(...listas.map((l) => {
    const c = cont[l.id] || { tot: 0, feitos: 0 };
    const falta = c.tot - c.feitos;
    const barra = el('i');
    barra.style.width = c.tot ? (100 * c.feitos / c.tot) + '%' : '0';
    const texto = modoComprar(l)
      ? (c.feitos ? `${c.feitos} ${rot(l).card}` : (c.tot ? rot(l).nada : 'vazia'))
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
  // Quadradinho com o número; ao tocar abre o seletor do celular (1 a 10)
  const opcoes = [];
  for (let i = 1; i <= 10; i++) opcoes.push(i);
  if (q > 10) opcoes.push(q);                    // quantidade maior digitada no ✎ editar
  const sel = el('select', { class: 'qtd' + (q > 1 ? ' mais1' : ''), ariaLabel: 'Quantidade de ' + it.texto, title: 'Quantidade' },
    ...opcoes.map((n) => el('option', { value: String(n), text: String(n), selected: n === q })));
  sel.onchange = () => mudarQtd(it, Number(sel.value) - (it.qtd || 1));
  return sel;
}

// Arrastar o item da direita para a esquerda abre a caixinha de detalhe.
function arrastarParaDetalhe(li, it) {
  let x0 = 0, y0 = 0, dx = 0, ativo = false, horizontal = false, id = null;
  li.addEventListener('pointerdown', (e) => {
    if (e.button || e.target.closest('.qtd, .edit')) return;
    x0 = e.clientX; y0 = e.clientY; dx = 0; ativo = true; horizontal = false; id = e.pointerId;
    S.arrastando = true;               // segura atualizações da tela enquanto o dedo está no item
  });
  li.addEventListener('pointermove', (e) => {
    if (!ativo || e.pointerId !== id) return;
    const ddx = e.clientX - x0, ddy = e.clientY - y0;
    if (!horizontal) {
      if (Math.abs(ddy) > 12 && Math.abs(ddy) > Math.abs(ddx)) { ativo = false; soltar(); return; }   // rolando a tela
      if (ddx < -12 && Math.abs(ddx) > Math.abs(ddy)) { horizontal = true; try { li.setPointerCapture(id); } catch (_) {} li.classList.add('arrastando'); }
      else return;
    }
    dx = Math.max(-120, Math.min(0, ddx));
    li.style.transform = `translateX(${dx}px)`;
    li.classList.toggle('pronto', dx < -70);
  });
  const soltar = () => {
    if (!S.arrastando) return;
    S.arrastando = false;
    if (S.renderPendente) setTimeout(render, 0);   // depois do clique ser tratado
  };
  li.addEventListener('pointerup', () => {
    if (!ativo) return soltar();
    ativo = false;
    if (!horizontal) return soltar();
    soltar();
    li._arrastou = true;
    setTimeout(() => { li._arrastou = false; }, 400);
    li.style.transform = '';
    li.classList.remove('arrastando', 'pronto');
    if (dx < -70) editarDetalhe(it);
  });
  li.addEventListener('pointercancel', () => {
    ativo = false; li.style.transform = ''; li.classList.remove('arrastando', 'pronto');
    soltar();
  });
}

async function editarDetalhe(it) {
  const d = $('#dlgObs');
  $('#dlgObsTitulo').textContent = `Detalhe — ${it.texto}`;
  const inp = $('#dlgObsTexto');
  inp.value = it.obs || '';
  d.returnValue = '';
  d.showModal();
  inp.focus();
  await new Promise((r) => d.addEventListener('close', r, { once: true }));
  if (d.returnValue === 'ok') {
    const obs = inp.value.trim().replace(/\s+/g, ' ').slice(0, 120);
    if (obs !== (it.obs || '')) gravar(db.collection('itens').doc(it.id).update({ obs }));
  }
}

// Último preço pago (bem pequenininho), só nas listas de Mercado
function precoEl(it) {
  const l = S.listas.get(it.listaId);
  if (!l || l.tipo !== 'mercado') return null;
  const p = ultimoPreco(it.texto);
  if (!p) return null;
  return el('span', { class: 'ult-preco', text: `${reais(p.unit)} · ${p.mercado} ${dataBR(p.data).slice(0, 5)}`, title: `Último preço: ${p.nome}` });
}

function linhaItem(it) {
  const li = el('li', { class: 'item' + (it.feito ? ' feito' : '') + (S.recemAdicionado === it.id ? ' novo' : ''), 'data-id': it.id },
    el('span', { class: 'chk', role: 'checkbox', ariaChecked: String(!!it.feito) }),
    el('span', { class: 'txt' }, el('span', { class: 'nome', text: it.texto }),
      it.obs ? el('span', { class: 'obs', text: it.obs }) : null,
      precoEl(it)),
    qtdEl(it),
    el('button', { class: 'edit', text: '✎', ariaLabel: 'Editar ' + it.texto }));
  arrastarParaDetalhe(li, it);
  li.onclick = (e) => {
    if (li._arrastou) { li._arrastou = false; return; }
    if (e.target.closest('.qtd')) { /* toque na quantidade não marca o item */ }
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
    topo.push(el('h2', { class: 'titulo-bloco' }, el('span', { text: `${rot(l).topo} (${pend.length})` })));
    if (!pend.length && its.length) topo.push(el('p', { class: 'vazio pouco', text: rot(l).vazio }));
  }
  $('#pendentes').replaceChildren(...topo, ...agrupar(pend));
  $('#btnPadrao').hidden = !PADRAO[l.tipo];
  $('#btnNota').hidden = l.tipo !== 'mercado';
  const esconder = false;   // "Ocultar" foi substituído por "Limpar"
  $('#feitos').replaceChildren(...(esconder ? [] : (comprar ? agrupar(feitos) : [el('ul', { class: 'itens' }, ...feitos.map(linhaItem))])));
  $('#listaVazia').hidden = its.length > 0;
  $('#secFeitos').hidden = feitos.length === 0;
  $('#tituloFeitos').textContent = comprar ? `${rot(l).baixo} (${feitos.length})` : `Marcados (${feitos.length})`;
  $('#btnLimparFeitos').textContent = 'Limpar';
  $('#btnLimparFeitos').title = `Tirar da lista os itens de "${rot(l).baixo}"`;
  $('#btnLimparFeitos').hidden = !feitos.length;
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
    apagarMarcados(l);
  };
  $('#formItem').onsubmit = (e) => {
    e.preventDefault();
    const inp = $('#novoItem');
    adicionarItem(inp.value);
    inp.value = '';
    inp.focus();
  };
  $('#btnColar').onclick = dialogoColar;
  // Segurança: ao soltar o dedo em qualquer lugar, libera as atualizações da tela
  const liberar = () => setTimeout(() => {
    if (!S.arrastando) return;
    S.arrastando = false;
    if (S.renderPendente) render();
  }, 0);
  window.addEventListener('pointerup', liberar);
  window.addEventListener('pointercancel', liberar);
  window.addEventListener('blur', liberar);
  // Enter num campo das janelas = Salvar (evita acionar o 1º botão, como "Excluir")
  for (const d of $$('dialog')) {
    d.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && e.target.matches('input')) {
        e.preventDefault();
        if (e.target.form && !e.target.form.reportValidity()) return;
        d.close('ok');
      }
    });
  }
  $('#btnPadrao').onclick = dialogoPadrao;
  $('#btnNota').onclick = dialogoNota;
  $('#btnCopiarPedido').onclick = copiarPedido;
  $('#btnAbrirPrecos').onclick = () => { $('#dlgNota').close('cancel'); dialogoPrecos(); };
  $('#dlgPadraoTodos').onclick = () => {
    const todos = [];
    for (const s of catalogo(CAT.tipo)) for (const i of s.itens) if (!CAT.ja.has(norm(i.texto))) todos.push(norm(i.texto));
    const marcar = todos.some((k) => !CAT.escolhidos.has(k));
    CAT.escolhidos = new Set(marcar ? todos : []);
    renderCatalogo();
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
    if (e.key === 'Escape' && S.atual && !$('dialog[open]') && !/^(INPUT|SELECT|TEXTAREA)$/.test((document.activeElement || {}).tagName)) voltar();
  });
}

/* ---------- Início ---------- */
async function iniciar() {
  ligarEventos();
  if ('serviceWorker' in navigator) {
    // Quando sair versão nova, o app recarrega sozinho uma vez (todos os arquivos juntos)
    const tinhaControle = !!navigator.serviceWorker.controller;
    let recarregou = false;
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (!tinhaControle || recarregou || $('dialog[open]')) return;
      recarregou = true;
      location.reload();
    });
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
  $('#btnRecarregarApp').onclick = recarregarApp;
  // Se ficar "Carregando…" muito tempo, oferece recarregar
  setTimeout(() => { if (!$('#telaCarregando').hidden) mostrarTravado(); }, 12000);

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
