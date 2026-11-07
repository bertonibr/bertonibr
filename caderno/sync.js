'use strict';

/* =========================================================
   Sincronização com Google Drive (pasta oculta do app)
   - Login: OAuth 2.0 por redirecionamento (funciona no app
     instalado no iPad, sem pop-up).
   - Cada caderno vira um arquivo "nb-<id>" (JSON gzip) e cada
     imagem de fundo um arquivo "img-<id>" no appDataFolder.
   - Comparação por data de alteração; se os dois lados
     mudaram, os traços são juntados (nada se perde).
   Usa funções e estado definidos em app.js (S, DB, flushSave…).
   ========================================================= */

class AuthError extends Error {}

const Sync = (() => {
  const SCOPE = 'https://www.googleapis.com/auth/drive.appdata';
  const API = 'https://www.googleapis.com/drive/v3';
  const UP = 'https://www.googleapis.com/upload/drive/v3';
  const KEY = 'caderno.sync';
  const TITLES = {
    off: 'Sincronização com Google Drive desligada',
    ok: 'Sincronizado com o Google Drive',
    busy: 'Sincronizando…',
    err: 'Erro ao sincronizar',
    login: 'Toque para reconectar ao Google Drive',
    offline: 'Sem internet — sincroniza quando voltar',
  };

  let st = {};
  try { st = JSON.parse(localStorage.getItem(KEY) || '{}'); } catch (_) {}
  const persist = () => { try { localStorage.setItem(KEY, JSON.stringify(st)); } catch (_) {} };

  let state = 'off', stateMsg = '';
  let running = false, again = false, timer = 0;

  const clientId = () => (window.CADERNO_CONFIG || {}).googleClientId || '';
  const redirectUri = () => location.origin + location.pathname.replace(/index\.html$/, '');
  const tokenValid = () => !!st.token && Date.now() < (st.exp || 0) - 60000;

  function setState(s, msg) {
    state = s; stateMsg = msg || '';
    document.querySelectorAll('.sync-btn').forEach((b) => {
      b.dataset.state = s;
      b.title = msg || TITLES[s];
    });
  }

  /* ---------- Login ---------- */
  // Chamado no início: trata o retorno do Google (#access_token=…)
  function handleRedirect() {
    const h = location.hash;
    if (!h.includes('access_token=') && !h.includes('error=')) return null;
    const q = new URLSearchParams(h.slice(1));
    history.replaceState(null, '', location.pathname + location.search);
    const ok = q.get('state') && q.get('state') === st.oauthState;
    delete st.oauthState;
    const ret = st.returnNb || null;
    delete st.returnNb;
    if (ok && q.get('access_token')) {
      st.token = q.get('access_token');
      st.exp = Date.now() + Number(q.get('expires_in') || 3600) * 1000;
      st.connected = true;
      delete st.authError;
    } else if (ok) {
      st.authError = q.get('error') || 'erro';
    }
    persist();
    return ret;
  }

  async function login(silent) {
    if (!clientId()) {
      toast('Sincronização ainda não configurada (falta o Client ID do Google).', 4500);
      return;
    }
    try { await flushSave(); } catch (_) {}
    st.oauthState = Math.random().toString(36).slice(2) + Date.now().toString(36);
    st.returnNb = S.nb ? S.nb.id : null;
    persist();
    const p = new URLSearchParams({
      client_id: clientId(),
      redirect_uri: redirectUri(),
      response_type: 'token',
      scope: SCOPE,
      include_granted_scopes: 'true',
      state: st.oauthState,
    });
    if (silent) p.set('prompt', 'none');
    if (st.email) p.set('login_hint', st.email);
    location.href = 'https://accounts.google.com/o/oauth2/v2/auth?' + p;
  }

  async function disconnect() {
    if (!confirm('Desconectar do Google Drive? Os cadernos continuam neste aparelho e no Drive.')) return;
    if (st.token) fetch('https://oauth2.googleapis.com/revoke?token=' + encodeURIComponent(st.token), { method: 'POST' }).catch(() => {});
    st = {};
    persist();
    setState('off');
    toast('Desconectado do Google Drive');
  }

  /* ---------- Drive API ---------- */
  async function api(url, opts = {}) {
    if (!tokenValid()) { setState('login'); throw new AuthError(); }
    const r = await fetch(url, { ...opts, headers: { ...(opts.headers || {}), Authorization: 'Bearer ' + st.token } });
    if (r.status === 401) { st.token = null; persist(); setState('login'); throw new AuthError(); }
    if (!r.ok) throw new Error(`Drive ${r.status}: ${(await r.text()).slice(0, 160)}`);
    return r;
  }

  async function listFiles() {
    const files = [];
    let token = '';
    do {
      const q = new URLSearchParams({
        spaces: 'appDataFolder', pageSize: '1000',
        fields: 'nextPageToken,files(id,name,appProperties)',
      });
      if (token) q.set('pageToken', token);
      const j = await (await api(`${API}/files?${q}`)).json();
      files.push(...(j.files || []));
      token = j.nextPageToken;
    } while (token);
    return files;
  }

  async function upload(name, blob, mime, props, existingId) {
    const meta = existingId
      ? { appProperties: props }
      : { name, parents: ['appDataFolder'], mimeType: mime, appProperties: props };
    const path = existingId ? `/files/${existingId}` : '/files';
    const method = existingId ? 'PATCH' : 'POST';
    if (blob.size < 4.5e6) {
      const b = 'caderno' + Math.random().toString(36).slice(2);
      const body = new Blob([
        `--${b}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(meta)}\r\n`,
        `--${b}\r\nContent-Type: ${mime}\r\n\r\n`, blob, `\r\n--${b}--`,
      ]);
      const r = await api(`${UP}${path}?uploadType=multipart&fields=id`, {
        method, headers: { 'Content-Type': `multipart/related; boundary=${b}` }, body,
      });
      return (await r.json()).id;
    }
    // Arquivo grande: upload em duas etapas (resumable)
    const r = await api(`${UP}${path}?uploadType=resumable&fields=id`, {
      method,
      headers: { 'Content-Type': 'application/json; charset=UTF-8', 'X-Upload-Content-Type': mime },
      body: JSON.stringify(meta),
    });
    const loc = r.headers.get('Location');
    if (!loc) throw new Error('Drive não retornou o endereço de upload');
    const r2 = await fetch(loc, { method: 'PUT', body: blob });
    if (!r2.ok) throw new Error('Falha no upload: ' + r2.status);
    return (await r2.json()).id;
  }

  const download = async (id) => (await api(`${API}/files/${id}?alt=media`)).blob();

  async function pack(obj) {
    const json = JSON.stringify(obj);
    if (typeof CompressionStream === 'undefined') return { blob: new Blob([json]), enc: 'json', mime: 'application/json' };
    const s = new Blob([json]).stream().pipeThrough(new CompressionStream('gzip'));
    return { blob: await new Response(s).blob(), enc: 'gz', mime: 'application/gzip' };
  }
  async function unpack(blob, enc) {
    if (enc === 'gz') {
      const s = blob.stream().pipeThrough(new DecompressionStream('gzip'));
      return JSON.parse(await new Response(s).text());
    }
    return JSON.parse(await blob.text());
  }

  /* ---------- Dados locais ---------- */
  const isOpen = (id) => S.nb && S.nb.id === id;
  const openHasUnsaved = () => S.dirtyPages.size > 0 || S.metaDirty;

  async function localFull(nb) {
    if (isOpen(nb.id)) return { nb: S.nb, pages: S.pages };
    const pages = (await Promise.all(nb.pageIds.map((id) => DB.get('pages', id)))).filter(Boolean);
    return { nb, pages };
  }

  const metaOf = (nb) => ({
    id: nb.id, name: nb.name, color: nb.color, template: nb.template,
    pageIds: nb.pageIds, created: nb.created, updated: nb.updated,
  });

  async function fetchImages(pages, byName) {
    for (const p of pages) {
      for (const id of pageImgIds(p)) {
        if (await DB.get('images', id)) continue;
        const f = byName.get('img-' + id);
        if (f) await DB.put('images', await download(f.id), id);
      }
    }
  }

  // Grava no aparelho uma versão do caderno vinda do Drive (ou do merge)
  async function applyLocal(data, updated, syncedAt) {
    const id = data.nb.id;
    const old = await DB.get('notebooks', id);
    const keep = new Set(data.pages.map((p) => p.id));
    const used = new Set(data.pages.flatMap(pageImgIds));
    if (old && old.pageIds) {
      for (const pid of old.pageIds) {
        if (keep.has(pid)) continue;
        const p = await DB.get('pages', pid);
        if (p) for (const id of pageImgIds(p)) if (!used.has(id)) await DB.del('images', id);
        await DB.del('pages', pid);
      }
    }
    for (const p of data.pages) await DB.put('pages', p);
    const nb = { ...data.nb, pageIds: data.pages.map((p) => p.id), updated, syncedAt };
    await DB.put('notebooks', nb);
    if (isOpen(id)) await reloadOpenNotebook();
    return nb;
  }

  async function deleteLocal(id) {
    const nb = await DB.get('notebooks', id);
    if (nb && nb.pageIds) {
      for (const pid of nb.pageIds) {
        const p = await DB.get('pages', pid);
        if (p) for (const id of pageImgIds(p)) await DB.del('images', id);
        await DB.del('pages', pid);
      }
    }
    await DB.del('notebooks', id);
    if (isOpen(id)) { await showLibrary(); toast('Este caderno foi excluído em outro aparelho'); }
  }

  /* ---------- Operações ---------- */
  async function push(L, f, byName) {
    if (L.deleted) {
      await upload('nb-' + L.id, new Blob(['{}']), 'application/json', { updated: String(L.updated), deleted: '1' }, f && f.id);
      for (const img of L.imgIds || []) {
        const fi = byName.get('img-' + img);
        if (fi) await api(`${API}/files/${fi.id}`, { method: 'DELETE' }).catch(() => {});
      }
      await DB.del('notebooks', L.id);
      return;
    }
    const { nb, pages } = await localFull(L);
    const updated = nb.updated;
    for (const p of pages) {
      for (const imgId of pageImgIds(p)) {
        if (byName.has('img-' + imgId)) continue;
        const blob = await DB.get('images', imgId);
        if (!blob) continue;
        const id = await upload('img-' + imgId, blob, blob.type || 'image/jpeg', {}, null);
        byName.set('img-' + imgId, { id, name: 'img-' + imgId });
      }
    }
    const { blob, enc, mime } = await pack({ v: 1, nb: metaOf(nb), pages });
    const fid = await upload('nb-' + nb.id, blob, mime, { updated: String(updated), enc }, f && f.id);
    byName.set('nb-' + nb.id, { id: fid, name: 'nb-' + nb.id, appProperties: { updated: String(updated), enc } });
    nb.syncedAt = updated;
    if (isOpen(nb.id)) S.nb.syncedAt = updated;
    const cur = await DB.get('notebooks', nb.id);
    if (cur) { cur.syncedAt = updated; await DB.put('notebooks', cur); }
  }

  async function pull(f, byName, L) {
    const props = f.appProperties || {};
    const R = Number(props.updated || 0);
    const id = f.name.slice(3);
    if (props.deleted === '1') { if (L) await deleteLocal(id); return true; }
    const data = await unpack(await download(f.id), props.enc);
    await fetchImages(data.pages, byName);
    if (isOpen(id) && (openHasUnsaved() || (L && S.nb.updated !== L.updated))) return false;
    await applyLocal(data, R, R);
    return true;
  }

  const strokeKey = (s) => s.id || (s.color + ':' + s.pts.slice(0, 6).join(','));

  function mergeData(local, remote) {
    const rmap = new Map(remote.pages.map((p) => [p.id, p]));
    const lids = new Set(local.pages.map((p) => p.id));
    const out = local.pages.map((lp) => {
      const rp = rmap.get(lp.id);
      if (!rp) return lp;
      const seen = new Set(lp.strokes.map(strokeKey));
      const strokes = lp.strokes.slice();
      for (const s of rp.strokes) if (!seen.has(strokeKey(s))) strokes.push(s);
      const images = (lp.images || []).slice();
      for (const o of rp.images || []) if (!images.some((x) => x.id === o.id)) images.push(o);
      return { ...lp, strokes, images };
    });
    // páginas que só existem no Drive: insere após a página anterior delas
    remote.pages.forEach((rp, i) => {
      if (lids.has(rp.id)) return;
      let at = 0;
      for (let j = i - 1; j >= 0; j--) {
        const k = out.findIndex((p) => p.id === remote.pages[j].id);
        if (k >= 0) { at = k + 1; break; }
      }
      out.splice(at, 0, rp);
    });
    return { v: 1, nb: { ...local.nb, name: local.nb.name || remote.nb.name }, pages: out };
  }

  async function merge(L, f, byName) {
    const props = f.appProperties || {};
    if (props.deleted === '1') { await push(L, f, byName); return true; } // edição local vence exclusão
    const remote = await unpack(await download(f.id), props.enc);
    await fetchImages(remote.pages, byName);
    const { nb, pages } = await localFull(L);
    if (isOpen(nb.id) && (openHasUnsaved() || S.nb.updated !== L.updated)) return false;
    const merged = mergeData({ nb: metaOf(nb), pages }, remote);
    const now = Date.now();
    const saved = await applyLocal(merged, now, Number(props.updated || 0));
    await push(saved, f, byName);
    return true;
  }

  /* ---------- Ciclo de sincronização ---------- */
  async function run() {
    clearTimeout(timer);
    if (!st.connected) { setState('off'); return; }
    if (!navigator.onLine) { setState('offline'); return; }
    if (!tokenValid()) { setState('login'); return; }
    if (running) { again = true; return; }
    if (typeof cur !== 'undefined' && cur) { schedule(2000); return; } // escrevendo agora
    running = true;
    setState('busy');
    let libChanged = false;
    try {
      await flushSave();
      if (!st.email) {
        try {
          const j = await (await api(`${API}/about?fields=user(emailAddress)`)).json();
          st.email = j.user && j.user.emailAddress; persist();
        } catch (e) { if (e instanceof AuthError) throw e; }
      }
      const files = await listFiles();
      const byName = new Map(files.map((f) => [f.name, f]));
      const locals = await DB.all('notebooks');
      const ids = new Set(locals.map((n) => n.id));
      for (const f of files) if (f.name.startsWith('nb-')) ids.add(f.name.slice(3));

      for (const id of ids) {
        if (typeof cur !== 'undefined' && cur) { again = true; break; }
        let L = locals.find((n) => n.id === id);
        if (L && isOpen(id)) L = S.nb;
        const f = byName.get('nb-' + id);
        if (!L) { if (f && (f.appProperties || {}).deleted !== '1') { await pull(f, byName, null); libChanged = true; } continue; }
        if (!f) {
          if (L.deleted && !L.syncedAt) { await DB.del('notebooks', id); continue; }
          await push(L, null, byName); continue;
        }
        const R = Number((f.appProperties || {}).updated || 0);
        const B = L.syncedAt || 0;
        if (L.deleted) {
          // excluído aqui: se o outro aparelho editou depois, a edição vence
          if (R !== B && (f.appProperties || {}).deleted !== '1') { await DB.del('notebooks', id); await pull(f, byName, null); libChanged = true; }
          else await push(L, f, byName);
          continue;
        }
        const localChanged = L.updated !== B;
        const remoteChanged = R !== B;
        let done = true;
        if (localChanged && !remoteChanged) await push(L, f, byName);
        else if (!localChanged && remoteChanged) { done = await pull(f, byName, L); libChanged = true; }
        else if (localChanged && remoteChanged) { done = await merge(L, f, byName); libChanged = true; }
        if (!done) again = true;
      }
      st.lastSync = Date.now();
      persist();
      setState('ok');
      if (libChanged && !$('#library').hidden) renderLibrary();
    } catch (e) {
      if (!(e instanceof AuthError)) {
        console.error(e);
        setState(navigator.onLine ? 'err' : 'offline', 'Erro ao sincronizar: ' + e.message);
      }
    } finally {
      running = false;
      if (again) { again = false; schedule(3000); }
    }
  }

  function schedule(ms = 4000) {
    if (!st.connected) return;
    clearTimeout(timer);
    timer = setTimeout(run, ms);
  }

  /* ---------- Início e menu ---------- */
  // Retorna true se redirecionou para o Google (renovação silenciosa)
  function start() {
    if (!st.connected) { setState('off'); return false; }
    if (!tokenValid()) {
      const recent = st.lastAutoAuth && Date.now() - st.lastAutoAuth < 10 * 60e3;
      if (navigator.onLine && !st.authError && !recent) {
        st.lastAutoAuth = Date.now(); persist();
        login(true);
        return true;
      }
      setState('login');
      return false;
    }
    schedule(300);
    setInterval(() => { if (!document.hidden) schedule(0); }, 60e3);
    return false;
  }

  document.addEventListener('visibilitychange', () => {
    if (!st.connected) return;
    if (document.hidden) run();
    else if (tokenValid()) schedule(500);
    else setState('login');
  });
  window.addEventListener('online', () => schedule(500));
  window.addEventListener('offline', () => st.connected && setState('offline'));

  function menu(anchor) {
    if (!clientId()) {
      showMenu(anchor, [{ info: 'Sincronização com Google Drive ainda não configurada.' }]);
      return;
    }
    if (!st.connected) {
      showMenu(anchor, [
        { info: 'Guarde seus cadernos no Google Drive e use no iPad, PC e Android.' },
        { label: 'Conectar Google Drive', action: () => login(false) },
      ]);
      return;
    }
    const when = st.lastSync ? new Date(st.lastSync).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : 'nunca';
    const items = [{ info: `${st.email || 'Google Drive'}<br>Última sincronização: ${when}${state === 'err' ? '<br>' + stateMsg : ''}` }];
    if (!tokenValid()) items.push({ label: 'Reconectar', action: () => login(!st.authError) });
    else items.push({ label: 'Sincronizar agora', action: () => run() });
    items.push('-', { label: 'Desconectar', danger: true, action: disconnect });
    showMenu(anchor, items);
  }

  return { handleRedirect, start, schedule, run, menu, get connected() { return !!st.connected; } };
})();
