export type AccountRole = 'user' | 'family' | 'institution';
export type Question = { id: string; title: string; description: string; options: readonly [string, string, string, string] };
export const roleNames: Record<AccountRole, string> = { user: 'Usuario', family: 'Grupo Familiar', institution: 'Institución' };
export const questions: Record<AccountRole, Question[]> = {
  user: [
    { id: 'purpose', title: '¿Para qué usarías TINO?', description: 'Elegí lo que más te serviría hoy. Después lo podés cambiar.', options: ['Comunicarme: mensajes, fotos y videollamadas', 'Resolver trámites y usar servicios digitales', 'Entender lo que aparece en mi pantalla', 'Aprender a usar nuevas funciones del teléfono'] },
    { id: 'interaction', title: '¿Cómo preferís recibir ayuda?', description: 'La ayuda se adapta a tu forma de interactuar.', options: ['Escuchar instrucciones por voz', 'Leer instrucciones por chat', 'Ver indicaciones y botones destacados', 'Combinar voz, texto e indicaciones visuales'] },
    { id: 'pace', title: '¿Cuánto acompañamiento querés?', description: 'No hay una respuesta correcta. Elegí lo que te resulte cómodo.', options: ['Una indicación por vez, con tiempo para probar', 'Una explicación completa antes de empezar', 'Ayuda solo cuando yo la pida', 'Que TINO me pregunte y lo ajustemos juntos'] },
    { id: 'priority', title: '¿Qué te gustaría priorizar?', description: 'Esto orienta tus accesos rápidos; no pide ni infiere un diagnóstico.', options: ['Leer y comprender mejor los textos', 'Usar la voz para interactuar', 'Revisar enlaces y permisos con más confianza', 'Practicar tareas para ganar autonomía'] },
  ],
  family: [
    { id: 'relationship', title: '¿Desde qué lugar acompañás?', description: 'Una red puede incluir a distintas personas de confianza.', options: ['Soy familiar', 'Soy una amistad o persona cercana', 'Soy una persona cuidadora o de apoyo', 'Formo parte de un equipo de acompañamiento'] },
    { id: 'support', title: '¿Para qué usarías el grupo?', description: 'Solo vas a poder ver lo que la otra persona autorice.', options: ['Acompañar su aprendizaje y progreso', 'Recibir pedidos de ayuda', 'Revisar señales de seguridad junto a esa persona', 'Combinar progreso, ayuda y seguridad autorizados'] },
    { id: 'frequency', title: '¿Cómo preferís recibir novedades?', description: 'Es una preferencia de configuración, siempre sujeta a los permisos.', options: ['Solo cuando la persona solicite ayuda', 'Un resumen diario', 'Un resumen semanal', 'Avisos relevantes y un resumen periódico'] },
    { id: 'guidance', title: '¿Qué te ayudaría a acompañar mejor?', description: 'Personalizamos también la experiencia de quien acompaña.', options: ['Explicaciones simples para orientar a distancia', 'Poder preparar indicaciones breves por voz', 'Ver el progreso en un resumen visual', 'Tener más detalle de las tareas autorizadas'] },
  ],
  institution: [
    { id: 'objective', title: '¿Qué quiere mejorar tu institución?', description: 'Las vistas institucionales siempre trabajan con datos agregados.', options: ['Acceso y autonomía digital', 'Talleres y programas de capacitación', 'Prevención y acompañamiento ante riesgos digitales', 'Planificación territorial de servicios'] },
    { id: 'evidence', title: '¿Qué información necesitás priorizar?', description: 'OpenARG aporta contexto público con fuentes y períodos visibles.', options: ['Necesidades de ayuda por territorio', 'Flujos más pedidos y puntos de dificultad', 'Uso y aprendizaje en el tiempo', 'Resultados de programas e intervenciones'] },
    { id: 'work', title: '¿Cómo vas a usar el portal?', description: 'Tu perfil institucional se habilita después de verificar el acceso.', options: ['Consultar indicadores y mapas', 'Diseñar programas de acompañamiento', 'Evaluar programas existentes', 'Preparar informes para mi equipo'] },
  ],
};
export interface OnboardingProfile {
  name: string; email: string; phone: string; username: string; birthDate: string; territoryId: string;
  organization: string; organizationType: string; position: string; photo: string;
}
export const initialProfile: OnboardingProfile = { name: '', email: '', phone: '', username: '', birthDate: '', territoryId: 'AR-C', organization: '', organizationType: 'community', position: '', photo: '' };
export interface OnboardingDraft {
  version: 1; role: AccountRole; profile: OnboardingProfile; answers: Record<string, number>;
  preferences: Record<string, boolean>; familyMode: 'create' | 'join'; adminEmail: string; targetIdentifier: string;
}
