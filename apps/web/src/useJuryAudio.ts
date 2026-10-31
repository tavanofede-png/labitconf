import { useCallback, useEffect, useRef, useState } from 'react';

export type JuryVoiceAudio = { base64: string; mimeType: string };

export function useJuryAudio() {
  const element = useRef<HTMLAudioElement>(null);
  const completion = useRef<{ resolve: () => void; reject: (error: Error) => void } | null>(null);
  const pending = useRef<JuryVoiceAudio | null>(null);
  const generation = useRef(0);
  const [playing, setPlaying] = useState(false); const [blocked, setBlocked] = useState(false); const [error, setError] = useState('');
  const stop = useCallback(() => {
    generation.current++;
    element.current?.pause(); completion.current?.resolve(); completion.current = null;
    pending.current = null; setPlaying(false); setBlocked(false); setError('');
  }, []);
  const play = useCallback(async (audio: JuryVoiceAudio) => {
    const player = element.current; if (!player) throw new Error('No pude preparar el sonido.');
    stop(); pending.current = audio;
    const currentGeneration = generation.current;
    player.src = `data:${audio.mimeType};base64,${audio.base64}`;
    player.dataset.voiceKind = 'reply'; player.muted = false; player.volume = 1;
    let turn: { resolve: () => void; reject: (error: Error) => void };
    const done = new Promise<void>((resolve, reject) => { turn = { resolve, reject }; completion.current = turn; });
    // A rejected play() must not leave a second unhandled promise behind.
    done.catch(() => {});
    try { await player.play(); setPlaying(true); await done; }
    catch (failure) {
      if (generation.current !== currentGeneration) return;
      setPlaying(false);
      if (failure instanceof DOMException && failure.name === 'NotAllowedError') {
        setBlocked(true); setError('Tocá Activar sonido para escuchar a TINO.');
      } else { pending.current = null; setError('No pude reproducir el audio. Volvé a intentar.'); }
      throw new Error('No pude reproducir el audio de TINO.');
    } finally { if (completion.current === turn!) completion.current = null; }
  }, [stop]);
  const retry = useCallback(() => { const audio = pending.current; if (audio) void play(audio).catch(() => {}); }, [play]);
  useEffect(() => () => { element.current?.pause(); completion.current?.resolve(); }, []);
  return { element, playing, blocked, error, play, retry, stop,
    onEnded: () => { setPlaying(false); pending.current = null; completion.current?.resolve(); },
    onError: () => { setPlaying(false); completion.current?.reject(new Error('Audio playback failed')); },
  };
}
