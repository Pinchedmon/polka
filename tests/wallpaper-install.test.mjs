import test from 'node:test';
import assert from 'node:assert/strict';
import {verifyWallpaper, pinWallpaper, enableCustomWallpaper, clearWallpaperCache} from '../docs/wallpaper-install.mjs';
const origin = 'http://crosspoint.local';
const file = Object.assign(new Blob(['new BMP']), {name: 'sleep.bmp'});
const signal = () => new AbortController().signal;

test('HTTP 200 with the old bitmap is rejected; exact read-back is accepted', async t => {
  const mock = t.mock.method(globalThis, 'fetch', async () => new Response('old BMP'));
  await assert.rejects(verifyWallpaper(origin, '/sleep.bmp', file, signal()), /отличается/);
  mock.mock.mockImplementation(async url => {
    assert.equal(new URL(url).searchParams.get('path'), '/sleep.bmp');
    assert.ok(new URL(url).searchParams.has('_polka'));
    return new Response('new BMP');
  });
  await verifyWallpaper(origin, '/sleep.bmp', file, signal());
});

test('HTTP 200 applying zero settings does not count as activated Custom mode', async t => {
  let value = 1;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (options.method === 'POST') return new Response('Applied 0 setting(s)');
    return new Response(JSON.stringify([{key:'sleepScreen', value}]));
  });
  await assert.rejects(enableCustomWallpaper(origin, signal()), /не подтвердила/);
  value = 2; await enableCustomWallpaper(origin, signal());
});

test('cache cleanup deletes only generated sleep bitmaps, preserving art, books and settings', async t => {
  const removed = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (new URL(url).pathname === '/api/files') return new Response(JSON.stringify([
      {name:'sleep_123.bmp',isDirectory:false}, {name:'sleep_456.bmp',isDirectory:true},
      {name:'settings.json',isDirectory:false}, {name:'cover.bmp',isDirectory:false},
      {name:'sleep.bmp',isDirectory:false}, {name:'sleep_photo.bmp',isDirectory:false},
    ]));
    removed.push(options.body.get('path')); return new Response('Deleted');
  });
  await clearWallpaperCache(origin, signal());
  assert.deepEqual(removed, ['/.crosspoint/sleep_123.bmp']);
});

function fakeUpload(t, replies, urls) {
  globalThis.XMLHttpRequest = class {
    constructor() { this.upload = {}; }
    open(method,url) { this.url = url; urls.push(url); }
    send() { queueMicrotask(() => { const [code, text] = replies.shift(); this.status=code; this.responseText=text; this.onload(); }); }
  };
  t.after(() => { delete globalThis.XMLHttpRequest; });
}
test('legacy explicit collision deletes only /sleep.bmp, retries and verifies the new bitmap', async t => {
  const urls = [], deleted = [];
  fakeUpload(t, [[400,'File already exists: sleep.bmp'],[200,'Uploaded']], urls);
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (new URL(url).pathname === '/delete') { deleted.push(options.body.get('path')); return new Response('Deleted'); }
    return new Response('new BMP');
  });
  await pinWallpaper(origin, file, signal(), () => {});
  assert.deepEqual(deleted, ['/sleep.bmp']); assert.equal(urls.length,2);
  assert.equal(new URL(urls[0]).searchParams.get('overwrite'),'1');
  assert.equal(new URL(urls[1]).searchParams.has('overwrite'),false);
});
test('other upload failures never trigger deletion of the old wallpaper', async t => {
  fakeUpload(t, [[400,'SD card write failed']], []);
  const fetch = t.mock.method(globalThis, 'fetch', async () => { throw new Error('Must not delete'); });
  await assert.rejects(pinWallpaper(origin, file, signal(), () => {}), /SD card write failed/);
  assert.equal(fetch.mock.callCount(),0);
});
test('cancelling before installation prevents file writes', async t => {
  const controller = new AbortController(); controller.abort();
  const fetch = t.mock.method(globalThis, 'fetch', async () => { throw new Error('Must not write'); });
  await assert.rejects(clearWallpaperCache(origin, controller.signal), {name:'AbortError'});
  await assert.rejects(enableCustomWallpaper(origin, controller.signal), {name:'AbortError'});
  assert.equal(fetch.mock.callCount(),0);
});
