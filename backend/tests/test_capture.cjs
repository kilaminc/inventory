const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {JSDOM}=require('jsdom');
const root=path.resolve(__dirname,'..');
function harness(t){
 const dom=new JSDOM(fs.readFileSync(path.join(root,'capture.html'),'utf8'),{url:'https://inventory.example/capture',runScripts:'outside-only'});
 const w=dom.window;t.after(()=>w.close());
 w.structuredClone=structuredClone;w.confirm=()=>true;
 w.HTMLDialogElement.prototype.showModal=function(){this.open=true};w.HTMLDialogElement.prototype.close=function(){this.open=false};
 w.HTMLMediaElement.prototype.play=async()=>{};
 w.HTMLCanvasElement.prototype.getContext=function(){return {drawImage(){},getImageData(){return {data:new Uint8ClampedArray(32*24*4).fill(50)}}}};
 w.HTMLCanvasElement.prototype.toDataURL=()=> 'data:image/jpeg;base64,c3ludGhldGljLWltYWdl';
 const video=w.document.getElementById('video');Object.defineProperties(video,{videoWidth:{value:1600},videoHeight:{value:1200}});
 let stopped=0;Object.defineProperty(w.navigator,'mediaDevices',{value:{getUserMedia:async()=>({getTracks:()=>[{stop(){stopped++}}]})}});
 require('node:vm').runInContext(fs.readFileSync(path.join(root,'capture.js'),'utf8'),dom.getInternalVMContext());
 const calls=[];const proposal={sku:'TEST-1',label:'Test packs',visible_packs:3,open_container:false,evidence_text:'Three sealed packs',question:''};
 const result={status:'needs_review',proposals:[proposal],scene_note:'Three visible test packs.'};
 w.api=async(p,m='GET',body)=>{calls.push({p,m,body:body&&structuredClone(body)});if(p==='/api/status')return {vision_ready:true,sheets_ready:true};if(p==='/api/items')return [{sku:'TEST-1',name:'Test packs',unit:'packs'}];if(p.startsWith('/api/captures?'))return [];if(p.includes('/analyze')||p==='/api/vision/preview')return result;return {saved:true}};
 w.eval("catalog=[{sku:'TEST-1',name:'Test packs',unit:'packs'}]");
 w.document.getElementById('location').value='A1';w.document.getElementById('operator').value='Test operator';
 return {w,calls,proposal,result,stopped:()=>stopped,read:expression=>w.eval(expression),set:source=>w.eval(source),el:id=>w.document.getElementById(id)};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
test('live analysis uses preview only until the operator selects evidence',async t=>{
 const h=harness(t);await h.w.startLive();await flush();
 assert.equal(h.calls.filter(x=>x.p==='/api/vision/preview').length,1);
 assert.equal(h.calls.filter(x=>x.p==='/api/captures').length,0);
 assert.match(h.el('proposals').textContent,/3 visible packs/);
 await h.w.freezeView();
 assert.equal(h.calls.filter(x=>x.p==='/api/captures').length,1);
 assert.equal(h.calls.filter(x=>x.p.endsWith('/analyze')).length,1);
 assert.equal(h.calls.filter(x=>x.p==='/api/commits').length,0);
 assert.equal(h.stopped(),1);
});
test('live errors stop the camera and expose an actionable message',async t=>{
 const h=harness(t);h.w.api=async()=>{throw new Error('OpenAI API credits need attention')};
 await h.w.startLive();await flush();
 assert.equal(h.read('live'),false);assert.equal(h.stopped(),1);
 assert.match(h.el('message').textContent,/credits need attention/);
});
test('a later view replaces a SKU quantity and zero is a valid reviewed count',t=>{
 const h=harness(t);h.w.ensureShelf();h.set("captureRecord={id:'evidence-1',uploaded:true}");
 const save=n=>{h.w.openCount(h.proposal);h.el('quantity').value=n;h.w.saveLine({preventDefault(){}})};
 save('3');save('5');assert.equal(h.read('draft.lines.length'),1);assert.equal(h.read('draft.lines[0].quantity'),5);
 save('0');assert.equal(h.read('draft.lines[0].quantity'),0);assert.equal(h.el('confirmed').checked,false);
});
test('failed commit retries preserve the exact body and ID; Sheets failure does not lose saved stock',async t=>{
 const h=harness(t);h.w.ensureShelf();h.set("captureRecord={id:'evidence-1',uploaded:true}");h.w.openCount(h.proposal);h.el('quantity').value='3';h.w.saveLine({preventDefault(){}});h.el('confirmed').checked=true;
 const bodies=[];let fail=true;h.w.api=async(p,m,body)=>{if(p==='/api/commits'){bodies.push(JSON.stringify(body));if(fail)throw new Error('Network interrupted');return {saved:true}}if(p==='/api/sheets/sync')throw new Error('Sheets needs permission');return {}};
 await h.w.finishShelf();assert.equal(h.read('!!draft.pending_commit'),true);
 assert.equal(h.el('startLive').disabled,true);
 fail=false;await h.w.finishShelf();assert.equal(bodies.length,2);assert.equal(bodies[0],bodies[1]);
 assert.equal(h.read('draft'),null);assert.match(h.el('saving').textContent,/Counts are saved/);
});
test('unknown weights are not inferred from a package count',t=>{
 const h=harness(t);assert.equal(h.w.suggestQuantity({unit:'kg'},h.proposal),'');
 assert.equal(h.w.suggestQuantity({unit:'kg',pack_size:2,pack_unit:'kg'},h.proposal),'6');
});
test('no final counts can be saved without explicit review',async t=>{
 const h=harness(t);h.w.ensureShelf();h.set("draft.lines=[{sku:'TEST-1',quantity:3,unit:'packs'}]");
 await h.w.finishShelf();assert.equal(h.calls.filter(x=>x.p==='/api/commits').length,0);
});

test('voice rejects negative or ambiguous quantities and understands decimals',t=>{
 const h=harness(t);assert.equal(h.w.voiceQuantity('minus two'),null);assert.equal(h.w.voiceQuantity('-2'),null);
 assert.equal(h.w.voiceQuantity('2 bags of 5 kg'),null);assert.equal(h.w.voiceQuantity('two bags of five kg'),null);
 assert.equal(h.w.voiceQuantity('two point five kilograms'),2.5);assert.equal(h.w.voiceQuantity('twenty five packs'),25);
 assert.equal(h.w.voiceQuantity('1,000 pieces'),1000);assert.equal(h.w.voiceQuantity('one hundred'),100);
});
