let browserClientId: string | undefined;
export async function juryApi<T>(resource: string, body?: unknown, query = '', signal?:AbortSignal): Promise<T> {
  const family=resource==='family'||resource==='family-action';
  const token=family?await jurySession('family'):'';
  if(resource==='family-action') {
    const response=await fetch('/api/demo/family/actions',{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify({id:crypto.randomUUID(),...(body as object)})});
    const value=await response.json();if(!response.ok)throw new Error(value.error?.message||'No pude enviar el aviso.');return value as T;
  }
  const response = await fetch(`/api/jury?resource=${resource}${query}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { ...(body===undefined?{}:{'Content-Type':'application/json'}),...(token?{Authorization:`Bearer ${token}`}:{}) },
    body: body === undefined ? undefined : JSON.stringify(resource==='chat'?{...(body as object),clientId:browserClientId??=(crypto.randomUUID())}:body),
    signal: signal?AbortSignal.any([signal,AbortSignal.timeout(65000)]):AbortSignal.timeout(65000),
  });
  const value = await response.json();
  if (!response.ok) throw new Error(value.error?.message || 'No pudimos completar la acción.');
  return value as T;
}

let sessionWork:Promise<string>|undefined;
export async function jurySession(role:string):Promise<string> {
 if(sessionWork)await sessionWork;
 sessionWork=(async()=>{
   let saved: {role:string;sessionToken:string}|null=null;
   try{saved=JSON.parse(sessionStorage.getItem('tino.jury.session')||'null');}catch{/* Start a fresh demo session. */}
   if(saved?.role===role)return saved.sessionToken;
   async function request(path:string,body:object,token=''){const response=await fetch('/api'+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body)});const value=await response.json();if(!response.ok)throw new Error(value.error?.message||'No pude abrir la cuenta.');return value;}
   if(!saved)saved=await request('/auth/demo/login',{email:'test@labitconf.com',password:'0000'});
   if(saved?.role!==role)saved=await request('/accounts/space',{role},saved?.sessionToken);
   if(!saved?.sessionToken)throw new Error('No pude iniciar la sesión.');
   sessionStorage.setItem('tino.jury.session',JSON.stringify(saved));return saved.sessionToken;
 })();
 try{return await sessionWork;}finally{sessionWork=undefined;}
}
