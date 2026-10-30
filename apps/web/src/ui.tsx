import type { ReactNode } from 'react';
import { ArrowSquareOutIcon, CircleNotchIcon, InfoIcon, WarningCircleIcon } from '@phosphor-icons/react';
import { safeUrl } from './api';

export function Spinner() { return <CircleNotchIcon size={17} className="spinner" aria-label="Cargando" />; }
export function Notice({ children, kind = 'info' }: { children: ReactNode; kind?: 'info' | 'error' | 'success' }) {
  return <div className={`notice ${kind}`} role={kind === 'error' ? 'alert' : 'status'}>{kind === 'error' ? <WarningCircleIcon size={20} /> : <InfoIcon size={20} />}<div>{children}</div></div>;
}
export function SourceLink({ url, children }: { url?: string; children: ReactNode }) {
  const href = safeUrl(url);
  return href ? <a className="source-link" href={href} target="_blank" rel="noopener noreferrer">{children}<ArrowSquareOutIcon size={14} /></a> : <span className="muted">{children}</span>;
}
export function EmptyState({ icon, title, children, action }: { icon: ReactNode; title: string; children: ReactNode; action?: ReactNode }) {
  return <div className="empty-state"><span className="empty-icon">{icon}</span><h3>{title}</h3><p>{children}</p>{action}</div>;
}
