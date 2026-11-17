import {deviceOrigin, childPath, parentPath, formatSize, request, listFiles, upload} from './client.mjs';
const $ = id => document.getElementById(id);
let origin = null, path = '/', remote = [], queue = [], busy = false, transferring = false, controller;
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
  $('drop').setAttribute('aria-disabled', String(busy));
  $('send').disabled = busy || !origin || !queue.some(item => item.state === 'waiting' || item.state === 'error');
  $('send').hidden = transferring;
  $('cancel').hidden = !transferring;
  $('cancel').disabled = transferring && Boolean(controller?.signal.aborted);
  $('destination').textContent = path;
  $('folder-path').textContent = path;
}
function make(tag, className, text) {
  const node = document.createElement(tag); if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
function renderQueue() {
  $('queue').replaceChildren();
  $('queue-count').textContent = queue.length ? `${queue.length} · ${formatSize(queue.reduce((n, i) => n + i.file.size, 0))}` : 'Очередь пуста';
  for (const item of queue) {
    const row = make('li'); row.dataset.state = item.state;
    const info = make('div', 'file-info');
    info.append(make('span', 'file-name', item.file.name));
    if (item.state === 'sending') {
      const progress = make('progress'); progress.max = 1; progress.value = item.progress;
      progress.setAttribute('aria-label', `Отправка ${item.file.name}`); info.append(progress);
    }
    const labels = {waiting: 'В очереди', sending: item.progress >= 1 ? 'Ожидаем подтверждение читалки…' : `${Math.floor(item.progress * 100)}%`, done: 'На читалке', error: item.error};
    info.append(make('span', 'file-detail', labels[item.state]));
    const remove = make('button', '', '×'); remove.type = 'button'; remove.disabled = busy;
    remove.setAttribute('aria-label', `Убрать ${item.file.name}`);
    remove.onclick = () => { queue = queue.filter(i => i !== item); renderQueue(); controls(); };
    row.append(info, make('span', 'file-size', formatSize(item.file.size)), remove);
    $('queue').append(row);
  }
}
function renderRemote() {
  $('remote-files').replaceChildren();
  $('folder-status').hidden = remote.length > 0;
  $('folder-status').textContent = 'Папка пуста. Отправленные файлы появятся здесь.';
  for (const file of remote) {
    const row = make('li');
    if (file.isDirectory) {
      const button = make('button'); button.disabled = busy;
      button.append(make('span', 'folder-icon', '↳'), make('span', '', file.name));
      button.setAttribute('aria-label', `Открыть папку ${file.name}`);
      button.onclick = () => { try { openFolder(childPath(path, file.name)); } catch (e) { notice(e.message); } };
      row.append(button);
    } else {
      row.append(make('span', 'file-info file-name', file.name), make('span', 'file-size', formatSize(Math.max(0, Number(file.size) || 0))));
    }
    $('remote-files').append(row);
  }
}
function setBusy(value) { busy = value; controls(); renderQueue(); renderRemote(); }
async function openFolder(nextPath) {
  if (busy || !origin) return;
  notice(); setBusy(true);
  try { const files = await listFiles(origin, nextPath); path = nextPath; remote = files; }
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
  notice(); origin = null; path = '/'; remote = []; setBusy(true); link();
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
    setBusy(false);
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
link(); controls();
