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

function savedPhoto(h){
 h.w.ensureShelf();
 h.set("captureRecord={id:'photo-1',session_id:draft.session_id,location:'A1',operator:'Test operator',captured_at:'2026-11-19T12:00:00Z',uploaded:true};recent=[{...captureRecord}];resultImage='data:image/jpeg;base64,dGVzdA==';result={status:'needs_review',proposals:[],scene_note:'Test photo'}");
 h.w.renderResult();h.w.renderHistory();
 return h.read('captureRecord');
}
test('delete controls identify the photo and cancel leaves it untouched',async t=>{
 const h=harness(t);const photo=savedPhoto(h);let message='';
 assert.equal(h.el('deletePhoto').classList.contains('hidden'),false);
 assert.match(h.el('history').textContent,/Delete photo/);
 h.w.confirm=text=>{message=text;return false};
 await h.w.deletePhoto(photo);
 assert.match(message,/A1/);assert.match(message,/Saved stock quantities/);
 assert.equal(h.calls.length,0);assert.equal(h.read('captureRecord.id'),'photo-1');
});
test('confirmed deletion clears the photo and affected drafts, preserving other counts',async t=>{
 const h=harness(t);const photo=savedPhoto(h);
 h.set("draft.lines=[{sku:'TEST-1',quantity:3,unit:'packs',evidence_ids:['photo-1']},{sku:'TEST-2',quantity:5,unit:'packs',evidence_ids:['photo-2']}];catalog[0].reference_photo='photo-1'");
 h.el('confirmed').checked=true;await h.w.deletePhoto(photo);
 assert.equal(h.calls.filter(c=>c.m==='DELETE'&&c.p==='/api/captures/photo-1').length,1);
 assert.equal(h.calls.filter(c=>c.p==='/api/sheets/sync').length,1);
 assert.equal(h.calls.filter(c=>c.p==='/api/commits').length,0);
 assert.equal(h.read('captureRecord'),null);assert.equal(h.read('resultImage'),'');assert.equal(h.read('result'),null);
 assert.equal(h.read('draft.lines.length'),1);assert.equal(h.read('draft.lines[0].sku'),'TEST-2');
 assert.equal(h.read('catalog[0].reference_photo'),'');assert.equal(h.el('confirmed').checked,false);
 assert.equal(h.read('recent.length'),0);assert.match(h.el('history').textContent,/No uploaded photos/);
 assert.equal(JSON.parse(h.w.localStorage.getItem('ht_shelf_draft')).lines[0].sku,'TEST-2');
 assert.equal(h.el('deletePhoto').classList.contains('hidden'),true);assert.match(h.el('resultHint').textContent,/Start live video/);
});
test('failed deletion preserves the selected photo and draft for retry',async t=>{
 const h=harness(t);const photo=savedPhoto(h);h.set("draft.lines=[{sku:'TEST-1',quantity:3,unit:'packs',evidence_ids:['photo-1']}]");
 h.w.api=async()=>{throw new Error('Connection interrupted')};await h.w.deletePhoto(photo);
 assert.equal(h.read('captureRecord.id'),'photo-1');assert.equal(h.read('draft.lines.length'),1);assert.equal(h.read('recent.length'),1);
 assert.match(h.el('message').textContent,/Retry Delete photo/);assert.equal(h.el('deletePhoto').disabled,false);
});
test('Sheets failure does not undo a successful photo deletion',async t=>{
 const h=harness(t);const photo=savedPhoto(h);
 h.w.api=async(p,m)=>{if(m==='DELETE')return {deleted:true};throw new Error('Sheets unavailable')};
 await h.w.deletePhoto(photo);
 assert.equal(h.read('captureRecord'),null);assert.equal(h.read('recent.length'),0);
 assert.match(h.el('message').textContent,/Photo deleted/);assert.match(h.el('message').textContent,/Sheets update needs retry/);
});
test('an uncertain saved count must be resolved before deleting evidence',async t=>{
 const h=harness(t);const photo=savedPhoto(h);h.set("draft.pending_commit={id:'commit-pending',lines:[]}");
 let confirmed=false;h.w.confirm=()=>{confirmed=true;return true};h.w.updateControls();
 assert.equal(h.el('deletePhoto').disabled,true);assert.ok([...h.el('history').querySelectorAll('button')].every(b=>b.disabled));
 await h.w.deletePhoto(photo);assert.equal(confirmed,false);assert.equal(h.calls.length,0);
 assert.match(h.el('message').textContent,/pending shelf/);
});
test('a late photo-list response cannot restore a deleted photo',async t=>{
 const h=harness(t);const photo=savedPhoto(h);let resolve;
 h.w.api=async(p)=>p.startsWith('/api/captures?')?new Promise(r=>resolve=r):{};
 const refresh=h.w.loadRecent();await h.w.deletePhoto(photo);resolve([photo]);await refresh;
 assert.equal(h.read('recent.length'),0);assert.match(h.el('history').textContent,/No uploaded photos/);
});

test('definitively rejected evidence unlocks counts for correction instead of trapping retries',async t=>{
 const h=harness(t);savedPhoto(h);h.set("draft.lines=[{sku:'TEST-1',quantity:3,unit:'packs',evidence_ids:['photo-1']}]");h.el('confirmed').checked=true;
 h.w.api=async()=>{const error=new Error('The photo is deleted. Review a new photo.');error.status=422;throw error};
 await h.w.finishShelf();assert.equal(h.read('draft.pending_commit'),undefined);
 assert.equal(h.read('draft.lines.length'),1);assert.equal(h.el('confirmed').checked,false);
 assert.equal(h.el('lines').querySelector('button').disabled,false);
 assert.match(h.el('saving').textContent,/Correct or remove/);
});

function ingredientCatalogue(h){
 h.set("catalog=[{sku:'CHICKPEAS',name:'Organic Chickpeas',unit:'kg',category:'Pantry'},{sku:'MILK',name:'Almond Milk',unit:'L',category:'Dairy alternatives'},{sku:'RICE',name:'Basmati Rice',unit:'kg',category:'Pantry'},{sku:'CUMIN',name:'Whole Cumin Seeds',unit:'kg',category:'Spices'}]");
}
test('unknown AI items prompt closest matches without automatically selecting an item',t=>{
 const h=harness(t);savedPhoto(h);ingredientCatalogue(h);
 const proposal={...h.proposal,sku:null,label:'Chikpeas'};
 h.set('result='+JSON.stringify({status:'needs_review',proposals:[proposal],scene_note:'Review'}));h.w.renderResult();
 assert.match(h.el('proposals').textContent,/Match or add item/);
 h.w.openCount(proposal);
 assert.equal(h.el('itemSearch').value,'Chikpeas');assert.equal(h.el('item').value,'');
 assert.equal(h.el('quantity').value,'');assert.match(h.el('itemMatches').textContent,/Organic Chickpeas/);
 assert.match(h.el('matchHint').textContent,/No exact name/);assert.equal(h.el('addNewItem').textContent,'Add new item');
 assert.equal(h.calls.length,0);
});
test('closest matches rank spelling variants and require an explicit choice with correct units',t=>{
 const h=harness(t);savedPhoto(h);ingredientCatalogue(h);
 assert.equal(h.w.closestItems('chikpeas')[0].sku,'CHICKPEAS');assert.equal(h.w.closestItems('basmati ric')[0].sku,'RICE');
 assert.ok(h.w.closestItems('milk').length<=3);assert.equal(h.w.closestItems('zzzzqqqqvv').length,0);
 h.w.openCount({...h.proposal,sku:null,label:'almond milkk'});
 h.el('itemMatches').querySelector('button').click();
 assert.equal(h.el('item').value,'MILK');assert.equal(h.el('unit').value,'L');assert.equal(h.el('unit').readOnly,true);
 assert.equal(h.el('quantity').value,'');assert.equal(h.calls.length,0);
});
test('searching after selecting an item clears stale selection and quantity',t=>{
 const h=harness(t);savedPhoto(h);h.w.openCount(h.proposal);
 assert.equal(h.el('item').value,'TEST-1');h.el('itemSearch').value='Completely unknown item';h.el('itemSearch').oninput();
 assert.equal(h.el('item').value,'');assert.equal(h.el('quantity').value,'');
});
test('new item cancel and missing unit do not create or sync anything',async t=>{
 const h=harness(t);savedPhoto(h);h.w.openCount({...h.proposal,sku:null,label:'New Ingredient'});h.w.openNewItem();
 assert.equal(h.el('newItemName').value,'New Ingredient');assert.equal(h.el('newItemUnit').value,'');
 await h.w.createCatalogItem({preventDefault(){}});assert.equal(h.calls.length,0);
 assert.match(h.el('newItemStatus').textContent,/counting unit/);
 h.el('cancelNewItem').click();assert.equal(h.el('newItemDialog').open,false);assert.equal(h.el('countDialog').open,true);
});
test('creating an item syncs Sheets and selects it without saving stock or inferring weight',async t=>{
 const h=harness(t);savedPhoto(h);h.w.openCount({...h.proposal,sku:null,label:'New Ingredient'});h.w.openNewItem();
 h.el('newItemUnit').value='kg';h.el('newItemCategory').value='Pantry';const calls=[];
 h.w.api=async(p,m,body)=>{calls.push({p,m,body});return p==='/api/items'?{...body}:{synced:true}};
 await h.w.createCatalogItem({preventDefault(){}});
 assert.equal(calls.filter(c=>c.p==='/api/items'&&c.m==='POST').length,1);assert.equal(calls.filter(c=>c.p==='/api/sheets/sync').length,1);
 assert.equal(calls.filter(c=>c.p==='/api/commits').length,0);assert.equal(h.read('draft.lines.length'),0);
 assert.match(h.el('item').value,/^HT-/);assert.equal(h.el('unit').value,'kg');assert.equal(h.el('quantity').value,'');
 assert.equal(h.el('newItemDialog').open,false);assert.match(h.el('itemStatus').textContent,/Google Sheets/);
 h.el('quantity').value='2.5';h.w.saveLine({preventDefault(){}});assert.equal(h.read('draft.lines[0].quantity'),2.5);
});
test('a timed out create retries the same SKU and double clicks do not create twice',async t=>{
 const h=harness(t);savedPhoto(h);h.w.openCount({...h.proposal,sku:null,label:'New Ingredient'});h.w.openNewItem();h.el('newItemUnit').value='packs';
 const bodies=[];let release;
 h.w.api=async(p,m,body)=>{if(p==='/api/items'){bodies.push(JSON.stringify(body));return new Promise((resolve,reject)=>release=()=>reject(new Error('Timed out')))}return {}};
 const first=h.w.createCatalogItem({preventDefault(){}});await h.w.createCatalogItem({preventDefault(){}});
 assert.equal(bodies.length,1);assert.equal(h.el('cancelNewItem').disabled,true);release();await first;
 assert.equal(h.el('newItemDialog').open,true);assert.equal(h.el('cancelNewItem').disabled,false);
 h.w.api=async(p,m,body)=>{if(p==='/api/items'){bodies.push(JSON.stringify(body));return body}return {synced:true}};
 await h.w.createCatalogItem({preventDefault(){}});assert.equal(bodies.length,2);assert.equal(bodies[0],bodies[1]);
});
test('duplicate conflicts offer the existing item without silently choosing it',async t=>{
 const h=harness(t);savedPhoto(h);h.w.openCount({...h.proposal,sku:null,label:'Added on another device'});h.w.openNewItem();h.el('newItemUnit').value='packs';
 h.w.api=async()=>{const error=new Error('Item already exists');error.status=409;error.detail={existing_item:{sku:'REMOTE',name:'Added on another device',unit:'kg'}};throw error};
 await h.w.createCatalogItem({preventDefault(){}});
 assert.equal(h.el('item').value,'');assert.equal(h.el('newItemDialog').open,true);assert.match(h.el('newItemMatches').textContent,/Use existing/);
 h.el('newItemMatches').querySelector('button').click();assert.equal(h.el('item').value,'REMOTE');assert.equal(h.el('unit').value,'kg');
});
test('a saved new item remains usable after Sheets failure',async t=>{
 const h=harness(t);savedPhoto(h);h.w.openCount({...h.proposal,sku:null,label:'New Ingredient'});h.w.openNewItem();h.el('newItemUnit').value='packs';
 h.w.api=async(p,m,body)=>{if(p==='/api/items')return body;throw new Error('Sheets unavailable')};
 await h.w.createCatalogItem({preventDefault(){}});
 assert.match(h.el('item').value,/^HT-/);assert.equal(h.el('quantity').value,'3');assert.equal(h.el('newItemDialog').open,false);
 assert.match(h.el('itemStatus').textContent,/Item added/);assert.match(h.el('itemStatus').textContent,/needs retry/);
});
