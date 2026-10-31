import {PauseIcon,PlayIcon,SpeakerHighIcon,XIcon} from '@phosphor-icons/react';
import type {PracticeGuide} from './jury-practice-model';
export function JuryGuide({guide,paused,onRepeat,onPause,onCancel}:{guide:PracticeGuide;paused:boolean;onRepeat:()=>void;onPause:()=>void;onCancel:()=>void}){
 return <aside className={`j-guide-panel ${guide.target?'':'is-done'} ${paused?'is-paused':''}`} aria-label="Guía de TINO" data-guide-step={guide.id}>
  <img src="/brand/tino-mascot.png" alt=""/><div className="j-guide-copy"><span>TINO · {guide.target?`PASO ${guide.step} DE ${guide.total}`:'¡LISTO!'}{paused?' · VOZ EN PAUSA':''}</span><p id="jury-current-guide" aria-live="polite">{guide.text}</p></div><div className="j-guide-actions"><button aria-label={paused?'Reanudar guía por voz':'Repetir instrucción'} onClick={onRepeat}>{paused?<PlayIcon size={17}/>:<SpeakerHighIcon size={18}/>}</button>{!paused&&<button aria-label="Pausar guía por voz" onClick={onPause}><PauseIcon size={17}/></button>}<button aria-label="Cerrar guía" onClick={onCancel}><XIcon size={17}/></button></div>
 </aside>;
}
