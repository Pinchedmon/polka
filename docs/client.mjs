export function deviceOrigin(value) {
  const text = value.trim();
  if (!text) throw new Error('Введите адрес с экрана читалки.');
  let url;
  try { url = new URL(/^[a-z][a-z\d+.-]*:\/\//i.test(text) ? text : `http://${text}`); }
  catch { throw new Error('Не удалось разобрать адрес. Пример: 192.168.1.100'); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || !url.hostname) {
    throw new Error('Нужен HTTP-адрес читалки без логина и пароля.');
  }
  return url.origin;
}
export function childPath(parent, name) {
  if (!name.trim() || /[/\\\x00-\x1f\x7f]/.test(name) || name === '.' || name === '..') {
    throw new Error('Название не должно содержать слеши, управляющие символы или состоять из точек.');
  }
  return `${parent === '/' ? '' : parent}/${name}`;
}
export function parentPath(path) { return path.slice(0, path.lastIndexOf('/')) || '/'; }
export function formatSize(bytes) {
  if (!bytes) return '0 Б';
  const units = ['Б', 'КБ', 'МБ', 'ГБ'];
  const n = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${new Intl.NumberFormat('ru', {maximumFractionDigits: n ? 1 : 0}).format(bytes / 1024 ** n)} ${units[n]}`;
}
export function endpoint(origin, route, params = {}) {
  const url = new URL(route, `${origin}/`);
  for (const [key, value] of Object.entries(params)) url.searchParams.set(key, value);
  return url.href;
}
export const connectionHelp = 'Проверьте File Transfer, общую Wi-Fi сеть и разрешение браузера на локальную сеть. Для GitHub Pages нужна прошивка с CORS. Если браузер блокирует запрос, откройте встроенный интерфейс читалки по ссылке в инструкции.';
export async function request(origin, route, {params, ...options} = {}) {
  let response;
  try {
    response = await fetch(endpoint(origin, route, params), {
      mode: 'cors', credentials: 'omit', cache: 'no-store',
      targetAddressSpace: 'local', signal: AbortSignal.timeout(15000), ...options,
    });
  } catch { throw new Error(connectionHelp); }
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${(await response.text()).slice(0, 250)}`);
  return response;
}
export async function listFiles(origin, path) {
  const data = await (await request(origin, '/api/files', {params: {path}})).json();
  if (!Array.isArray(data) || data.some(f => !f || typeof f.name !== 'string' || typeof f.isDirectory !== 'boolean')) {
    throw new Error('Читалка вернула неожиданный список файлов.');
  }
  return data.sort((a, b) => Number(b.isDirectory) - Number(a.isDirectory) || a.name.localeCompare(b.name, 'ru'));
}
export function upload(origin, path, file, signal, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    const finish = (error) => {
      signal.removeEventListener('abort', abort);
      error ? reject(error) : resolve();
    };
    xhr.open('POST', endpoint(origin, '/upload', {path}));
    xhr.timeout = 300000;
    xhr.upload.onprogress = event => { if (event.lengthComputable) onProgress(event.loaded / event.total); };
    xhr.onload = () => finish(xhr.status >= 200 && xhr.status < 300 ? null : new Error(`HTTP ${xhr.status}: ${xhr.responseText.slice(0, 250)}`));
    xhr.onerror = () => finish(new Error(connectionHelp));
    xhr.ontimeout = () => finish(new Error('Читалка не ответила за 5 минут. Проверьте файл на устройстве перед повторной отправкой.'));
    xhr.onabort = () => finish(new DOMException('Передача остановлена', 'AbortError'));
    signal.addEventListener('abort', abort, {once: true});
    if (signal.aborted) { signal.removeEventListener('abort', abort); reject(new DOMException('Передача остановлена', 'AbortError')); return; }
    const form = new FormData();
    form.append('file', file, file.name);
    xhr.send(form);
  });
}
