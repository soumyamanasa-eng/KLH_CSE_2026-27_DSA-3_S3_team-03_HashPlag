const $ = id => document.getElementById(id);
let files = [];
let latestData = null;
let currentUser = null;
let progressTimer = null;

const SUPPORTED = /\.(pdf|docx|dotx|pptx|xlsx|odt|odp|ods|rtf|html?|txt|md|csv|tsv|log|json|xml|java|py|c|cpp|js|css|tex)$/i;
const LEGACY = /\.(doc|ppt|xls)$/i;

const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const cls = p => p >= 70 ? 'high' : p >= 30 ? 'mod' : 'low';
const icon = n => /\.pdf$/i.test(n) ? '📕' : /\.(docx?|dotx|odt|rtf)$/i.test(n) ? '📘' : /\.(pptx?|odp)$/i.test(n) ? '📙' : /\.(xlsx?|ods|csv)$/i.test(n) ? '📗' : /\.(java|py|c|cpp|js)$/i.test(n) ? '💻' : '📄';

async function apiJson(url, options = {}) {
  const response = await fetch(url, { credentials: 'same-origin', ...options });
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; }
  catch { throw new Error(`Unexpected server response (HTTP ${response.status}). Check the Java terminal.`); }
  if (!response.ok) {
    if (response.status === 401) showLogin();
    throw new Error(data.error || `Request failed (HTTP ${response.status})`);
  }
  return data;
}

function setStatus(message, isError = false) {
  $('status').textContent = message;
  $('status').style.color = isError ? '#b93838' : '';
}

function showLogin() {
  currentUser = null;
  $('loginCard').classList.remove('hidden');
  $('appWorkspace').classList.add('hidden');
  $('accountBar').classList.add('hidden');
}

function showWorkspace(user) {
  currentUser = user;
  $('loginCard').classList.add('hidden');
  $('appWorkspace').classList.remove('hidden');
  $('accountBar').classList.remove('hidden');
  $('accountLabel').textContent = `${user.username} · ${user.role}`;
  refreshHistory().catch(err => console.error(err));
}

$('loginForm').addEventListener('submit', async event => {
  event.preventDefault();
  $('btnLogin').disabled = true;
  $('loginStatus').textContent = 'Signing in...';
  try {
    const body = new URLSearchParams({username: $('loginUsername').value.trim(), password: $('loginPassword').value});
    const user = await apiJson('/api/login', {method: 'POST', headers: {'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'}, body});
    $('loginPassword').value = '';
    $('loginStatus').textContent = '';
    showWorkspace(user);
  } catch (err) {
    $('loginStatus').textContent = err.message;
    $('loginStatus').style.color = '#b93838';
  } finally { $('btnLogin').disabled = false; }
});

$('btnLogout').addEventListener('click', async () => {
  try { await fetch('/api/logout', {method:'POST', credentials:'same-origin'}); } catch {}
  files = []; latestData = null; renderFiles(); $('results').innerHTML = ''; showLogin();
});

async function checkSession() {
  try {
    const session = await apiJson('/api/me');
    if (session.authenticated) showWorkspace(session);
    else showLogin();
  } catch { showLogin(); }
}

function readBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(',')[1] || '');
    reader.onerror = () => reject(reader.error || new Error('Could not read ' + file.name));
    reader.readAsDataURL(file);
  });
}

async function addFiles(fileList) {
  const skipped = [];
  for (const file of [...fileList]) {
    if (file.size > 15 * 1024 * 1024) { skipped.push(`${file.name} (larger than the 15 MB per-file limit)`); continue; }
    if (LEGACY.test(file.name)) { skipped.push(`${file.name} (old .doc/.ppt/.xls: save it as a newer format)`); continue; }
    if (!SUPPORTED.test(file.name)) { skipped.push(`${file.name} (unsupported type)`); continue; }
    if (files.some(item => item.name === file.name && item.size === file.size)) continue;
    try {
      const b64 = await readBase64(file);
      files.push({name:file.name, size:file.size, b64});
    } catch (err) { skipped.push(`${file.name} (could not read)`); }
  }
  renderFiles();
  if (skipped.length) setStatus('Skipped: ' + skipped.join('; '), true);
}

function renderFiles() {
  const list = $('fileList');
  list.innerHTML = '';
  files.forEach((file, index) => {
    const li = document.createElement('li');
    const name = document.createElement('span');
    name.innerHTML = `${icon(file.name)} ${esc(file.name)} <span class="small">(${(file.size / 1024).toFixed(1)} KB)</span>`;
    const remove = document.createElement('button');
    remove.type = 'button'; remove.textContent = 'Remove';
    remove.addEventListener('click', () => { files.splice(index, 1); renderFiles(); });
    li.append(name, remove); list.appendChild(li);
  });
  $('btnCompare').disabled = files.length < 2;
  if (files.length < 2) setStatus('Select at least two documents.');
  else setStatus(`${files.length} documents ready to compare.`);
}

const drop = $('drop');
drop.addEventListener('click', () => $('fileInput').click());
drop.addEventListener('keydown', e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); $('fileInput').click(); } });
$('btnFiles').addEventListener('click', () => $('fileInput').click());
$('btnFolder').addEventListener('click', () => $('folderInput').click());
$('btnClear').addEventListener('click', () => { files = []; latestData = null; renderFiles(); $('results').innerHTML = ''; });
$('fileInput').addEventListener('change', async e => { await addFiles(e.target.files); e.target.value = ''; });
$('folderInput').addEventListener('change', async e => { await addFiles(e.target.files); e.target.value = ''; });
['dragenter','dragover'].forEach(name => drop.addEventListener(name, e => { e.preventDefault(); drop.classList.add('over'); }));
['dragleave','drop'].forEach(name => drop.addEventListener(name, e => { e.preventDefault(); drop.classList.remove('over'); }));
drop.addEventListener('drop', e => addFiles(e.dataTransfer.files));
$('window').addEventListener('input', e => { $('wLabel').textContent = e.target.value; });

function updateProgress(percent, message) {
  const value = Math.max(0, Math.min(100, Number(percent) || 0));
  $('progressFill').style.width = `${value}%`;
  $('progressPercent').textContent = `${value}%`;
  $('progressMessage').textContent = message || 'Processing...';
}

async function pollProgress() {
  try {
    const state = await apiJson('/api/progress');
    updateProgress(state.percent, state.message);
  } catch (err) { /* the main request displays any session/server error */ }
}

$('btnCompare').addEventListener('click', async () => {
  if (files.length < 2) return;
  $('btnCompare').disabled = true;
  $('btnCancel').classList.remove('hidden');
  $('btnCancel').disabled = false;
  $('progressWrap').classList.remove('hidden');
  updateProgress(1, 'Uploading documents...');
  setStatus('Comparison in progress...');
  const body = new URLSearchParams();
  body.append('window', $('window').value);
  body.append('mode', $('mode').value);
  files.forEach(file => { body.append('name', file.name); body.append('data', file.b64); });
  progressTimer = setInterval(pollProgress, 450);
  try {
    const data = await apiJson('/api/compare', {method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'}, body});
    latestData = data;
    updateProgress(100, 'Comparison complete');
    showResults(data);
    setStatus('Done. Report saved to scan history.');
    await refreshHistory();
  } catch (err) {
    if (err.message === 'Scan cancelled by user.') {
      $('results').innerHTML = '<div class="card"><b>Scan cancelled</b><p class="muted small">The scan was stopped at a processing checkpoint. You can retry it.</p></div>';
      setStatus('Scan cancelled.');
    } else {
      $('results').innerHTML = `<div class="card error-panel"><b>Comparison failed</b><p>${esc(err.message)}</p></div>`;
      setStatus(err.message, true);
    }
  } finally {
    if (progressTimer) clearInterval(progressTimer);
    progressTimer = null;
    $('btnCompare').disabled = files.length < 2;
    $('btnCancel').classList.add('hidden');
    setTimeout(() => $('progressWrap').classList.add('hidden'), 1800);
  }
});

$('btnCancel').addEventListener('click', async () => {
  $('btnCancel').disabled = true;
  setStatus('Requesting cancellation...');
  try { await apiJson('/api/cancel', {method:'POST'}); }
  catch (err) { setStatus(err.message, true); }
});

function reportAsText(data) {
  const out = [];
  out.push('HashPlag Detect - Similarity Report');
  out.push(`Generated: ${new Date().toLocaleString()}`);
  out.push(`Mode: ${data.mode === 'code' ? 'Programming assignments (token-normalized)' : 'Document text'}`);
  out.push(`Character window size: ${data.window}`);
  out.push('Documents: ' + (data.documents || []).join(', '));
  out.push('');
  (data.results || []).forEach(r => {
    out.push(`${r.a} <-> ${r.b}`);
    out.push(`Overall exact-match similarity: ${Number(r.overall).toFixed(2)}% (${r.verdict})`);
    out.push(`${Number(r.simA).toFixed(2)}% of ${r.a}; ${Number(r.simB).toFixed(2)}% of ${r.b}`);
    out.push(`Word n-gram overlap: ${Number(r.wordNgramOverlap || 0).toFixed(2)}% (lexical clue only)`);
    out.push(`Hash hits: ${r.hits}; verified: ${r.verified}; collisions rejected: ${r.collisions}`);
    out.push('Matching text and line numbers:');
    (r.matches || []).forEach(m => {
      out.push(`${r.a} - Line ${m.aLine}: ${m.aText}`);
      out.push(`${r.b} - Line ${m.bLine}: ${m.bText}`);
      out.push('');
    });
    out.push('');
  });
  out.push('AI-writing-style check is experimental and does not prove authorship.');
  out.push('Web search hits are possible sources and must be manually verified.');
  return out.join('\n');
}

async function exportPdf(data) {
  try {
    const body = new URLSearchParams({content: reportAsText(data)});
    const response = await fetch('/api/report', {method:'POST', credentials:'same-origin', headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'}, body});
    if (!response.ok) {
      const t = await response.text();
      let msg = t; try { msg = JSON.parse(t).error || t; } catch {}
      throw new Error(msg || `PDF export failed (HTTP ${response.status})`);
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a'); link.href = url; link.download = 'HashPlag_Report.pdf';
    document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url);
  } catch (err) { alert('Could not export report: ' + err.message); }
}

function renderWritingAnalysis(data) {
  const items = data.writingAnalysis || [];
  if (!items.length) return '';
  let html = `<section class="card result-card"><h3>AI vs human writing-style check <span class="badge mod">Experimental</span></h3><p class="small muted">These rules detect writing-style patterns only. The score is not an AI probability and cannot prove whether a human or AI wrote the document.</p>`;
  items.forEach(item => {
    const hasScore = Number(item.score) >= 0;
    html += `<div class="style-analysis"><div class="style-analysis-heading">${esc(item.name)}</div><div class="style-analysis-label">${esc(item.label)}</div>`;
    if (hasScore) html += `<p class="small">Writing-style signal score: ${Number(item.score)}/100 (not a probability)</p><div class="style-signal-bar"><div class="style-signal-fill" style="width:${Math.max(0,Math.min(100,Number(item.score)))}%"></div></div>`;
    else html += `<p class="small">No score available for this document.</p>`;
    if (item.signals && item.signals.length) html += `<ul class="style-signals">${item.signals.map(s => `<li>${esc(s)}</li>`).join('')}</ul>`;
    html += `<p class="small">${esc(item.note)}</p></div>`;
  });
  return html + '</section>';
}

function showResults(data) {
  const warnings = (data.warnings || []).length ? `<div class="warning-note">⚠ ${data.warnings.map(esc).join('<br>')}</div>` : '';
  let html = warnings + `<section class="card"><div class="result-head"><div><div class="eyebrow">SCAN COMPLETE</div><h2>Comparison report</h2><p class="small muted">${data.mode === 'code' ? 'Token-normalized code comparison' : 'Polynomial Rolling Hash + Rabin–Karp'} · window size ${data.window} characters</p></div><div class="result-actions"><button id="btnExportPdf" class="button button-primary">Download PDF report</button></div></div><h3>Document similarity matrix</h3><div class="table-wrap"><table><tr><th>Document</th>${data.documents.map(n => `<th>${esc(n)}</th>`).join('')}</tr>`;
  (data.documents || []).forEach(name => {
    html += `<tr><th>${esc(name)}</th>`;
    data.documents.forEach(other => {
      if (name === other) { html += '<td><span class="badge low">100%</span></td>'; return; }
      const result = (data.results || []).find(r => (r.a === name && r.b === other) || (r.b === name && r.a === other));
      const value = result ? Number(result.overall) : 0;
      html += `<td><span class="badge ${cls(value)}">${value.toFixed(2)}%</span></td>`;
    });
    html += '</tr>';
  });
  html += '</table></div><p class="small muted">The matrix is based on exact verified character matches. Word n-gram overlap is shown separately as an additional lexical signal.</p></section>';
  html += renderWritingAnalysis(data);

  (data.results || []).forEach(result => {
    const matches = result.matches || [];
    let matchHtml = matches.length ? '' : '<p class="small muted">No matching phrase was found at the selected window size.</p>';
    matches.forEach(match => {
      matchHtml += `<div class="match-row"><div class="match-side"><div class="match-title">${esc(result.a)} — Line ${match.aLine}</div><div class="match-text">${esc(match.aText)}</div></div><div class="match-arrow">↔</div><div class="match-side"><div class="match-title">${esc(result.b)} — Line ${match.bLine}</div><div class="match-text">${esc(match.bText)}</div></div></div>`;
    });
    const exact = Number(result.overall || 0);
    const lexical = Number(result.wordNgramOverlap || 0);
    html += `<section class="card result-card"><div class="result-head"><h3>${esc(result.a)} ↔ ${esc(result.b)}</h3><span class="badge ${cls(exact)}">${esc(result.verdict)}</span></div><div class="similarity-line"><b>Exact-match similarity: ${exact.toFixed(2)}%</b><span class="small muted">${Number(result.simA).toFixed(2)}% of ${esc(result.a)} · ${Number(result.simB).toFixed(2)}% of ${esc(result.b)}</span></div><div class="bar"><div class="fill ${cls(exact)}" style="width:${Math.max(0,Math.min(100,exact))}%"></div></div><p class="small muted">Word 3-gram overlap: ${lexical.toFixed(2)}% (shared consecutive word groups; not semantic similarity) · Hash hits: ${result.hits} · verified: ${result.verified} · collisions rejected: ${result.collisions} · short-line phrase matches: ${result.shortMatches || 0}</p><h4>Similar text and line numbers</h4><div class="match-list">${matchHtml}</div></section>`;
  });
  $('results').innerHTML = html;
  $('btnExportPdf').addEventListener('click', () => exportPdf(data));
  $('results').scrollIntoView({behavior:'smooth', block:'start'});
  const longMatch = (data.results || []).flatMap(r => r.matches || []).map(m => m.aText || '').sort((a,b) => b.length-a.length).find(t => t.length >= 12);
  if (longMatch && !$('webQuery').value.trim()) $('webQuery').value = longMatch.slice(0,180);
}

async function refreshHistory() {
  if (!currentUser) return;
  try {
    const response = await apiJson('/api/history');
    renderHistory(response.history || []);
  } catch (err) { $('historyList').innerHTML = `<p class="small">${esc(err.message)}</p>`; }
}

function renderHistory(entries) {
  const root = $('historyList');
  if (!entries.length) { root.innerHTML = '<p class="small muted">No scans saved yet. Your future comparison reports will appear here.</p>'; return; }
  root.innerHTML = '';
  entries.forEach(entry => {
    const row = document.createElement('div'); row.className = 'history-row';
    const meta = document.createElement('div'); meta.className = 'history-meta';
    const date = entry.createdAt ? new Date(entry.createdAt).toLocaleString() : 'Unknown date';
    meta.innerHTML = `<div class="history-title">${esc(entry.documents || 'Document comparison')}</div><div class="history-sub">${esc(date)} · ${entry.pairs} pair(s) · average similarity ${Number(entry.averageSimilarity || 0).toFixed(2)}% · owner ${esc(entry.owner)}</div>`;
    const actions = document.createElement('div'); actions.className = 'history-actions';
    const open = document.createElement('button'); open.className = 'button button-secondary'; open.textContent = 'Open report';
    open.addEventListener('click', async () => {
      try { latestData = await apiJson('/api/history?id=' + encodeURIComponent(entry.id)); showResults(latestData); window.scrollTo({top:0,behavior:'smooth'}); }
      catch (err) { alert(err.message); }
    });
    actions.appendChild(open);
    if (currentUser && (currentUser.role === 'ADMIN' || currentUser.username === entry.owner)) {
      const del = document.createElement('button'); del.className = 'button button-quiet'; del.textContent = 'Delete';
      del.addEventListener('click', async () => {
        if (!confirm('Delete this saved scan report?')) return;
        try {
          await apiJson('/api/history', {method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:new URLSearchParams({id:entry.id})});
          await refreshHistory();
        } catch (err) { alert(err.message); }
      });
      actions.appendChild(del);
    }
    row.append(meta, actions); root.appendChild(row);
  });
}
$('btnRefreshHistory').addEventListener('click', refreshHistory);

$('btnWebSearch').addEventListener('click', async () => {
  const query = $('webQuery').value.trim();
  if (query.length < 12) { $('webResults').innerHTML = '<p class="small">Enter a distinctive phrase of at least 12 characters.</p>'; return; }
  $('btnWebSearch').disabled = true;
  $('webResults').innerHTML = '<p class="small muted">Searching for possible public sources...</p>';
  try {
    const result = await apiJson('/api/web-search', {method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:new URLSearchParams({query})});
    let html = `<div class="warning-note">${esc(result.message || 'Verify all sources manually.')}</div>`;
    if (result.results && result.results.length) {
      result.results.forEach(item => { html += `<div class="source-result"><a href="${esc(item.url)}" target="_blank" rel="noopener noreferrer">${esc(item.title)}</a><p>${esc(item.url)}</p></div>`; });
    }
    if (result.searchUrl) html += `<div class="source-result"><a href="${esc(result.searchUrl)}" target="_blank" rel="noopener noreferrer">Open this phrase in web search</a><p>Opens the public search engine in a new tab.</p></div>`;
    $('webResults').innerHTML = html;
  } catch (err) {
    $('webResults').innerHTML = `<div class="warning-note">${esc(err.message)}</div>`;
  } finally { $('btnWebSearch').disabled = false; }
});

renderFiles();
checkSession();
