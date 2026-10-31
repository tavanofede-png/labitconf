import guideSource from '../../api/src/jury-guide-steps.mjs';
export const guideTexts: Record<string,string> = guideSource;
export type GuidedTask = 'group'|'message'|'location'|'email';
export const practiceContacts = [
 {id:'lucia',name:'Lucía',color:'#b7c9b6',preview:'¿Nos encontramos en la plaza?',time:'10:04'},
 {id:'ana',name:'Ana',color:'#d9c4a8',preview:'¡Gracias por avisarme!',time:'09:46'},
 {id:'pedro',name:'Pedro',color:'#b8c3d9',preview:'Dale, contá conmigo.',time:'09:32'},
 {id:'martin',name:'Martín',color:'#cbb5c9',preview:'Nos vemos el sábado.',time:'Ayer'},
];
export type PracticeMessage={id:number;text:string;kind:'text'|'location'|'system';outgoing:boolean};
type Group={id:string;name:string;members:string[]};
export type PracticeMail={id:number;to:string;subject:string;body:string};
export type PracticeState={
 task:GuidedTask|null;run:number;complete:boolean;sequence:number;
 wa:{screen:'chats'|'new-chat'|'contacts'|'group-name'|'chat'|'attachments'|'location';search:string;searchUsed:boolean;selected:string[];groupName:string;nameReady:boolean;chatId:string;groups:Group[];messages:Record<string,PracticeMessage[]>;drafts:Record<string,string>;draftReady:boolean};
 gm:{screen:'inbox'|'compose'|'confirm'|'detail';folder:'inbox'|'sent';to:string;subject:string;body:string;ready:Record<string,boolean>;sent:PracticeMail[];opened:number|null};
};
export type PracticeAction=
 |{type:'START';task:GuidedTask}|{type:'CANCEL'}
 |{type:'WA_PLUS'|'WA_NEW_GROUP'|'WA_SEARCH_FOCUS'|'WA_NEXT'|'WA_NAME_READY'|'WA_CREATE'|'WA_DRAFT_READY'|'WA_SEND'|'WA_ATTACH'|'WA_LOCATION'|'WA_SHARE'|'WA_BACK'}
 |{type:'WA_SEARCH'|'WA_NAME'|'WA_DRAFT';value:string}|{type:'WA_CONTACT';id:string}
 |{type:'GM_COMPOSE'|'GM_SEND'|'GM_CONFIRM'|'GM_REVIEW'|'GM_BACK'}
 |{type:'GM_SET';field:'to'|'subject'|'body';value:string}|{type:'GM_READY';field:'to'|'subject'|'body'}
 |{type:'GM_FOLDER';folder:'inbox'|'sent'}|{type:'GM_OPEN';id:number};
export function createPracticeState():PracticeState{
 return{task:null,run:0,complete:false,sequence:10,wa:{screen:'chats',search:'',searchUsed:false,selected:[],groupName:'',nameReady:false,chatId:'lucia',groups:[],messages:{},drafts:{},draftReady:false},gm:{screen:'inbox',folder:'inbox',to:'',subject:'',body:'',ready:{},sent:[],opened:null}};
}
export const validMail=(value:string)=>/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim());
export function practiceReducer(state:PracticeState,action:PracticeAction):PracticeState{
 const w=state.wa,g=state.gm;
 const wa=(changes:Partial<PracticeState['wa']>,extra:Partial<PracticeState>={})=>({...state,...extra,wa:{...w,...changes}});
 const gm=(changes:Partial<PracticeState['gm']>,extra:Partial<PracticeState>={})=>({...state,...extra,gm:{...g,...changes}});
 switch(action.type){
 case 'START':return{...state,task:action.task,run:state.run+1,complete:false,wa:{...w,screen:'chats',search:'',searchUsed:false,selected:[],groupName:'',nameReady:false,draftReady:false},gm:{...g,screen:'inbox',folder:'inbox',ready:{}}};
 case 'CANCEL':return{...state,task:null};
 case 'WA_PLUS':return w.screen==='chats'?wa({screen:'new-chat',search:'',searchUsed:false}):state;
 case 'WA_NEW_GROUP':return w.screen==='new-chat'?wa({screen:'contacts',selected:[],search:'',searchUsed:false}):state;
 case 'WA_SEARCH_FOCUS':return ['new-chat','contacts'].includes(w.screen)?wa({searchUsed:true}):state;
 case 'WA_SEARCH':return ['chats','new-chat','contacts'].includes(w.screen)?wa({search:action.value.slice(0,80),searchUsed:true}):state;
 case 'WA_CONTACT':{
  const contact=practiceContacts.some(p=>p.id===action.id),group=w.groups.some(p=>p.id===action.id);
  if(w.screen==='contacts'&&contact)return wa({selected:w.selected.includes(action.id)?w.selected.filter(id=>id!==action.id):[...w.selected,action.id],search:'',searchUsed:true});
  return ['chats','new-chat'].includes(w.screen)&&(contact||group)?wa({screen:'chat',chatId:action.id,search:'',draftReady:false}):state;
 }
 case 'WA_NEXT':return w.screen==='contacts'&&w.selected.length>=2?wa({screen:'group-name',groupName:'',nameReady:false}):state;
 case 'WA_NAME':return w.screen==='group-name'?wa({groupName:action.value.slice(0,60),nameReady:false}):state;
 case 'WA_NAME_READY':return w.screen==='group-name'&&w.groupName.trim()?wa({nameReady:true}):state;
 case 'WA_CREATE':{
  if(w.screen!=='group-name'||w.selected.length<2||!w.groupName.trim())return state;
  const id='group-'+state.sequence,name=w.groupName.trim();
  return wa({screen:'chat',chatId:id,groups:[...w.groups,{id,name,members:[...w.selected]}],messages:{...w.messages,[id]:[{id:state.sequence,text:`Creaste el grupo «${name}»`,kind:'system',outgoing:false}]}},{sequence:state.sequence+1,complete:state.complete||state.task==='group'});
 }
 case 'WA_DRAFT':return w.screen==='chat'?wa({drafts:{...w.drafts,[w.chatId]:action.value.slice(0,1000)},draftReady:false}):state;
 case 'WA_DRAFT_READY':return w.screen==='chat'&&w.drafts[w.chatId]?.trim()?wa({draftReady:true}):state;
 case 'WA_SEND':{
  const text=w.drafts[w.chatId]?.trim();if(w.screen!=='chat'||!text)return state;
  return wa({messages:{...w.messages,[w.chatId]:[...(w.messages[w.chatId]||[]),{id:state.sequence,text,kind:'text',outgoing:true}]},drafts:{...w.drafts,[w.chatId]:''},draftReady:false},{sequence:state.sequence+1,complete:state.complete||state.task==='message'});
 }
 case 'WA_ATTACH':return w.screen==='chat'?wa({screen:'attachments'}):state;
 case 'WA_LOCATION':return w.screen==='attachments'?wa({screen:'location'}):state;
 case 'WA_SHARE':return w.screen==='location'?wa({screen:'chat',messages:{...w.messages,[w.chatId]:[...(w.messages[w.chatId]||[]),{id:state.sequence,text:'Plaza del Congreso · Buenos Aires',kind:'location',outgoing:true}]}},{sequence:state.sequence+1,complete:state.complete||state.task==='location'}):state;
 case 'WA_BACK':return wa({screen:w.screen==='attachments'?'chat':w.screen==='location'?'attachments':w.screen==='group-name'?'contacts':'chats',search:''});
 case 'GM_COMPOSE':return ['inbox','detail'].includes(g.screen)?gm({screen:'compose',ready:{}}):state;
 case 'GM_SET':return g.screen==='compose'?gm({[action.field]:action.value.slice(0,action.field==='body'?2000:160),ready:{...g.ready,[action.field]:false}}):state;
 case 'GM_READY':return g.screen==='compose'?gm({ready:{...g.ready,[action.field]:action.field==='to'?validMail(g.to):Boolean(g[action.field].trim())}}):state;
 case 'GM_SEND':return g.screen==='compose'&&validMail(g.to)&&g.subject.trim()&&g.body.trim()?gm({screen:'confirm'}):state;
 case 'GM_REVIEW':return g.screen==='confirm'?gm({screen:'compose'}):state;
 case 'GM_CONFIRM':{
  if(g.screen!=='confirm'||!validMail(g.to)||!g.subject.trim()||!g.body.trim())return state;
  return gm({screen:'inbox',folder:'sent',sent:[{id:state.sequence,to:g.to.trim(),subject:g.subject.trim(),body:g.body.trim()},...g.sent],to:'',subject:'',body:'',ready:{}},{sequence:state.sequence+1,complete:state.complete||state.task==='email'});
 }
 case 'GM_FOLDER':return gm({folder:action.folder,screen:'inbox'});
 case 'GM_OPEN':return gm({screen:'detail',opened:action.id});
 case 'GM_BACK':return gm({screen:'inbox'});
 default:return state;
 }
}
export function resolvePracticeTask(text:string):GuidedTask|null{
 const value=text.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g,'');
 if(/^(no quiero|no crees|no mandes|no envies|cancel|basta|deja de)/.test(value.trim()))return null;
 if(!/crea|arm|hac|ayud|quier|neces|envia|manda|compart|escrib/.test(value))return null;
 if(/grupo/.test(value))return 'group';
 if(/ubicacion|locacion/.test(value))return 'location';
 if(/correo|gmail|mail/.test(value))return 'email';
 if(/mensaje|whatsapp|wasap|wsp/.test(value))return 'message';
 return null;
}
export type PracticeGuide={id:string;target:string|null;text:string;step:number;total:number};
export function practiceGuide(state:PracticeState,surface:string):PracticeGuide|null{
 const task=state.task;if(!task||surface==='tino')return null;
 const guide=(id:string,target:string|null,step:number,total=task==='group'?8:task==='email'?6:6):PracticeGuide=>({id,target,text:guideTexts[id],step,total});
 const app=task==='email'?'gmail':'whatsapp';
 if(state.complete)return surface===app?guide(task+'-done',null,task==='group'?8:6):null;
 if(surface!==app)return surface==='android'?guide('open-'+app,'app-'+app,1):guide('return-android','android-home',1);
 if(task==='email'){
  const g=state.gm;
  if(g.screen==='detail')return guide('gm-back-inbox','gm-back',2);
  if(g.screen==='inbox')return guide('gm-compose','gm-compose',2);
  if(g.screen==='confirm')return guide('gm-confirm','gm-confirm',6);
  if(!g.ready.to)return guide('gm-to','gm-to',3);
  if(!g.ready.subject)return guide('gm-subject','gm-subject',4);
  if(!g.ready.body)return guide('gm-body','gm-body',5);
  return guide('gm-send','gm-send',6);
 }
 const w=state.wa;
 if((task==='group'&&['chat','attachments','location'].includes(w.screen))||(task!=='group'&&['contacts','group-name'].includes(w.screen)))return guide('wa-back-chats','wa-back',2);
 if(w.screen==='chats')return guide('wa-plus','wa-plus',2);
 if(w.screen==='new-chat'&&task==='group')return guide('wa-new-group','wa-new-group',3);
 if(w.screen==='new-chat')return w.searchUsed?guide('wa-contact','wa-contact-list',3):guide('wa-search-contact','wa-search',3);
 if(w.screen==='contacts'){
  if(w.selected.length>=2)return guide('wa-group-next','wa-next',5);
  if(w.selected.length===1)return guide('wa-group-more','wa-contact-list',4);
  return w.searchUsed?guide('wa-group-contacts','wa-contact-list',4):guide('wa-search-group','wa-search',4);
 }
 if(w.screen==='group-name')return w.nameReady?guide('wa-group-create','wa-create',7):guide('wa-group-name','wa-name',6);
 if(w.screen==='attachments')return guide('wa-location','wa-location',5);
 if(w.screen==='location')return guide('wa-share','wa-share',6);
 if(task==='location')return guide('wa-attach','wa-attach',4);
 return w.draftReady?guide('wa-send','wa-send',5):guide('wa-write','wa-write',4);
}
