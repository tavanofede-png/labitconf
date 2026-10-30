import { useEffect, useRef, useState } from 'react';
import { ArrowSquareOutIcon, MapPinIcon } from '@phosphor-icons/react';
import type { Map as MapType, Marker as MarkerType } from 'maplibre-gl';
import mapWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import type { Territory } from './territories';
import { safeUrl } from './api';

type MapIndicator = { label: string; value: number | string; unit: string; period: string; sourceUrl: string; sourceTitle: string };

export function TerritoryMap({ territory, indicator }: { territory: Territory; indicator?: MapIndicator }) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<MapType | null>(null);
  const marker = useRef<MarkerType | null>(null);
  const [mapFailed, setMapFailed] = useState(false);
  const initial = useRef(territory);

  useEffect(() => {
    let disposed = false;
    void import('maplibre-gl').then((maplibre) => {
      if (disposed || !container.current) return;
      try {
        maplibre.setWorkerUrl(mapWorkerUrl);
        const current = initial.current;
        const m = new maplibre.Map({
          container: container.current,
          center: [...current.point], zoom: 10.2, minZoom: 3, maxZoom: 16,
          style: {
            version: 8,
            sources: { osm: { type: 'raster', tiles: [import.meta.env.VITE_MAP_TILE_URL || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'], tileSize: 256, attribution: '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a> contributors' } },
            layers: [{ id: 'base', type: 'raster', source: 'osm', paint: { 'raster-saturation': -0.85, 'raster-contrast': -0.10, 'raster-opacity': 0.9 } }],
          },
          attributionControl: { compact: false },
        });
        map.current = m;
        m.scrollZoom.disable();
        m.addControl(new maplibre.NavigationControl({ showCompass: false }), 'top-right');
        const dot = document.createElement('div'); dot.className = 'map-point';
        dot.setAttribute('aria-label', 'Referencia territorial; no representa personas');
        marker.current = new maplibre.Marker({ element: dot }).setLngLat([...current.point]).addTo(m);
        m.on('error', () => { if (!disposed) setMapFailed(true); });
        m.on('idle', () => { if (!disposed && m.areTilesLoaded()) setMapFailed(false); });
      } catch { if (!disposed) setMapFailed(true); }
    }).catch(() => { if (!disposed) setMapFailed(true); });
    return () => { disposed = true; marker.current?.remove(); map.current?.remove(); map.current = null; };
  }, []);

  useEffect(() => {
    initial.current = territory;
    marker.current?.setLngLat([...territory.point]);
    map.current?.flyTo({ center: [...territory.point], zoom: territory.id === 'AR-C' ? 10.2 : 8.6, duration: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 900 });
  }, [territory]);

  return <div className="territory-map">
    <div ref={container} className="map-canvas" role="region" aria-label={`Mapa de referencia de ${territory.name}`} />
    {indicator && <div className="map-datum"><span>CONTEXTO HISTÓRICO · {indicator.period}</span><strong>{typeof indicator.value === 'number' ? new Intl.NumberFormat('es-AR', { maximumFractionDigits: 1 }).format(indicator.value) : indicator.value} <small>{indicator.unit}</small></strong><p>{indicator.label}</p><a href={safeUrl(indicator.sourceUrl)} target="_blank" rel="noopener noreferrer">{indicator.sourceTitle} <ArrowSquareOutIcon size={12} /></a></div>}
    <div className="map-caption"><MapPinIcon weight="fill" size={17} /><div><strong>{territory.name}</strong><span>Referencia de capital · sin ubicaciones personales</span></div></div>
    {mapFailed && <div className="map-warning">El mapa requiere conexión a internet. <a href={`https://www.openstreetmap.org/#map=10/${territory.point[1]}/${territory.point[0]}`} target="_blank" rel="noreferrer">Abrir cartografía <ArrowSquareOutIcon /></a></div>}
  </div>;
}
