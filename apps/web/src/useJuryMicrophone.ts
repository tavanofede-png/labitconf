import { useCallback, useEffect, useRef, useState } from 'react';

export const microphoneConstraints: MediaStreamConstraints = { audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } };

export function useJuryMicrophone() {
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [error, setError] = useState(''); const [requesting, setRequesting] = useState(false);
  const source = useRef<MediaStream | null>(null); const pending = useRef<Promise<void> | null>(null);
  const mounted = useRef(false); const wanted = useRef(true);
  const pause = useCallback(() => {
    wanted.current = false; source.current?.getTracks().forEach(track => track.stop()); source.current = null;
    setStream(null); setError('');
  }, []);
  const request = useCallback(async () => {
    wanted.current = true;
    if (source.current?.active) return;
    if (pending.current) return pending.current;
    if (!navigator.mediaDevices?.getUserMedia) { setError('Abrí la demo por HTTPS en Chrome para usar el micrófono.'); return; }
    setRequesting(true); setError('');
    const work = (async () => {
      try {
        const input = await navigator.mediaDevices.getUserMedia(microphoneConstraints);
        if (!mounted.current || !wanted.current) { input.getTracks().forEach(track => track.stop()); return; }
        source.current = input; setStream(input);
        input.getAudioTracks().forEach(track => track.addEventListener('ended', () => {
          if (source.current !== input) return;
          source.current = null; setStream(null); setError('El micrófono se desconectó. Activá la voz nuevamente.');
        }, { once: true }));
      } catch { if (mounted.current) setError('Permití el micrófono de esta computadora en el aviso del navegador.'); }
      finally { if (mounted.current) setRequesting(false); pending.current = null; }
    })();
    pending.current = work; return work;
  }, []);
  useEffect(() => {
    mounted.current = true; void request();
    return () => { mounted.current = false; source.current?.getTracks().forEach(track => track.stop()); source.current = null; };
  }, [request]);
  useEffect(() => {
    const visibility = () => {
      if (document.visibilityState === 'hidden') { source.current?.getTracks().forEach(track => track.stop()); source.current = null; setStream(null); }
      else if (wanted.current) void request();
    };
    document.addEventListener('visibilitychange', visibility);
    return () => document.removeEventListener('visibilitychange', visibility);
  }, [request]);
  const acquire = useCallback(async () => { await request(); if (!source.current?.active) throw new Error('Permití el micrófono en el navegador.'); return source.current.clone(); }, [request]);
  return { stream, enabled: Boolean(stream?.active), label: stream?.getAudioTracks()[0]?.label || '', error, requesting, request, pause, acquire };
}
