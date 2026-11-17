import test from 'node:test';
import assert from 'node:assert/strict';
import {dimensions, framing, grayscale, encodeBmp} from '../docs/wallpaper-image.mjs';

test('screen dimensions accept X4 and custom integer sizes, reject unsafe canvas sizes', () => {
  assert.deepEqual(dimensions(480, 800), {width: 480, height: 800});
  assert.deepEqual(dimensions(801, 601), {width: 801, height: 601});
  for (const [w, h] of [[0, 800], [480, NaN], [480.5, 800], [480, 99999], [63, 64]]) assert.throws(() => dimensions(w, h));
});
test('fill crops a landscape photo; fit preserves all of it; rotation changes aspect', () => {
  assert.deepEqual(framing(1200, 800, 480, 800), {scale: 1, x: 240, y: 400, rotation: 0});
  assert.equal(framing(1200, 800, 480, 800, 0, false).scale, .4);
  assert.equal(framing(1200, 800, 480, 800, 90, true).scale, 2 / 3);
});
test('e-ink grayscale uses luminance and keeps alpha intact', () => {
  const pixels = new Uint8ClampedArray([255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 255, 255]);
  grayscale(pixels);
  assert.deepEqual([...pixels], [54,54,54,255,182,182,182,255,18,18,18,255,255,255,255,255]);
});
test('BMP has valid 24-bit headers, BGR channels, bottom-up rows and zero padding', () => {
  const bmp = encodeBmp({width: 1, height: 2, data: new Uint8ClampedArray([255,0,0,255,0,0,255,255])});
  const v = new DataView(bmp), bytes = new Uint8Array(bmp);
  assert.equal(v.getUint16(0,true), 0x4d42);
  assert.equal(v.getUint32(2,true), 62);
  assert.equal(v.getUint32(10,true), 54);
  assert.equal(v.getInt32(18,true), 1); assert.equal(v.getInt32(22,true), 2);
  assert.equal(v.getUint16(28,true), 24); assert.equal(v.getUint32(30,true), 0);
  assert.equal(v.getUint32(34,true), 8);
  assert.deepEqual([...bytes.slice(54)], [255,0,0,0,0,0,255,0]);
});
test('X4 BMP size matches mobile Polka output exactly', () => {
  const bmp = encodeBmp({width:480,height:800,data:new Uint8ClampedArray(480*800*4)});
  assert.equal(bmp.byteLength, 1152054);
});
