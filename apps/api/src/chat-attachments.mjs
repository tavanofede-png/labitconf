// Explicit, transient uploads only. Never persist these bytes in sessions, telemetry or logs.
export const MAX_ATTACHMENT_BYTES = 2 * 1024 * 1024;
export const MAX_ATTACHMENTS_TOTAL = 4 * 1024 * 1024;
export const CHAT_BODY_BYTES = 6 * 1024 * 1024;
export class AttachmentError extends Error {
  constructor(message, status = 400) { super(message); this.status = status; this.code = 'invalid_attachment'; }
}
export function validateAttachments(value = []) {
  if (!Array.isArray(value) || value.length > 3) throw new AttachmentError('Podés adjuntar hasta 3 archivos por mensaje.');
  let total = 0;
  return value.map(file => {
    if (!file || typeof file !== 'object' || Array.isArray(file) || Object.keys(file).some(key => !['name','mimeType','dataBase64'].includes(key))) throw new AttachmentError('Adjunto inválido.');
    if (typeof file.name !== 'string' || !file.name.trim() || file.name.length > 120 || /[\x00-\x1f\x7f/\\]/.test(file.name)) throw new AttachmentError('Nombre de archivo inválido.');
    if (typeof file.dataBase64 !== 'string' || file.dataBase64.length > Math.ceil(MAX_ATTACHMENT_BYTES/3)*4 || !/^[A-Za-z0-9+/]+={0,2}$/.test(file.dataBase64)) throw new AttachmentError('Cada archivo puede pesar hasta 2 MB.');
    const bytes = Buffer.from(file.dataBase64,'base64'); total += bytes.length;
    if (!bytes.length || bytes.length > MAX_ATTACHMENT_BYTES || total > MAX_ATTACHMENTS_TOTAL || bytes.toString('base64') !== file.dataBase64) throw new AttachmentError('Los adjuntos pueden sumar hasta 4 MB por mensaje.');
    const actual = bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])) ? 'image/png'
      : bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 ? 'image/jpeg'
      : bytes.length >= 12 && bytes.toString('ascii',0,4) === 'RIFF' && bytes.toString('ascii',8,12) === 'WEBP' ? 'image/webp'
      : bytes.toString('ascii',0,5) === '%PDF-' ? 'application/pdf' : null;
    if (!actual || file.mimeType !== actual) throw new AttachmentError('Elegí una imagen PNG, JPG, WebP, o un PDF válido.');
    return { name: file.name.trim(), mimeType: actual, dataBase64: file.dataBase64 };
  });
}
export function attachmentInput(text, attachments) {
  return [{type:'input_text',text}, ...attachments.map(file => file.mimeType === 'application/pdf'
    ? {type:'input_file',filename:file.name,file_data:`data:application/pdf;base64,${file.dataBase64}`}
    : {type:'input_image',image_url:`data:${file.mimeType};base64,${file.dataBase64}`,detail:'auto'})];
}
