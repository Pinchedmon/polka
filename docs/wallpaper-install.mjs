import {request, listFiles, upload} from './client.mjs?v=20261117-4';

export async function verifyWallpaper(origin, path, file, signal) {
  signal.throwIfAborted();
  const response = await request(origin, '/download', {params: {path, _polka: Date.now()}, signal});
  const actual = new Uint8Array(await response.arrayBuffer());
  const expected = new Uint8Array(await file.arrayBuffer());
  signal.throwIfAborted();
  if (actual.length !== expected.length || actual.some((byte, i) => byte !== expected[i])) {
    throw new Error(`Читалка не подтвердила новую картинку в ${path}. Прочитанный файл отличается от отправленного.`);
  }
}

export async function pinWallpaper(origin, file, signal, onProgress) {
  try {
    await upload(origin, '/', file, signal, onProgress, {overwrite: true});
  } catch (error) {
    // Older firmware refuses overwrite. Match the mobile app's delete-then-upload
    // behavior only on an explicit filename collision, never on a network failure.
    if (!/^HTTP 400: File already exists:\s*sleep\.bmp\s*$/i.test(error.message)) throw error;
    signal.throwIfAborted();
    await request(origin, '/delete', {method: 'POST', body: new URLSearchParams({path: '/sleep.bmp'}), signal});
    signal.throwIfAborted();
    await upload(origin, '/', file, signal, onProgress);
  }
  await verifyWallpaper(origin, '/sleep.bmp', file, signal);
}

export async function enableCustomWallpaper(origin, signal) {
  signal.throwIfAborted();
  await request(origin, '/api/settings', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({sleepScreen: 2}), signal});
  const settings = await (await request(origin, '/api/settings', {signal})).json();
  const current = Array.isArray(settings) ? settings.find(setting => setting.key === 'sleepScreen') : null;
  if (!current || Number(current.value) !== 2) throw new Error('Прошивка не подтвердила режим «Своя картинка / Custom». Включите его на читалке вручную.');
}

export async function clearWallpaperCache(origin, signal) {
  signal.throwIfAborted();
  const entries = await listFiles(origin, '/.crosspoint');
  for (const entry of entries) {
    // CrossPoint's derived sleep bitmap cache only. Preserve source art, book
    // covers, settings and credentials. Older firmware may not have this cache.
    if (entry.isDirectory || !/^sleep_\d+\.bmp$/.test(entry.name)) continue;
    signal.throwIfAborted();
    await request(origin, '/delete', {method: 'POST', body: new URLSearchParams({path: `/.crosspoint/${entry.name}`}), signal});
  }
}
