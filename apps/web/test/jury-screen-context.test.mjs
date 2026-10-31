import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {transform} from 'esbuild';
import {JSDOM} from 'jsdom';

const {code}=await transform(await readFile(new URL('../src/jury-screen-context.ts',import.meta.url),'utf8'),{loader:'ts',format:'esm',target:'es2022'});
const {juryScreenContext}=await import('data:text/javascript;base64,'+Buffer.from(code).toString('base64'));
function fixture(t,html){const dom=new JSDOM(html);t.after(()=>dom.window.close());return dom.window.document.body;}

test('contact avatars never prefix names in assistant context and visible contacts stay unchanged',t=>{
 const screen=fixture(t,'<section><p>Elegí las personas.</p>'+['Lucía','Ana','Pedro','Martín'].map(name=>`<button><span class="jp-avatar" aria-hidden="true">${name[0]}</span>${name}</button>`).join('')+'<button>Siguiente</button></section>');
 const original=screen.innerHTML;
 assert.equal(juryScreenContext(screen),'Elegí las personas. Lucía Ana Pedro Martín Siguiente');
 assert.equal(screen.innerHTML,original);assert.equal(screen.querySelectorAll('.jp-avatar').length,4);
});

test('legacy two-letter avatars are excluded without modifying legitimate repeated letters in names',t=>{
 const screen=fixture(t,'<button><span class="d-avatar">LU</span><strong>Lucía</strong><small>Mensaje</small></button><button><span class="jp-avatar">A</span>Anna</button><button>Aarón</button><button>Lloyd</button>');
 assert.equal(juryScreenContext(screen),'Lucía Mensaje Anna Aarón Lloyd');
});

test('hidden content and decorative SVG text cannot enter screen context',t=>{
 const screen=fixture(t,'<section><span aria-hidden="true">A</span><strong>Ana</strong><div hidden>Otro chat</div><svg><title>Decoración</title></svg><script>hidden script</script><style>.hidden{}</style></section>');
 assert.equal(juryScreenContext(screen),'Ana');assert.equal(juryScreenContext(null),'');
 assert.equal(juryScreenContext(screen.querySelector('[hidden]')),'');
});

test('screen context retains its size limit and normalizes whitespace between separate labels',t=>{
 const screen=fixture(t,'<p>  Ana\n\t  Pedro </p><p>'+ 'x'.repeat(2100)+'</p>');
 const context=juryScreenContext(screen);assert.equal(context.length,2000);assert.ok(context.startsWith('Ana Pedro '));
});
