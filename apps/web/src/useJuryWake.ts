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

export function useJuryWake({ enabled, source, conversationActive, onWake, onMessage, onIdle }: { enabled: boolean; source: MediaStream | null; conversationActive: boolean; onWake: () => void; onMessage: (text: string) => void; onIdle: () => void }) {
  const callbacks = useRef({ onWake, onMessage, onIdle }); callbacks.current = { onWake, onMessage, onIdle };
  const recognition = useRef<Recognition | null>(null);
  const active = useRef(false); const query = useRef(''); const cancelRef = useRef<() => void>(() => {});
  const [capturing, setCapturing] = useState(false); const [partial, setPartial] = useState(''); const [seconds, setSeconds] = useState(0);
  const [status, setStatus] = useState<'off'|'starting'|'ready'|'error'>('off'); const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!capturing) { setSeconds(0); return; }
    const timer = setInterval(() => setSeconds(value => value + 1), 1000); return () => clearInterval(timer);
  }, [capturing]);
  useEffect(() => {
    if (!enabled || !source?.active) { setStatus('off'); return; }
    const C = (window as SpeechWindow).SpeechRecognition || (window as SpeechWindow).webkitSpeechRecognition;
    if (!C) { setStatus('error'); setError('Usá Chrome para activar TINO por voz. El micrófono del chat sigue disponible.'); return; }
    let disposed = false; const stream = source.clone(); let restart: ReturnType<typeof setTimeout> | undefined;
    let silence: ReturnType<typeof setTimeout> | undefined; let idle: ReturnType<typeof setTimeout> | undefined;
    let failed = false; let completed = false; let retries = 0; let heardWake = false;
    active.current = conversationActive; query.current = '';
    const reset = () => { active.current = false; query.current = ''; heardWake = false; setCapturing(false); setPartial(''); clearTimeout(silence); clearTimeout(idle); };
    const finish = () => {
      const text = query.current.trim() || (heardWake ? 'Hola TINO' : '');
      reset();
      if (text && !completed) { completed = true; recognition.current?.abort(); callbacks.current.onMessage(text); }
    };
    const expire = () => { reset(); callbacks.current.onIdle(); recognition.current?.abort(); };
    const renewIdle = () => { clearTimeout(idle); idle = setTimeout(expire, 30000); };
    if (conversationActive) renewIdle();
    cancelRef.current = () => { reset(); callbacks.current.onIdle(); recognition.current?.abort(); };
    const start = () => {
      if (disposed || document.visibilityState === 'hidden') return;
      setStatus('starting'); setError('');
      try {
        const r = new C(); recognition.current = r; r.lang = 'es-AR'; r.continuous = true; r.interimResults = true; r.maxAlternatives = 5;
        r.onstart = () => { if (!disposed) { retries = 0; setStatus('ready'); } };
        r.onresult = event => {
          if (disposed || completed) return;
          const results = Array.from(event.results);
          const transcript = results.map(result => result[0].transcript).join(' ');
          let afterWake = wakeQuery(transcript);
          if (afterWake === null && !/\bh?ola\s+nieto\b/i.test(transcript)) {
            for (let index = 0; index < results.length && afterWake === null; index++) {
              for (let option = 1; option < results[index].length && afterWake === null; option++) {
                afterWake = wakeQuery(results.map((result, i) => result[i === index ? option : 0].transcript).join(' '));
              }
            }
          }
          if (afterWake !== null && !heardWake) {
            heardWake = true; active.current = true; callbacks.current.onWake();
          }
          if (!active.current) return;
          setCapturing(true); renewIdle();
          if (afterWake !== null) query.current = afterWake;
          else if (!heardWake) query.current = transcript;
          // Wait for a final hypothesis before treating a standalone invocation as a greeting.
          setPartial(query.current); clearTimeout(silence);
          if (results.at(-1)?.isFinal) silence = setTimeout(finish, 650);
        };
        r.onerror = event => {
          if (disposed || completed || event.error === 'aborted' || event.error === 'no-speech') return;
          failed = true; reset(); setStatus('error');
          setError(event.error === 'not-allowed' || event.error === 'service-not-allowed' ? 'Permití el micrófono en Chrome y volvé a activar la voz.' : 'No pude iniciar la escucha. Volvé a activar la voz o usá el micrófono del chat.');
        };
        r.onend = () => {
          if (disposed || failed || completed) return;
          if (active.current && (query.current || heardWake)) finish();
          else if (++retries < 4) restart = setTimeout(start, 500);
          else { reset(); setStatus('error'); setError('Volvé a activar la voz para escuchar «Hola TINO».'); }
        };
        r.start(stream.getAudioTracks()[0]);
      } catch { if (!disposed) { failed = true; reset(); setStatus('error'); setError('Permití el micrófono en Chrome y volvé a activar la voz.'); } }
    };
    start();
    return () => { disposed = true; clearTimeout(restart); clearTimeout(silence); clearTimeout(idle); recognition.current?.abort(); recognition.current = null; stream.getTracks().forEach(track => track.stop()); reset(); };
  }, [enabled, source, conversationActive, attempt]);
  return { capturing, partial, seconds, status, error, retry: () => setAttempt(value => value + 1), cancel: () => cancelRef.current(), finish: () => { if (query.current.trim()) recognition.current?.stop(); else cancelRef.current(); } };
}
