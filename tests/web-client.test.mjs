import test from 'node:test';
import assert from 'node:assert/strict';
import {deviceOrigin, childPath, parentPath, endpoint, listFiles, request, upload} from '../docs/client.mjs';

test('accepts IP, mDNS, port and a pasted CrossPoint file manager URL', () => {
  assert.equal(deviceOrigin('192.168.4.1'), 'http://192.168.4.1');
  assert.equal(deviceOrigin(' crosspoint.local:8080 '), 'http://crosspoint.local:8080');
  assert.equal(deviceOrigin('http://192.168.1.10/files?path=/Books'), 'http://192.168.1.10');
  assert.equal(deviceOrigin('https://reader.example/files'), 'https://reader.example');
  for (const value of ['', 'javascript://reader', 'file:///tmp/reader', 'http://user:pass@reader', 'http://']) assert.throws(() => deviceOrigin(value));
});
test('encodes Cyrillic, spaces and reserved characters in destination paths', () => {
  assert.equal(childPath('/', 'Книги & я'), '/Книги & я');
  assert.equal(childPath('/Книги', 'Роман.epub'), '/Книги/Роман.epub');
  assert.equal(parentPath('/Книги/Романы'), '/Книги');
  assert.equal(parentPath('/Книги'), '/');
  assert.equal(parentPath('/'), '/');
  for (const name of ['', ' ', '.', '..', 'a/b', 'a\\b', 'a\u0000b']) assert.throws(() => childPath('/', name));
  const url = new URL(endpoint('http://crosspoint.local', '/upload', {path: '/Книги & я/#1'}));
  assert.equal(url.searchParams.get('path'), '/Книги & я/#1');
  assert.equal(url.searchParams.has('overwrite'), false);
});
test('API requests use CORS, local network access and URL-encoded multipart destinations', async t => {
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(new URL(url).searchParams.get('path'), '/Книги');
    assert.equal(options.mode, 'cors');
    assert.equal(options.credentials, 'omit');
    assert.equal(options.targetAddressSpace, 'local');
    return new Response(JSON.stringify([{name:'b.epub',size:10,isDirectory:false},{name:'Книги',isDirectory:true}]));
  });
  assert.equal((await listFiles('http://crosspoint.local', '/Книги'))[0].name, 'Книги');
});
test('API HTTP failures, network errors and invalid file listings are surfaced', async t => {
  const mock = t.mock.method(globalThis, 'fetch', async () => new Response('SD card missing', {status:500}));
  await assert.rejects(request('http://crosspoint.local', '/api/files'), /HTTP 500: SD card missing/);
  mock.mock.mockImplementation(async () => { throw new TypeError('blocked'); });
  await assert.rejects(request('http://crosspoint.local', '/api/files'), /локальную сеть/);
  mock.mock.mockImplementation(async () => new Response('{"unexpected":true}'));
  await assert.rejects(listFiles('http://crosspoint.local', '/'), /неожиданный список/);
});
class FakeXHR {
  static last;
  constructor() { FakeXHR.last = this; this.upload = {}; }
  open(method, url) { this.method = method; this.url = url; }
  send(body) { this.body = body; }
  abort() { this.onabort(); }
  respond(status, text = '') { this.status = status; this.responseText = text; this.onload(); }
}
const file = Object.assign(new Blob(['book']), {name:'Книга.epub'});
test('upload waits for server confirmation even after 100% bytes sent', async t => {
  globalThis.XMLHttpRequest = FakeXHR;
  t.after(() => { delete globalThis.XMLHttpRequest; });
  let progress = 0, resolved = false;
  const promise = upload('http://crosspoint.local', '/Книги', file, new AbortController().signal, v => { progress = v; }).then(() => { resolved = true; });
  const xhr = FakeXHR.last;
  assert.equal(xhr.method, 'POST');
  assert.equal(new URL(xhr.url).searchParams.get('path'), '/Книги');
  assert.equal(xhr.body.get('file').name, 'Книга.epub');
  xhr.upload.onprogress({lengthComputable:true,loaded:100,total:100});
  await Promise.resolve();
  assert.equal(progress, 1); assert.equal(resolved, false);
  xhr.respond(200); await promise; assert.equal(resolved, true);
});
test('upload refuses to report success for existing files, connection failures or timeout', async t => {
  globalThis.XMLHttpRequest = FakeXHR;
  t.after(() => { delete globalThis.XMLHttpRequest; });
  const start = () => upload('http://crosspoint.local', '/', file, new AbortController().signal, () => {});
  let p = start(); FakeXHR.last.respond(400, 'File already exists'); await assert.rejects(p, /File already exists/);
  p = start(); FakeXHR.last.onerror(); await assert.rejects(p, /локальную сеть/);
  p = start(); FakeXHR.last.ontimeout(); await assert.rejects(p, /5 минут/);
});
test('upload aborts active transfers and never starts with an aborted signal', async t => {
  globalThis.XMLHttpRequest = FakeXHR;
  t.after(() => { delete globalThis.XMLHttpRequest; });
  const controller = new AbortController();
  const p = upload('http://crosspoint.local', '/', file, controller.signal, () => {});
  controller.abort(); await assert.rejects(p, {name:'AbortError'});
  const p2 = upload('http://crosspoint.local', '/', file, controller.signal, () => {});
  assert.equal(FakeXHR.last.body, undefined);
  await assert.rejects(p2, {name:'AbortError'});
});
