export function dimensions(width, height) {
  if (![width, height].every(n => Number.isInteger(n) && n >= 64 && n <= 2048)) throw new Error('Размер каждой стороны — целое число от 64 до 2048 пикселей.');
  return {width, height};
}
export function framing(sw, sh, width, height, rotation = 0, fill = true) {
  const [w, h] = rotation % 180 ? [sh, sw] : [sw, sh];
  return {scale: (fill ? Math.max : Math.min)(width / w, height / h), x: width / 2, y: height / 2, rotation};
}
export function grayscale(data) {
  for (let i = 0; i < data.length; i += 4) {
    const gray = Math.round(data[i] * .2126 + data[i + 1] * .7152 + data[i + 2] * .0722);
    data[i] = data[i + 1] = data[i + 2] = gray;
  }
  return data;
}
// Windows BITMAPINFOHEADER, uncompressed 24-bit BGR, padded bottom-up rows.
export function encodeBmp({width, height, data}) {
  const stride = Math.ceil(width * 3 / 4) * 4;
  const size = stride * height;
  const buffer = new ArrayBuffer(54 + size), view = new DataView(buffer);
  view.setUint16(0, 0x4d42, true); view.setUint32(2, buffer.byteLength, true);
  view.setUint32(10, 54, true); view.setUint32(14, 40, true);
  view.setInt32(18, width, true); view.setInt32(22, height, true);
  view.setUint16(26, 1, true); view.setUint16(28, 24, true);
  view.setUint32(34, size, true); view.setInt32(38, 2835, true); view.setInt32(42, 2835, true);
  const out = new Uint8Array(buffer);
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const source = ((height - y - 1) * width + x) * 4, target = 54 + y * stride + x * 3;
    out[target] = data[source + 2]; out[target + 1] = data[source + 1]; out[target + 2] = data[source];
  }
  return buffer;
}
