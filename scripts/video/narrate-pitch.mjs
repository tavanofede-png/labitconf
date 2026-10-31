import {readFile,writeFile,mkdir,access} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import WebSocket from 'ws';
const root=fileURLToPath(new URL('../../',import.meta.url));
const output=path.join(root,'.cache/pitch-video/voices');await mkdir(output,{recursive:true});
const story=JSON.parse(await readFile(new URL('./pitch-story.json',import.meta.url),'utf8'));
if(!process.env.OPENAI_API_KEY)throw Error('OPENAI_API_KEY is required in the private local environment.');
const model='gpt-realtime-2.1-mini';
function wav(pcm){const h=Buffer.alloc(44);h.write('RIFF');h.writeUInt32LE(36+pcm.length,4);h.write('WAVEfmt ',8);h.writeUInt32LE(16,16);h.writeUInt16LE(1,20);h.writeUInt16LE(1,22);h.writeUInt32LE(24000,24);h.writeUInt32LE(48000,28);h.writeUInt16LE(2,32);h.writeUInt16LE(16,34);h.write('data',36);h.writeUInt32LE(pcm.length,40);return Buffer.concat([h,pcm]);}
async function generate(clip){
 const dest=path.join(output,clip.id+'.wav');
 try{await access(dest);const saved=JSON.parse(await readFile(path.join(output,clip.id+'.json'),'utf8'));if(saved.text===clip.text&&saved.voice===clip.voice){console.log('VOICE_CACHED',clip.id,saved.seconds);return;}}catch{}
 const performance=clip.role==='marta'?'Sos la voz ficticia de Marta, una mujer mayor argentina. Soná cercana, natural y un poco dubitativa al dirigirte a TINO. Sin caricatura ni fragilidad exagerada.':clip.role==='tino'?'Sos TINO. Voz cálida, paciente y conversacional, con voseo rioplatense suave. Acompañás a una adulta mayor de igual a igual. Sonreí en la voz sin infantilizar.':'Locutor de un pitch argentino. Voz clara, humana, conversacional y ágil. Entonación expresiva con pausas breves. Sin tono de noticiero, solemnidad ni entusiasmo artificial.';
 const instructions=`${performance} Leé SOLAMENTE el texto indicado, exactamente y en español de Argentina. No agregues explicaciones, saludos ni herramientas. Duración buscada: unos ${Math.round(clip.end-clip.start)} segundos. Texto: ${JSON.stringify(clip.text)}`;
 const result=await new Promise((resolve,reject)=>{
  const ws=new WebSocket('wss://api.openai.com/v1/realtime?model='+model,{headers:{Authorization:'Bearer '+process.env.OPENAI_API_KEY},handshakeTimeout:15000,maxPayload:2000000});
  const chunks=[];let transcript='',started=false;const timer=setTimeout(()=>finish(Error('Voice generation timed out: '+clip.id)),60000);
  function finish(error){clearTimeout(timer);ws.close();if(error)reject(error);else resolve({pcm:Buffer.concat(chunks),transcript});}
  ws.on('error',()=>finish(Error('Voice connection failed: '+clip.id)));
  ws.on('message',raw=>{let e;try{e=JSON.parse(raw.toString());}catch{return;}
   if(e.type==='session.created')ws.send(JSON.stringify({type:'session.update',session:{type:'realtime',model,instructions,output_modalities:['audio'],max_output_tokens:4096,audio:{input:{format:{type:'audio/pcm',rate:24000},turn_detection:null},output:{format:{type:'audio/pcm',rate:24000},voice:clip.voice}},tools:[],tool_choice:'none'}}));
   if(e.type==='session.updated'&&!started){started=true;ws.send(JSON.stringify({type:'conversation.item.create',item:{type:'message',role:'user',content:[{type:'input_text',text:clip.text}]}}));ws.send(JSON.stringify({type:'response.create',response:{output_modalities:['audio'],max_output_tokens:4096}}));}
   if(e.type==='response.output_audio.delta')chunks.push(Buffer.from(e.delta,'base64'));
   if(e.type==='response.output_audio_transcript.done')transcript=e.transcript;
   if(e.type==='response.done'){if(e.response.status!=='completed'||!chunks.length)finish(Error('Incomplete voice: '+clip.id));else finish();}
   if(e.type==='error')finish(Error('Voice provider rejected clip '+clip.id+' ('+String(e.error?.code||'unknown').replace(/[^a-z_]/gi,'')+')'));
  });
 });
 await writeFile(dest,wav(result.pcm));const meta={id:clip.id,text:clip.text,transcript:result.transcript,voice:clip.voice,model,seconds:result.pcm.length/48000,synthetic:true};await writeFile(path.join(output,clip.id+'.json'),JSON.stringify(meta,null,2));console.log('VOICE_READY',clip.id,meta.seconds.toFixed(2),meta.transcript);
}
let next=0;await Promise.all(Array.from({length:2},async()=>{while(next<story.voices.length)await generate(story.voices[next++]);}));
console.log('ALL_PITCH_VOICES_READY');
