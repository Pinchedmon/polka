import {verifyWallpaper, pinWallpaper, enableCustomWallpaper, clearWallpaperCache} from './wallpaper-install.mjs?v=20261117-4';
import {request, listFiles, upload} from './client.mjs?v=20261117-4';
import {dimensions, framing, grayscale, encodeBmp} from './wallpaper-image.mjs';

export function initWallpaperEditor({getOrigin, isBusy, setBusy, onSaved}) {
  const $ = id => document.getElementById(id);
  const canvas = $('wp-canvas'), ctx = canvas.getContext('2d', {willReadFrequently: true});
  let image = null, sourceName = '', frame, background = 'white', validSize = true;
  let sending = false, loading = false, decodeId = 0, animation = 0, controller;
  const pointers = new Map();
  const status = message => { $('wp-status').textContent = message; };
  function sync() {
    const locked = isBusy() || sending || loading;
    $('wp-tools').disabled = !image || locked;
    $('wp-file').disabled = locked;
    $('wp-pin').disabled = locked;
    $('wp-send').disabled = !image || locked || !getOrigin() || !validSize;
    $('wp-download').disabled = !image || locked || !validSize;
    $('wp-stop').hidden = !sending;
    $('wp-stop').disabled = Boolean(controller?.signal.aborted);
    canvas.setAttribute('aria-disabled', String(!image || locked));
  }
  function render() {
    canvas.parentElement.style.width = `${Math.min(240, 400 * canvas.width / canvas.height) + 24}px`;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = background; ctx.fillRect(0, 0, canvas.width, canvas.height);
    if (image) {
      ctx.save(); ctx.translate(frame.x, frame.y); ctx.rotate(frame.rotation * Math.PI / 180);
      ctx.scale(frame.scale, frame.scale); ctx.drawImage(image, -image.naturalWidth / 2, -image.naturalHeight / 2); ctx.restore();
      const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height);
      grayscale(pixels.data); ctx.putImageData(pixels, 0, 0);
    } else {
      ctx.fillStyle = '#8a7f93'; ctx.textAlign = 'center'; ctx.font = '600 30px Unbounded, Nunito, sans-serif';
      ctx.fillText('Ваша', canvas.width / 2, canvas.height * .45);
      ctx.fillText('история', canvas.width / 2, canvas.height * .45 + 42);
      ctx.font = '600 16px Nunito, sans-serif'; ctx.fillText('начинается с картинки', canvas.width / 2, canvas.height * .65);
    }
    $('wp-preview-caption').textContent = `${canvas.width} × ${canvas.height} · предпросмотр e-ink`;
    if (frame) {
      const fit = framing(image.naturalWidth, image.naturalHeight, canvas.width, canvas.height, frame.rotation, false).scale;
      $('wp-zoom').max = Math.ceil(Math.max(1200, frame.scale / fit * 100));
      $('wp-zoom').value = Math.round(frame.scale / fit * 100);
      $('wp-zoom-value').value = `${Math.round(frame.scale / fit * 100)}%`;
    }
  }
  function redraw() {
    if (!animation) animation = requestAnimationFrame(() => { animation = 0; render(); });
  }
  function preset(fill, rotation = frame?.rotation || 0) {
    if (!image) return;
    frame = framing(image.naturalWidth, image.naturalHeight, canvas.width, canvas.height, rotation, fill);
    $('wp-fill').setAttribute('aria-pressed', String(fill)); $('wp-fit').setAttribute('aria-pressed', String(!fill)); redraw();
  }
  function sizeChanged() {
    try {
      const {width, height} = dimensions($('wp-width').valueAsNumber, $('wp-height').valueAsNumber);
      validSize = true; canvas.width = width; canvas.height = height;
      preset(true); status('Размер обновлён. Проверьте кадрирование перед отправкой.');
    } catch (error) { validSize = false; status(error.message); }
    sync();
  }
  $('wp-width').addEventListener('change', sizeChanged); $('wp-height').addEventListener('change', sizeChanged);
  $('wp-default-size').onclick = () => { $('wp-width').value = 480; $('wp-height').value = 800; sizeChanged(); };
  $('wp-fill').onclick = () => preset(true); $('wp-fit').onclick = () => preset(false);
  $('wp-rotate').onclick = () => preset(true, (frame.rotation + 90) % 360);
  $('wp-reset').onclick = () => { background = 'white'; document.querySelector('input[name="wp-background"][value="white"]').checked = true; preset(true, 0); };
  for (const radio of document.querySelectorAll('input[name="wp-background"]')) radio.onchange = () => { background = radio.value; redraw(); };
  function zoom(factor, anchor = {x: canvas.width / 2, y: canvas.height / 2}) {
    const fit = framing(image.naturalWidth, image.naturalHeight, canvas.width, canvas.height, frame.rotation, false).scale;
    const fill = framing(image.naturalWidth, image.naturalHeight, canvas.width, canvas.height, frame.rotation, true).scale;
    const scale = Math.max(fit * .3, Math.min(Math.max(fill, fit * 12), frame.scale * factor));
    const ratio = scale / frame.scale;
    frame.x = anchor.x + (frame.x - anchor.x) * ratio; frame.y = anchor.y + (frame.y - anchor.y) * ratio; frame.scale = scale;
    $('wp-fill').setAttribute('aria-pressed', 'false'); $('wp-fit').setAttribute('aria-pressed', 'false'); redraw();
  }
  $('wp-zoom').oninput = () => {
    const fit = framing(image.naturalWidth, image.naturalHeight, canvas.width, canvas.height, frame.rotation, false).scale;
    zoom((Number($('wp-zoom').value) / 100 * fit) / frame.scale);
  };
  function point(event) {
    const rect = canvas.getBoundingClientRect();
    return {x: (event.clientX - rect.left) * canvas.width / rect.width, y: (event.clientY - rect.top) * canvas.height / rect.height};
  }
  function gesture() {
    const points = [...pointers.values()].slice(0, 2);
    const center = {x: points.reduce((s, p) => s + p.x, 0) / points.length, y: points.reduce((s, p) => s + p.y, 0) / points.length};
    return {center, distance: points.length === 2 ? Math.hypot(points[0].x - points[1].x, points[0].y - points[1].y) : 0};
  }
  canvas.addEventListener('pointerdown', event => {
    if (!image || isBusy() || loading || event.button > 0) return;
    pointers.set(event.pointerId, point(event)); canvas.setPointerCapture(event.pointerId);
  });
  canvas.addEventListener('pointermove', event => {
    if (!pointers.has(event.pointerId) || isBusy()) return;
    const before = gesture(); pointers.set(event.pointerId, point(event)); const after = gesture();
    frame.x += after.center.x - before.center.x; frame.y += after.center.y - before.center.y;
    if (before.distance > 0 && after.distance > 0) zoom(after.distance / before.distance, after.center);
    redraw();
  });
  for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) canvas.addEventListener(type, event => pointers.delete(event.pointerId));
  canvas.addEventListener('wheel', event => { if (!image || isBusy() || loading) return; event.preventDefault(); zoom(Math.exp(-event.deltaY * .001), point(event)); }, {passive: false});
  canvas.addEventListener('keydown', event => {
    if (!image || isBusy() || loading) return;
    const amount = event.shiftKey ? 10 : 1;
    const moves = {ArrowLeft: [-amount, 0], ArrowRight: [amount, 0], ArrowUp: [0, -amount], ArrowDown: [0, amount]};
    if (moves[event.key]) { event.preventDefault(); frame.x += moves[event.key][0]; frame.y += moves[event.key][1]; redraw(); }
    if (event.key === '+' || event.key === '=') { event.preventDefault(); zoom(1.05); }
    if (event.key === '-') { event.preventDefault(); zoom(1 / 1.05); }
  });
  $('wp-file').onchange = async event => {
    const file = event.target.files[0]; event.target.value = ''; if (!file || isBusy()) return;
    const id = ++decodeId; loading = true; sync(); status('Открываем картинку…');
    const url = URL.createObjectURL(file);
    try {
      const next = new Image(); next.src = url; await next.decode();
      if (id !== decodeId) return;
      if (!next.naturalWidth || next.naturalWidth * next.naturalHeight > 80000000) throw new Error('Картинка слишком большая. Уменьшите её до 80 мегапикселей.');
      image = next; sourceName = file.name;
      $('wp-source').textContent = `${file.name} · ${next.naturalWidth} × ${next.naturalHeight}`;
      pointers.clear(); preset(true, 0); status('Подберите кадр. Предпросмотр и готовый BMP совпадают.');
    } catch { status('Не удалось открыть изображение. Попробуйте JPEG, PNG или WebP меньшего размера.'); }
    finally { URL.revokeObjectURL(url); if (id === decodeId) { loading = false; sync(); } }
  };
  function bmpFile(name) {
    render();
    return new File([encodeBmp(ctx.getImageData(0, 0, canvas.width, canvas.height))], name, {type: 'image/bmp'});
  }
  function baseName() { return sourceName.replace(/\.[^.]*$/, '').replace(/[/\\\x00-\x1f\x7f]/g, '_').slice(0, 40).trim() || 'wallpaper'; }
  $('wp-download').onclick = () => {
    if (!image || !validSize || isBusy()) return;
    const url = URL.createObjectURL(bmpFile(`${baseName()}.bmp`));
    const a = document.createElement('a'); a.href = url; a.download = `${baseName()}.bmp`; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 30000); status(`BMP ${canvas.width} × ${canvas.height} готов к скачиванию.`);
  };
  $('wp-send').onclick = async () => {
    if (!image || !validSize || isBusy() || !getOrigin()) return;
    const origin = getOrigin(), pin = $('wp-pin').checked;
    const file = bmpFile(`${baseName()}.bmp`);
    controller = new AbortController(); sending = true; pointers.clear(); setBusy(true); sync();
    let saved = false, pinned = false;
    const check = () => controller.signal.throwIfAborted();
    try {
      status('Проверяем папку заставок…');
      const root = await listFiles(origin, '/'); check();
      const sleep = root.find(entry => entry.name.toLowerCase() === 'sleep');
      if (sleep && !sleep.isDirectory) throw new Error('/sleep уже существует как файл. Нужна папка с этим именем.');
      if (!sleep) { await request(origin, '/mkdir', {method: 'POST', body: new URLSearchParams({path: '/', name: 'sleep'})}); check(); }
      const directory = sleep ? `/${sleep.name}` : '/sleep';
      const entries = await listFiles(origin, directory); check();
      const taken = new Set(entries.map(entry => entry.name.toLowerCase()));
      let name = file.name, n = 2;
      while (taken.has(name.toLowerCase())) name = `${baseName()}-${n++}.bmp`;
      const gallery = new File([file], name, {type: 'image/bmp'});
      await upload(origin, directory, gallery, controller.signal, value => status(`Сохраняем в ${directory}: ${Math.floor(value * 100)}%`)); check();
      status('Проверяем сохранённую картинку…');
      await verifyWallpaper(origin, `${directory}/${name}`, gallery, controller.signal); saved = true; check();
      if (pin) {
        await pinWallpaper(origin, new File([file], 'sleep.bmp', {type: 'image/bmp'}), controller.signal, value => status(`Устанавливаем заставку: ${Math.floor(value * 100)}%`)); pinned = true; check();
      }
      status('Проверяем режим заставки…');
      await enableCustomWallpaper(origin, controller.signal); check();
      let cacheWarning = '';
      try { await clearWallpaperCache(origin, controller.signal); check(); }
      catch (error) {
        if (error.name === 'AbortError') throw error;
        cacheWarning = ' Кэш заставок сбросить не удалось. Если остаётся старая картинка, требуется проверка версии прошивки.';
      }
      status((pin ? 'Новая /sleep.bmp прочитана обратно и проверена, режим Custom включён. Выйдите из File Transfer и снова переведите читалку в сон.' : `Заставка добавлена в ${directory}. Закреплённая /sleep.bmp, если она есть, остаётся приоритетной.`) + cacheWarning);
    } catch (error) {
      const partial = pinned ? 'Файлы заставки сохранены. ' : saved ? 'Картинка сохранена в коллекции, но не закреплена. ' : '';
      status(partial + (error.name === 'AbortError' ? 'Передача остановлена. На карте может остаться неполный файл; проверьте его перед повтором.' : error.message));
    } finally {
      sending = false; setBusy(false); sync();
      if (saved) await onSaved();
    }
  };
  $('wp-stop').onclick = () => { controller?.abort(); status('Останавливаем передачу…'); sync(); };
  render(); sync();
  return {sync, isSending: () => sending};
}
