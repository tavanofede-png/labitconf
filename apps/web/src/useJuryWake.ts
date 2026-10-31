import { useEffect, useRef, useState } from 'react';

type RecognitionResult = { isFinal: boolean; length: number; [index: number]: { transcript: string } };
type Recognition = {
  lang: string; continuous: boolean; interimResults: boolean; maxAlternatives: number;
  onstart: (() => void) | null; onend: (() => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onresult: ((event: { results: ArrayLike<RecognitionResult> }) => void) | null;
  start: (track?: MediaStreamTrack) => void; stop: () => void; abort: () => void;
};
type SpeechWindow = Window & { SpeechRecognition?: new () => Recognition; webkitSpeechRecognition?: new () => Recognition };
const invocation = /\bh?ola\s*tino\b[\s,.:;!?¡¿-]*/i;
export function wakeQuery(transcript: string): string | null {
  const normalized = transcript.normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  const match = invocation.exec(normalized);
  return match ? transcript.slice(match.index + match[0].length).trim() : null;
}

export function useJuryWake({ enabled, onWake, onMessage }: { enabled: boolean; onWake: () => void; onMessage: (text: string) => void }) {
  const callbacks = useRef({ onWake, onMessage }); callbacks.current = { onWake, onMessage };
  const recognition = useRef<Recognition | null>(null);
  const active = useRef(false); const query = useRef(''); const cancelRef = useRef<() => void>(() => {});
  const [capturing, setCapturing] = useState(false); const [partial, setPartial] = useState('');
  const [status, setStatus] = useState<'off'|'starting'|'ready'|'error'>('off'); const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!enabled) { setStatus('off'); return; }
    const C = (window as SpeechWindow).SpeechRecognition || (window as SpeechWindow).webkitSpeechRecognition;
    if (!C) { setStatus('error'); setError('Usá Chrome para activar TINO por voz. El micrófono del chat sigue disponible.'); return; }
    let disposed = false; let stream: MediaStream | undefined; let restart: ReturnType<typeof setTimeout> | undefined;
    let silence: ReturnType<typeof setTimeout> | undefined; let limit: ReturnType<typeof setTimeout> | undefined;
    let failed = false; let completed = false; let retries = 0;
    const reset = () => { active.current = false; query.current = ''; setCapturing(false); setPartial(''); clearTimeout(silence); clearTimeout(limit); };
    const finish = () => {
      const text = query.current.trim(); reset();
      if (text && !completed) { completed = true; recognition.current?.abort(); callbacks.current.onMessage(text); }
    };
    cancelRef.current = () => { reset(); recognition.current?.abort(); };
    const start = async () => {
      if (disposed || document.visibilityState === 'hidden') return;
      setStatus('starting'); setError('');
      try {
        if (!stream) stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
        if (disposed) { stream.getTracks().forEach(track => track.stop()); return; }
        const r = new C(); recognition.current = r; r.lang = 'es-AR'; r.continuous = true; r.interimResults = true; r.maxAlternatives = 5;
        r.onstart = () => { if (!disposed) { retries = 0; setStatus('ready'); } };
        r.onresult = event => {
          if (disposed || completed) return;
          const results = Array.from(event.results);
          const transcript = results.map(result => result[0].transcript).join(' ');
          let afterWake = wakeQuery(transcript);
          if (afterWake === null && !/\bh?ola\s+nieto\b/i.test(transcript)) {
            // Some recognizers put the brand in a secondary hypothesis.
            for (let index = 0; index < results.length && afterWake === null; index++) {
              for (let option = 1; option < results[index].length && afterWake === null; option++) {
                afterWake = wakeQuery(results.map((result, i) => result[i === index ? option : 0].transcript).join(' '));
              }
            }
          }
          if (!active.current && afterWake !== null) {
            active.current = true; setCapturing(true); callbacks.current.onWake();
            limit = setTimeout(() => { if (query.current) finish(); else cancelRef.current(); }, 15000);
          }
          if (!active.current) return;
          // A standalone wake phrase may become a final result before the request.
          if (afterWake !== null) query.current = afterWake;
          else query.current = transcript;
          setPartial(query.current);
          clearTimeout(silence);
          if (query.current) silence = setTimeout(finish, results.at(-1)?.isFinal ? 600 : 1800);
        };
        r.onerror = event => {
          if (disposed || completed || event.error === 'aborted' || event.error === 'no-speech') return;
          failed = true; reset(); stream?.getTracks().forEach(track => track.stop()); stream = undefined; setStatus('error');
          setError(event.error === 'not-allowed' || event.error === 'service-not-allowed' ? 'Permití el micrófono en Chrome y volvé a activar la voz.' : 'No pude iniciar la escucha. Volvé a activar la voz o usá el micrófono del chat.');
        };
        r.onend = () => { if (!disposed && !failed && !completed) { if (active.current && query.current) finish(); else if (++retries < 4) restart = setTimeout(() => void start(), 500); else { reset(); setStatus('error'); setError('Volvé a activar la voz para escuchar «Hola TINO».'); } } };
        r.start(stream.getAudioTracks()[0]);
      } catch {
        if (!disposed) { failed = true; stream?.getTracks().forEach(track => track.stop()); stream = undefined; setStatus('error'); setError('Permití el micrófono en Chrome y volvé a activar la voz.'); }
      }
    };
    const visibility = () => { if (document.visibilityState === 'hidden') { clearTimeout(restart); recognition.current?.abort(); stream?.getTracks().forEach(track => track.stop()); stream = undefined; reset(); setStatus('off'); } else if (!failed && !completed) void start(); };
    document.addEventListener('visibilitychange', visibility); void start();
    return () => { disposed = true; clearTimeout(restart); clearTimeout(silence); clearTimeout(limit); recognition.current?.abort(); recognition.current = null; stream?.getTracks().forEach(track => track.stop()); reset(); document.removeEventListener('visibilitychange', visibility); };
  }, [enabled, attempt]);
  return { capturing, partial, status, error, retry: () => setAttempt(value => value + 1), cancel: () => cancelRef.current(), finish: () => { if (query.current.trim()) recognition.current?.stop(); else cancelRef.current(); } };
}
