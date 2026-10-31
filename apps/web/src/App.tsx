import { JuryApp } from './JuryApp';
import { JuryStage } from './JuryStage';

export function App() {
  return window.location.pathname.startsWith('/app') ? <JuryApp /> : <JuryStage />;
}
