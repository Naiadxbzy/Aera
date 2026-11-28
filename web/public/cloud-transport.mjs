const encoder = new TextEncoder();
const payloadLimit = 96 * 1024 * 1024;

export function hostedJobBody(input) {
  const payloads = [], references = [];
  function append(value, format, bytes) {
    const blob = new Blob([format === 'string' ? JSON.stringify(value).slice(1, -1) : value]);
    const id = references.length;
    payloads.push(blob);
    references.push({ size: blob.size, format, ...(format === 'string' && { bytes }) });
    return id;
  }

  const last = input.messages.findLast(message => message.role === 'user');
  const query = last?.parts.filter(part => part.type === 'text')
    .map(part => part.text).join('\n').trim().slice(0, 400) ?? '';
  const messages = input.messages.map(message => {
    const mapped = { role: message.role, parts: message.parts.map(part => {
      if (part.type === 'text') {
        if (part.text.length < 1024) return { type: 'text', text: part.text };
        return { type: 'text', textRef: append(part.text, 'string', encoder.encode(part.text).byteLength) };
      }
      return { type: part.type, mime: part.mime, name: part.name, fileRef: append(part.base64, 'base64') };
    }) };
    if (message.reasoning) {
      if (message.reasoning.length < 1024) mapped.reasoning = message.reasoning;
      else mapped.reasoningRef = append(message.reasoning, 'string', encoder.encode(message.reasoning).byteLength);
    }
    return mapped;
  });

  const header = JSON.stringify({ version: 1, input: { ...input, messages }, query, references });
  const body = new Blob([header, '\n', ...payloads], { type: 'application/x-aera-job' });
  if (body.size > payloadLimit) throw new Error('请求超过 96 MiB，请减少附件后重试。');
  return body;
}
