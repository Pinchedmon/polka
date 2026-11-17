import {deviceOrigin, childPath, parentPath, formatSize, request, listFiles, upload} from './client.mjs';
const $ = id => document.getElementById(id);
let origin = null, path = '/', remote = [], queue = [], busy = false, transferring = false, connecting = false, controller;
try { $('address').value = localStorage.getItem('polka-address') || 'crosspoint.local'; } catch { /* Storage may be disabled. */ }
function notice(message = '') { $('notice').textContent = message; $('notice').hidden = !message; }
function link() {
  try { $('device-link').href = `${deviceOrigin($('address').value)}/files`; }
  catch { $('device-link').removeAttribute('href'); }
}
function controls() {
  $('connect').disabled = busy;
  $('address').disabled = busy;
  $('refresh').disabled = busy || !origin;
  $('up').disabled = busy || !origin || path === '/';
  $('new-folder').disabled = busy || !origin;
  $('hotspot').disabled = busy;
  $('files').disabled = busy;
  $('file-search').disabled = !origin;
  $('clear-done').disabled = busy;
  $('drop').setAttribute('aria-disabled', String(busy));
  $('send').disabled = busy || !origin || !queue.some(item => item.state === 'waiting' || item.state === 'error');
  $('send').hidden = transferring;
  $('cancel').hidden = !transferring;
  $('cancel').disabled = transferring && Boolean(controller?.signal.aborted);
  $('destination').textContent = path;
  renderBreadcrumbs();
  syncVisuals();
}
function make(tag, className, text) {
  const node = document.createElement(tag); if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function glyph(symbol) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.classList.add('file-glyph'); svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', `#icon-${symbol}`); svg.append(use); return svg;
}
function syncVisuals() {
  const state = connecting ? 'connecting' : transferring ? 'transferring' : origin ? 'connected' : 'offline';
  document.querySelector('.connection').dataset.state = state;
  const labels = {offline: 'Не подключена', connecting: 'Подключаем…', connected: 'На связи', transferring: 'Передаём'};
  $('connection-label').textContent = labels[state];
  const done = queue.filter(item => item.state === 'done').length;
  const captions = {offline: 'Для хороших историй', connecting: 'Почти познакомились', connected: done && done === queue.length ? 'Хорошего чтения' : 'Готова к новым историям', transferring: 'Пополняем вашу Полку'};
  $('reader-caption').textContent = captions[state];
  $('connect').replaceChildren(document.createTextNode(connecting ? 'Подключаем…' : origin ? 'Подключено ✓' : 'Подключить читалку ↗'));
  const pending = queue.filter(item => item.state === 'waiting' || item.state === 'error').length;
  $('send').textContent = pending ? `Отправить · ${pending}` : 'Отправить';
  $('clear-done').hidden = !done;
  $('overall').hidden = !transferring && !queue.some(item => item.state === 'done' || item.state === 'error');
  const total = queue.reduce((sum, item) => sum + item.file.size, 0);
  const sent = queue.reduce((sum, item) => sum + item.file.size * (item.state === 'done' ? 1 : item.state === 'sending' ? item.progress : 0), 0);
  const percent = total ? Math.floor(sent / total * 100) : queue.length ? Math.floor(done / queue.length * 100) : 0;
  $('overall-label').textContent = transferring ? 'Передаём на читалку' : `На читалке: ${done} из ${queue.length}`;
  $('overall-value').textContent = `${percent}%`;
  $('overall-fill').style.transform = `scaleX(${percent / 100})`;
  $('overall-progress').setAttribute('aria-valuenow', String(percent));
}
function renderBreadcrumbs() {
  const root = make('button', '', 'Карта'); root.type = 'button'; root.disabled = busy || !origin;
  root.onclick = () => openFolder('/');
  const nodes = [root];
  let current = '';
  for (const part of path.split('/').filter(Boolean)) {
    current += `/${part}`; const target = current;
    const separator = make('span', '', '›'); separator.setAttribute('aria-hidden', 'true');
    const button = make('button', '', part); button.type = 'button'; button.disabled = busy || !origin;
    button.onclick = () => openFolder(target); nodes.push(separator, button);
  }
  nodes.at(-1).setAttribute('aria-current', 'location');
  $('folder-path').replaceChildren(...nodes);
}
function renderQueue() {
  const container = $('queue');
  const keep = new Set(queue.map(item => item.nodes?.row));
  for (const row of [...container.children]) if (!keep.has(row)) row.remove();
  $('queue-count').textContent = queue.length ? `${queue.length} · ${formatSize(queue.reduce((n, i) => n + i.file.size, 0))}` : 'Пока пусто';
  for (const item of queue) {
    if (!item.nodes) {
      const row = make('li'), icon = glyph('book'), info = make('div', 'file-info');
      const progress = make('progress'); progress.max = 1;
      progress.setAttribute('aria-label', `Отправка ${item.file.name}`);
      const detail = make('span', 'file-detail');
      info.append(make('span', 'file-name', item.file.name), progress, detail);
      const remove = make('button', '', '×'); remove.type = 'button';
      remove.setAttribute('aria-label', `Убрать ${item.file.name}`);
      remove.onclick = () => { queue = queue.filter(i => i !== item); renderQueue(); controls(); };
      row.append(icon, info, make('span', 'file-size', formatSize(item.file.size)), remove);
      item.nodes = {row, icon, progress, detail, remove}; container.append(row);
    }
    const {row, icon, progress, detail, remove} = item.nodes;
    row.dataset.state = item.state;
    progress.hidden = item.state !== 'sending'; progress.value = item.progress;
    const labels = {waiting: 'В очереди', sending: item.progress >= 1 ? 'Сохраняем на читалке…' : `${Math.floor(item.progress * 100)}% · отправляем`, done: 'Уже на читалке', error: item.error};
    detail.textContent = labels[item.state]; remove.disabled = busy;
    icon.firstChild.setAttribute('href', item.state === 'done' ? '#icon-check' : '#icon-book');
  }
  syncVisuals();
}
function renderRemote() {
  $('remote-files').replaceChildren();
  const query = $('file-search').value.trim().toLocaleLowerCase('ru');
  const filtered = remote.filter(file => file.name.toLocaleLowerCase('ru').includes(query));
  $('folder-status').hidden = filtered.length > 0;
  $('shelf-illustration').hidden = filtered.length > 0;
  $('folder-status').textContent = !origin
    ? 'Здесь появятся книги и папки. Подключите читалку, чтобы заглянуть на её Полку.'
    : query && !filtered.length ? 'По этому запросу ничего нет. Попробуйте другое название.' : 'В этой папке пока тихо. Самое время добавить хорошую книгу.';
  for (const file of filtered) {
    const row = make('li');
    if (file.isDirectory) {
      const button = make('button'); button.disabled = busy;
      button.append(glyph('folder'), make('span', '', file.name));
      button.setAttribute('aria-label', `Открыть папку ${file.name}`);
      button.onclick = () => { try { openFolder(childPath(path, file.name)); } catch (e) { notice(e.message); } };
      row.append(button);
    } else {
      row.append(glyph('book'), make('span', 'file-info file-name', file.name), make('span', 'file-size', formatSize(Math.max(0, Number(file.size) || 0))));
    }
    $('remote-files').append(row);
  }
}
function setBusy(value) { busy = value; controls(); renderQueue(); renderRemote(); }
async function openFolder(nextPath) {
  if (busy || !origin) return;
  notice(); setBusy(true);
  try { const files = await listFiles(origin, nextPath); path = nextPath; remote = files; $('file-search').value = ''; }
  catch (error) { notice(`Не удалось открыть папку. ${error.message}`); }
  finally { setBusy(false); }
}
$('address').addEventListener('input', () => {
  origin = null; path = '/'; remote = []; controls(); renderRemote();
  $('folder-status').textContent = 'Подключитесь к читалке по новому адресу.';
  $('device-status').textContent = 'Адрес изменён. Нажмите «Подключить».'; link(); notice();
});
$('hotspot').onclick = () => { $('address').value = '192.168.4.1'; $('address').dispatchEvent(new Event('input')); $('address').focus(); };
$('connect-form').onsubmit = async event => {
  event.preventDefault(); if (busy) return;
  let target;
  try { target = deviceOrigin($('address').value); } catch (error) { notice(error.message); return; }
  notice(); connecting = true; $('file-search').value = ''; origin = null; path = '/'; remote = []; setBusy(true); link();
  $('device-status').textContent = 'Подключаемся… Разрешите браузеру доступ к локальной сети.';
  try {
    const status = await (await request(target, '/api/status')).json();
    if (!status || typeof status.version !== 'string') throw new Error('По этому адресу не найден API CrossPoint.');
    const files = await listFiles(target, '/');
    origin = target; remote = files;
    $('device-status').textContent = `${status.device || 'Xteink'} · ${status.version} · подключено`;
    try { localStorage.setItem('polka-address', target); } catch { /* Optional preference. */ }
  } catch (error) { $('device-status').textContent = 'Нет подключения'; notice(error.message); }
  finally {
    connecting = false; setBusy(false);
    if (!origin) $('folder-status').textContent = 'Не удалось подключиться. Проверьте адрес и инструкцию ниже.';
  }
};
function addFiles(files) {
  if (busy) return;
  let invalid = false;
  for (const file of files) {
    try { childPath('/', file.name); } catch { invalid = true; continue; }
    if (queue.some(i => i.file.name === file.name && i.file.size === file.size && i.file.lastModified === file.lastModified && i.state !== 'done')) continue;
    queue.push({file, state: 'waiting', progress: 0, error: ''});
  }
  if (invalid) notice('Некоторые имена файлов содержат недопустимые символы. Переименуйте файлы и добавьте снова.');
  renderQueue(); controls();
}
$('files').onchange = event => { addFiles(event.target.files); event.target.value = ''; };
for (const name of ['dragenter', 'dragover']) $('drop').addEventListener(name, event => { event.preventDefault(); if (!busy) $('drop').classList.add('dragover'); });
for (const name of ['dragleave', 'drop']) $('drop').addEventListener(name, event => { event.preventDefault(); $('drop').classList.remove('dragover'); });
$('drop').addEventListener('drop', event => addFiles(event.dataTransfer.files));
window.addEventListener('dragover', event => event.preventDefault());
window.addEventListener('drop', event => event.preventDefault());
$('file-search').addEventListener('input', renderRemote);
$('clear-done').onclick = () => { if (busy) return; queue = queue.filter(item => item.state !== 'done'); renderQueue(); controls(); };
$('refresh').onclick = () => openFolder(path);
$('up').onclick = () => openFolder(parentPath(path));
$('send').onclick = async () => {
  if (busy || !origin) return;
  const target = origin, destination = path;
  const pending = queue.filter(item => item.state === 'waiting' || item.state === 'error');
  if (!pending.length) return;
  notice(); transferring = true; controller = new AbortController(); setBusy(true);
  try {
    // Refuse collisions before sending bytes; the firmware also refuses overwrite by default.
    const existing = new Set((await listFiles(target, destination)).map(file => file.name));
    for (const item of pending) {
      if (controller.signal.aborted) break;
      item.error = ''; item.progress = 0;
      if (existing.has(item.file.name)) {
        item.state = 'error'; item.error = 'Такое имя уже есть в папке. Переименуйте файл или выберите другую папку.'; renderQueue(); continue;
      }
      item.state = 'sending'; renderQueue();
      $('transfer-status').textContent = `Отправляем ${item.file.name} в ${destination}`;
      try {
        await upload(target, destination, item.file, controller.signal, value => { item.progress = value; renderQueue(); });
        item.state = 'done'; existing.add(item.file.name);
      } catch (error) {
        item.state = 'error';
        item.error = error.name === 'AbortError' ? 'Остановлено. На карте может остаться неполный файл.' : error.message;
        // On transport errors the server may still be writing. Require a deliberate retry.
        notice(item.error); break;
      }
      renderQueue();
    }
    if (!controller.signal.aborted) {
      try { remote = await listFiles(target, destination); }
      catch (error) { notice(`Передача завершена, но список файлов обновить не удалось. ${error.message}`); }
    }
  } catch (error) { notice(error.message); }
  finally {
    const stopped = controller.signal.aborted;
    transferring = false; setBusy(false);
    $('transfer-status').textContent = stopped ? 'Передача остановлена. Перед повторной отправкой проверьте файл на читалке.' : `Отправлено: ${pending.filter(i => i.state === 'done').length} из ${pending.length}.`;
  }
};
$('cancel').onclick = () => { if (controller) controller.abort(); $('cancel').disabled = true; $('transfer-status').textContent = 'Останавливаем передачу…'; };
$('new-folder').onclick = () => {
  if (busy || !origin) return;
  $('folder-name').value = ''; $('folder-error').textContent = ''; $('folder-dialog').showModal();
};
$('folder-cancel').onclick = () => $('folder-dialog').close();
$('folder-form').onsubmit = async event => {
  event.preventDefault(); if (busy || !origin) return;
  const name = $('folder-name').value.trim();
  try { childPath(path, name); } catch (error) { $('folder-error').textContent = error.message; return; }
  setBusy(true); $('folder-create').disabled = true; $('folder-cancel').disabled = true;
  try {
    await request(origin, '/mkdir', {method: 'POST', body: new URLSearchParams({path, name})});
    $('folder-dialog').close();
    remote = await listFiles(origin, path); notice();
  } catch (error) { $('folder-error').textContent = error.message; if (!$('folder-dialog').open) notice(error.message); }
  finally { setBusy(false); $('folder-create').disabled = false; $('folder-cancel').disabled = false; }
};
window.addEventListener('beforeunload', event => { if (transferring) { event.preventDefault(); event.returnValue = ''; } });
link(); controls(); renderRemote();
