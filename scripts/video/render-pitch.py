"""Render the edited pitch from actual browser footage, brand assets and AI voices.

Requires Pillow, numpy and an FFmpeg executable (FFMPEG_PATH).
All working recordings stay under .cache/pitch-video; exports go to output/tino-pitch.
"""
from pathlib import Path
import argparse, json, math, os, subprocess, sys, wave
from functools import lru_cache
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps

ROOT=Path(__file__).resolve().parents[2]
WORK=ROOT/'.cache/pitch-video'
OUT=ROOT/'output/tino-pitch'
OUT.mkdir(parents=True,exist_ok=True)
STORY=json.loads((Path(__file__).with_name('pitch-story.json')).read_text())
FF=os.environ.get('FFMPEG_PATH','/private/tmp/tino-video-tools/imageio_ffmpeg/binaries/ffmpeg-macos-aarch64-v7.1')
W,H,FPS=1920,1080,30
CREAM='#f5f7f2'; INK='#102a24'; TEAL='#00cdb7'; DARK='#102c25'; MUTED='#55716a'; MINT='#d4fff0'
FONT=ROOT/'apps/android/app/src/main/res/font/plus_jakarta_sans.ttf'

def run(args):
    p=subprocess.run([FF,'-hide_banner','-loglevel','error','-y',*map(str,args)],capture_output=True)
    if p.returncode: raise RuntimeError(p.stderr.decode(errors='replace')[-3500:])

def rgba(color,alpha=255):
    if isinstance(color,tuple):return (*color[:3],alpha)
    return tuple(int(color.lstrip('#')[i:i+2],16) for i in (0,2,4))+(alpha,)

@lru_cache(None)
def font(size,weight=600):
    f=ImageFont.truetype(str(FONT),round(size))
    f.set_variation_by_axes([weight])
    return f

def ease(x):
    x=max(0,min(1,x));return 1-(1-x)**3

def smooth(x):
    x=max(0,min(1,x));return x*x*(3-2*x)

def text(im,xy,value,size=32,fill=INK,weight=600,anchor=None):
    ImageDraw.Draw(im).text(xy,value,font=font(size,weight),fill=fill,anchor=anchor,spacing=12)

@lru_cache(maxsize=512)
def wrap(value,size,maxw,weight=600):
    lines=[];line=''
    for word in value.split():
        nextline=(line+' '+word).strip()
        if font(size,weight).getlength(nextline)>maxw and line:lines.append(line);line=word
        else:line=nextline
    if line:lines.append(line)
    return lines

def para(im,xy,value,size=32,maxw=1000,fill=INK,weight=600,gap=1.3):
    x,y=xy
    for line in wrap(value,size,maxw,weight):text(im,(x,y),line,size,fill,weight);y+=size*gap
    return y

def rr(im,box,r,fill,outline=None,width=1):
    ImageDraw.Draw(im).rounded_rectangle(tuple(map(round,box)),radius=r,fill=fill,outline=outline,width=width)

@lru_cache(None)
def asset(name):
    paths={'marta':ROOT/'brand/video/marta.png','logo':ROOT/'apps/web/public/brand/tino-logo.png','ghost':ROOT/'apps/web/public/brand/tino-mascot.png','native':ROOT/'brand/video/android-prototype.png'}
    im=Image.open(paths[name]).convert('RGBA')
    if name in ('logo','ghost'):im=im.crop(im.getbbox())
    return im

@lru_cache(None)
def resized(name,width):
    src=asset(name);return src.resize((int(width),round(src.height*width/src.width)),Image.Resampling.LANCZOS)

def paste(im,src,xy,alpha=1):
    x,y=map(round,xy)
    if alpha<.999:
        src=src.copy();src.putalpha(src.getchannel('A').point(lambda a:round(a*alpha)))
    im.alpha_composite(src,(x,y))

def pill(im,xy,label,bg='#e0f8ee',fg=INK,size=22,pad=17):
    width=round(font(size,700).getlength(label))+2*pad
    rr(im,(xy[0],xy[1],xy[0]+width,xy[1]+size+2*pad),25,bg)
    text(im,(xy[0]+pad,xy[1]+pad-3),label,size,fg,700)
    return width

@lru_cache(None)
def background(dark=False):
    im=Image.new('RGBA',(W,H),DARK if dark else CREAM)
    glow=Image.new('RGBA',(W,H));d=ImageDraw.Draw(glow)
    d.ellipse((1050,-150,2250,1050),fill=rgba('#176d58' if dark else '#bcecd8',60 if dark else 85))
    d.ellipse((-460,650,600,1700),fill=rgba('#235e4b' if dark else '#d5eddf',40 if dark else 90))
    glow=glow.filter(ImageFilter.GaussianBlur(90));im.alpha_composite(glow)
    return im

def header(im,t,dark=False):
    if dark:
        rr(im,(82,33,272,108),20,'#effbf4');paste(im,resized('logo',150),(102,39))
    else:paste(im,resized('logo',160),(96,39))
    stage=0 if t<10 else 1 if t<20 else 2 if t<45 else 3 if t<55 else 4
    labels=['01 · EL PROBLEMA','02 · UN ACOMPAÑANTE','03 · DEMO DEL GRUPO','04 · PROTOTIPO ANDROID','05 · EL PRÓXIMO PASO']
    text(im,(1820,57),labels[stage],21,'#a9e3ca' if dark else MUTED,700,'ra')
    d=ImageDraw.Draw(im);d.line((96,126,1824,126),fill=rgba('#8bbaa5' if dark else '#b8d4c6',90),width=1)
    d.line((96,1063,1824,1063),fill=rgba('#72a18a' if dark else '#abc6b7',110),width=3)
    d.line((96,1063,96+1728*t/60,1063),fill=TEAL,width=5)

def draw_problem(t):
    im=background().copy();d=ImageDraw.Draw(im)
    d.ellipse((1030,160,1860,990),fill='#dceee1')
    a=ease(t/.8);portrait=resized('marta',795)
    paste(im,portrait,(1050+75*(1-a),155+5*math.sin(t*.7)),a)
    pill(im,(100,195),'MARTA · 72 AÑOS',size=22)
    if t<5.7:
        para(im,(96,310),'Quiere estar\nmás cerca.'.replace('\n',' '),88,880,INK,800,1.13)
        text(im,(100,570),'Un grupo de WhatsApp con sus nietos.',31,MUTED,500)
        pill(im,(100,660),'Un deseo cotidiano.',bg='#ffffff',size=23)
    else:
        v=ease((t-5.7)/.5)
        para(im,(96,305),'¿Y si toco\nalgo mal?'.replace('\n',' '),88,850,INK,800,1.14)
        text(im,(100,572),'No sabe por dónde empezar.',32,MUTED,500)
        rr(im,(100,669,850,784),22,'#ffffff')
        text(im,(130,697),'Necesita a alguien que la acompañe.',29,INK,600)
        ImageDraw.Draw(im).line((130,756,130+650*v,756),fill=TEAL,width=4)
    text(im,(100,875),'Una historia ilustrativa. Una barrera real.',21,MUTED,500)
    return im

def draw_solution(t):
    u=t-10;im=background(True).copy();d=ImageDraw.Draw(im)
    for i in range(3):
        r=260+i*65+8*math.sin(u*.5+i);d.ellipse((1490-r,570-r,1490+r,570+r),outline=rgba('#6de4ba',30),width=2)
    a=ease(u/.7)
    para(im,(96,250+30*(1-a)),'La ayuda justa, cuando la necesitás.',84,1040,'#f0fff5',800,1.16)
    x=96
    for label in ['POR VOZ','EN PANTALLA','PASO A PASO']:
        x+=pill(im,(x,590),label,bg='#244e40',fg='#adf8d4',size=22)+18
    text(im,(100,745),'Acompañar, hasta que pueda hacerlo sola.',31,'#bce4cf',500)
    shadow=Image.new('RGBA',(W,H));ImageDraw.Draw(shadow).ellipse((1290,905,1730,941),fill=rgba('#000000',50));im.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(20)))
    paste(im,resized('ghost',620),(1190,232+14*math.sin(u*1.3)),a)
    return im

def video_montage():
    meta=json.loads((WORK/'capture/shots.json').read_text());shots={s['id']:s for s in meta['shots']}
    shots['listening']['start']=5;shots['listening']['end']=10
    filters=[];n=len(STORY['demoShots'])
    # Playwright video uses CSS pixels, independently of screenshot deviceScaleFactor.
    # Crop to the actual viewport before scaling; older recordings may contain padding.
    vw,vh=meta['viewport']['width'],meta['viewport']['height']
    filters.append(f'[0:v]crop={vw}:{vh}:0:0,split={n}'+''.join(f'[s{i}]' for i in range(n)))
    for i,s in enumerate(STORY['demoShots']):
        raw=shots[s['id']];frames=round((s['end']-20)*FPS)-round((s['start']-20)*FPS)
        duration=frames/FPS;source=raw['end']-raw['start']
        filters.append(f"[s{i}]trim=start={raw['start']}:end={raw['end']},setpts=(PTS-STARTPTS)*{duration/source},fps={FPS},tpad=stop_mode=clone:stop_duration=1,trim=end_frame={frames},setpts=N/({FPS}*TB),scale=780:1688,setsar=1[v{i}]")
    filters.append(''.join(f'[v{i}]' for i in range(n))+f'concat=n={n}:v=1:a=0[out]')
    run(['-i',WORK/'capture/raw-demo.webm','-filter_complex',';'.join(filters),'-map','[out]','-an','-c:v','libx264','-preset','fast','-crf','18','-pix_fmt','yuv420p',WORK/'montage.mp4'])
    return shots

def trim_voice(clip):
    src=WORK/'voices'/f"{clip['id']}.wav"
    with wave.open(str(src)) as w:sr=w.getframerate();pcm=np.frombuffer(w.readframes(w.getnframes()),dtype=np.int16)
    indices=np.flatnonzero(np.abs(pcm.astype(np.int32))>85)
    lo=max(0,int(indices[0])-int(sr*.06));hi=min(len(pcm),int(indices[-1])+int(sr*.1))
    trimmed=WORK/'voices'/f"{clip['id']}-trim.wav"
    with wave.open(str(trimmed),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(sr);w.writeframes(pcm[lo:hi].tobytes())
    duration=(hi-lo)/sr;slot=clip['end']-clip['start'];tempo=max(.85,duration/slot)
    dest=WORK/'voices'/f"{clip['id']}-fit.wav"
    run(['-i',trimmed,'-af',f'atempo={tempo},apad,atrim=duration={slot},afade=t=in:d=0.018,afade=t=out:st={slot-.045}:d=0.045,loudnorm=I=-18:TP=-2:LRA=7','-ar','48000','-ac','1','-c:a','pcm_s16le',dest])
    with wave.open(str(dest)) as w:out=np.frombuffer(w.readframes(w.getnframes()),dtype=np.int16).astype(np.float32)/32768
    return out,{'id':clip['id'],'sourceSeconds':round(duration,3),'slotSeconds':round(slot,3),'tempo':round(tempo,3)}

def make_audio():
    sr=48000;length=sr*60;mix=np.zeros((length,2),np.float32);duck=np.ones(length,np.float32);report=[];envelopes={}
    for clip in STORY['voices']:
        pcm,info=trim_voice(clip);report.append(info);start=round(clip['start']*sr);end=min(length,start+len(pcm));mix[start:end,:]+=pcm[:end-start,None]
        duck[max(0,start-12000):min(length,end+10000)]=.35
        envelopes[clip['id']]=np.sqrt(np.mean(np.pad(pcm,(0,(-len(pcm))%1200)).reshape(-1,1200)**2,axis=1)).tolist()
    music=np.zeros(length,np.float32);beat=60/100
    scale=[[130.81,164.81,196,261.63],[110,130.81,164.81,220],[87.31,130.81,174.61,220],[98,146.83,196,246.94]]
    for k in range(200):
        start=round(k*beat*.5*sr)
        if start>=length:break
        chord=scale[(k//16)%4];freq=chord[(k*3)%4];n=min(round(.9*sr),length-start);t=np.arange(n)/sr
        env=(1-np.exp(-t*90))*np.exp(-t*6)
        tone=(np.sin(2*np.pi*freq*t)+.23*np.sin(4*np.pi*freq*t)+.1*np.sin(6*np.pi*freq*t))*env*.017
        music[start:start+n]+=tone
    for k in range(100):
        start=round(k*beat*sr)
        if start>=length:break
        n=min(round(.13*sr),length-start);t=np.arange(n)/sr
        music[start:start+n]+=.013*np.sin(2*np.pi*(65*t-25*t*t))*np.exp(-t*32)
    fade=np.minimum(np.arange(length)/(sr*1.5),(length-1-np.arange(length))/(sr*1.1)).clip(0,1)
    music*=duck*fade;mix[:,0]+=music;mix[:,1]+=np.roll(music,240)
    for stamp in [10,20,25.8,27.1,29.05,30.8,33.4,36.85,39.5,45,55]:
        start=round(stamp*sr);n=round(.13*sr);t=np.arange(n)/sr
        effect=.035*np.sin(2*np.pi*(950*t+150*t*t))*np.exp(-t*37)
        mix[start:start+n,:]+=effect[:,None]
    n=round(1.3*sr);t=np.arange(n)/sr;chime=sum(np.sin(2*np.pi*f*t)*np.exp(-t*(3+i)) for i,f in enumerate([523.25,659.25,783.99]))*.018
    at=round(59.1*sr);mix[at:min(length,at+n),:]+=chime[:length-at,None]
    mix*=np.minimum(1,(length-1-np.arange(length))/(sr*.3)).clip(0,1)[:,None]
    peak=float(np.abs(mix).max());mix*=min(1,.92/max(peak,.001))
    dest=WORK/'mix.wav'
    with wave.open(str(dest),'wb') as w:w.setnchannels(2);w.setsampwidth(2);w.setframerate(sr);w.writeframes((mix.clip(-1,1)*32767).astype(np.int16).tobytes())
    (WORK/'audio-report.json').write_text(json.dumps({'clips':report,'peakBeforeLimiter':peak,'syntheticVoices':True},indent=2))
    return envelopes

def subtitle_events():
    events=[]
    for clip in STORY['voices']:
        words=clip['text'].split();chunks=[];line=''
        for word in words:
            proposal=(line+' '+word).strip()
            if len(proposal)>72 and line:chunks.append(line);line=word
            else:line=proposal
        if line:chunks.append(line)
        cursor=clip['start'];total=sum(len(c) for c in chunks)
        for chunk in chunks:
            duration=(clip['end']-clip['start'])*len(chunk)/total
            events.append({'start':cursor,'end':cursor+duration,'text':chunk,'role':clip['role']});cursor+=duration
    def stamp(t):
        n=round(t*1000);return f'{n//3600000:02}:{n//60000%60:02}:{n//1000%60:02},{n%1000:03}'
    (OUT/'TINO-pitch-60s.srt').write_text('\n'.join(f"{i+1}\n{stamp(e['start'])} --> {stamp(e['end'])}\n{e['text']}\n" for i,e in enumerate(events)),encoding='utf8')
    return events

def subtitles(im,t,events):
    for event in events:
        if event['start']<=t<event['end']:
            lines=wrap(event['text'],31,1450,600);wid=max(font(31,600).getlength(s) for s in lines)
            h=48 if len(lines)==1 else 85;y=1000-h/2
            rr(im,(W/2-wid/2-28,y-8,W/2+wid/2+28,y+h),17,rgba('#102a24',242))
            color='#b9ffe1' if event['role']=='tino' else '#ffe6b5' if event['role']=='marta' else '#ffffff'
            for i,line in enumerate(lines):text(im,(W/2,y+3+i*38),line,31,color,600,'ma')
            break

@lru_cache(None)
def phone_shadow():
    im=Image.new('RGBA',(W,H));d=ImageDraw.Draw(im);d.rounded_rectangle((121,150,538,990),45,fill=rgba('#173c30',55));return im.filter(ImageFilter.GaussianBlur(22))

def phone(im,frame):
    im.alpha_composite(phone_shadow());rr(im,(118,141,532,985),44,'#18221f',outline='#718279',width=2)
    screen=frame.resize((380,822),Image.Resampling.LANCZOS).convert('RGBA');mask=Image.new('L',screen.size);ImageDraw.Draw(mask).rounded_rectangle((0,0,379,821),radius=28,fill=255);screen.putalpha(mask);paste(im,screen,(135,154))
    rr(im,(280,144,370,151),4,'#53635c')

def draw_demo(t,frame,raw,envelopes):
    im=background().copy();phone(im,frame)
    shot=next(s for s in STORY['demoShots'] if s['start']<=t<s['end']);idx=STORY['demoShots'].index(shot)
    pill(im,(650,165),('MARTA · VOZ RECREADA' if idx==0 else f'{idx:02} · UN PASO A LA VEZ'),size=19)
    para(im,(650,245),shot['title'],64,1160,INK,800,1.15)
    text(im,(652,344),shot['comment'],27,MUTED,500)
    rr(im,(645,410,1819,892),28,'#ffffff',outline='#b5dbc9',width=2)
    if idx==0:
        para(im,(690,460),'«Hola TINO, quiero crear un grupo de WhatsApp con mis nietos».',48,900,INK,700,1.34)
        env=envelopes['marta'];ix=min(len(env)-1,max(0,int((t-20.05)*40)));energy=env[ix] if ix>=0 else 0
        d=ImageDraw.Draw(im)
        for i in range(30):
            h=9+(18+energy*230)*(.3+.7*abs(math.sin(i*.7+t*12)));x=698+i*19;d.rounded_rectangle((x,791-h/2,x+8,791+h/2),4,fill=TEAL)
    else:
        cx,cy,cw,ch=shot['focus'];zoom=1+.025*smooth((t-shot['start'])/(shot['end']-shot['start']))
        cw/=zoom;ch/=zoom;box=(max(0,cx-cw/2),max(0,cy-ch/2),min(390,cx+cw/2),min(844,cy+ch/2))
        ratio=frame.width/390
        box=tuple(round(v*ratio) for v in box)
        crop=ImageOps.fit(frame.crop(box),(1156,464),method=Image.Resampling.LANCZOS).convert('RGBA')
        mask=Image.new('L',crop.size);ImageDraw.Draw(mask).rounded_rectangle((0,0,crop.width-1,crop.height-1),22,fill=255);crop.putalpha(mask);paste(im,crop,(654,419))
        source=raw.get(shot['id'],{}).get('target')
        if source:
            y=154+(source['y']+source['height']/2)*822/844
            d=ImageDraw.Draw(im);d.line((535,y,587,y,625,652,645,652),fill=rgba('#00b992',155),width=3);d.ellipse((529,y-6,541,y+6),fill=TEAL)
    text(im,(654,914),'CON SU PERMISO',18,MUTED,700)
    text(im,(1010,914),'EN SEGUNDO PLANO',18,MUTED,700)
    text(im,(1430,914),'CADA TOQUE ES DE MARTA',18,MUTED,700)
    text(im,(1820,102),STORY['disclosure'],15,MUTED,500,'ra')
    return im

def check(im,xy,size=36,color=TEAL):
    x,y=xy;ImageDraw.Draw(im).line((x,y+size*.5,x+size*.35,y+size*.83,x+size,y),fill=color,width=5)

def draw_validation(t):
    im=background(True).copy();u=t-45
    para(im,(96,200),'Un prototipo. Una posibilidad real.',78,1560,'#f0fff5',800,1.15)
    text(im,(100,350),'Validación técnica: pantalla → controles → guía.',30,'#bce4cf',500)
    cards=[(96,'Android funcional','Un prototipo que ya corre en Android.'),(688,'Entiende el contexto','Lee la pantalla y reconoce sus controles.'),(1280,'Guía sin reemplazar','La persona realiza cada acción.')]
    for i,(x,title,body) in enumerate(cards):
        a=ease((u-i*.12)/.6);y=465+35*(1-a);rr(im,(x,y,x+540,y+390),26,'#1d4235',outline='#3c6653',width=1)
        check(im,(x+28,y+29));text(im,(x+28,y+88),title,31,'#effff4',700);para(im,(x+28,y+145),body,24,440,'#b5d8c2',500)
        if i==0:
            src=asset('native');thumb=src.resize((99,158),Image.Resampling.LANCZOS);paste(im,thumb,(x+398,y+205))
            text(im,(x+28,y+278),'Voz + chat',24,'#a7f4d0',600);text(im,(x+28,y+320),'Permisos de Android',20,'#b5d8c2',500)
        elif i==1:
            for j,label in enumerate(['Permiso','Pantalla','Control']):
                yy=y+235+j*40;rr(im,(x+28,yy,x+480,yy+32),9,'#2f5947');text(im,(x+44,yy+3),label,19,'#d5ffe6',600)
        else:
            text(im,(x+28,y+257),'TINO orienta.',29,'#a7f4d0',700);text(im,(x+28,y+309),'Marta decide.',29,'#f3fff6',700)
    return im

def draw_close(t):
    im=Image.new('RGBA',(W,H),'#d5fff0');u=t-55;d=ImageDraw.Draw(im);d.ellipse((1190,45,2160,1040),fill='#b0f1d5')
    paste(im,resized('logo',340),(96,184))
    para(im,(96,395),'La tecnología es de todos.',88,1160,INK,800,1.12)
    text(im,(100,650),'Próxima semana: personas mayores + una institución.',27,'#356a55',500)
    pill(im,(98,755),'PROBÁ LA DEMO',bg='#103c2b',fg='#d5ffe8',size=19)
    text(im,(100,831),'tino-web.vercel.app',36,INK,700)
    paste(im,resized('ghost',540),(1280,245+10*math.sin(u)),ease(u/.6))
    return im

def render(preview=None):
    events=subtitle_events()
    if preview is not None:
        frame=Image.open(WORK/'capture/next.png').convert('RGB');raw={s['id']:s for s in json.loads((WORK/'capture/shots.json').read_text())['shots']}
        envelopes={'marta':[.1]*200}
        im=draw_problem(preview) if preview<10 else draw_solution(preview) if preview<20 else draw_demo(preview,frame,raw,envelopes) if preview<45 else draw_validation(preview) if preview<55 else draw_close(preview)
        header(im,preview,10<=preview<20 or 45<=preview<55);subtitles(im,preview,events);im.convert('RGB').save(OUT/f'preview-{preview}.jpg',quality=92);return
    raw=video_montage();envelopes=make_audio()
    decoder=subprocess.Popen([FF,'-hide_banner','-loglevel','error','-i',str(WORK/'montage.mp4'),'-f','rawvideo','-pix_fmt','rgb24','pipe:1'],stdout=subprocess.PIPE)
    dest=OUT/'TINO-pitch-60s-1080p.mp4'
    log=open(WORK/'encode.log','w')
    encoder=subprocess.Popen([FF,'-hide_banner','-y','-f','rawvideo','-pix_fmt','rgb24','-s',f'{W}x{H}','-r',str(FPS),'-i','pipe:0','-i',str(WORK/'mix.wav'),'-c:v','libx264','-preset','fast','-crf','23','-pix_fmt','yuv420p','-c:a','aac','-b:a','160k','-af','loudnorm=I=-16:TP=-1.5:LRA=8','-t','60','-movflags','+faststart',str(dest)],stdin=subprocess.PIPE,stdout=log,stderr=log)
    frame=Image.open(WORK/'capture/launcher.png').convert('RGB');bytes_per_frame=780*1688*3
    try:
        for n in range(FPS*60):
            t=n/FPS
            if 20<=t<45:
                data=decoder.stdout.read(bytes_per_frame)
                if len(data)!=bytes_per_frame:raise RuntimeError('Browser montage ended before 25 seconds.')
                frame=Image.frombytes('RGB',(780,1688),data)
            if t<10:im=draw_problem(t)
            elif t<20:im=draw_solution(t)
            elif t<45:im=draw_demo(t,frame,raw,envelopes)
            elif t<55:im=draw_validation(t)
            else:im=draw_close(t)
            header(im,t,10<=t<20 or 45<=t<55);subtitles(im,t,events)
            encoder.stdin.write(im.convert('RGB').tobytes())
            if n%300==0:print('RENDER_SECONDS',round(t),flush=True)
    finally:
        encoder.stdin.close();encoder.wait();decoder.stdout.close();decoder.wait();log.close()
    if encoder.returncode:raise RuntimeError((WORK/'encode.log').read_text()[-3000:])
    run(['-i',dest,'-vf','scale=1280:720','-c:v','libx264','-preset','slow','-crf','26','-c:a','aac','-b:a','128k','-movflags','+faststart',OUT/'TINO-pitch-60s-web.mp4'])
    run(['-ss','11.4','-i',dest,'-frames:v','1','-q:v','2',OUT/'TINO-pitch-poster.jpg'])
    print('VIDEO_READY',dest,dest.stat().st_size,flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--preview',type=float);args=parser.parse_args();render(args.preview)
