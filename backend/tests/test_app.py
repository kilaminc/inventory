import base64,io,json,os,sys,tempfile,types,unittest,uuid
from unittest.mock import MagicMock,patch
from datetime import datetime,timezone,timedelta
os.environ['DATA_DIR']=tempfile.mkdtemp()
os.environ['DEVICE_TOKEN']='test-device'
from PIL import Image
from fastapi.testclient import TestClient
import app as module
# Synthetic fixtures keep private business catalogue data out of public source.
if not module.item_list():
 with module.db() as c:
  for i in range(2):
   item={'sku':f'TEST-{i+1:03d}','name':f'Test item {i+1}','unit':'pcs'}
   c.execute('INSERT INTO items VALUES (?,?)',(item['sku'],json.dumps(item)))

class InventoryTests(unittest.TestCase):
 def setUp(self):
  environment=patch.dict(os.environ,{'OPENAI_API_KEY':'','GOOGLE_SERVICE_ACCOUNT_JSON':'','GOOGLE_SHEET_ID':''})
  environment.start();self.addCleanup(environment.stop)
  module.VISION_CACHE.clear()
  self.client=TestClient(module.app);self.client.headers['Authorization']='Bearer test-device'
  self.session=str(uuid.uuid4());self.location='A-'+self.session[:8]
  image=io.BytesIO();Image.new('RGB',(64,64),'green').save(image,'JPEG');self.image=base64.b64encode(image.getvalue()).decode()
  self.sku=module.item_list()[0]['sku'];self.unit=module.item_list()[0].get('unit') or 'pcs'
 def capture(self):
  body=dict(id=str(uuid.uuid4()),session_id=self.session,location=self.location,operator='Pilot',
   captured_at=datetime.now(timezone.utc).isoformat(),image_base64=self.image,ocr='',barcodes=[])
  self.assertEqual(self.client.post('/api/captures',json=body).status_code,200);return body
 def commit_body(self,cap,quantity=3):
  return dict(id=str(uuid.uuid4()),session_id=self.session,location=self.location,operator='Pilot',
   counted_at=datetime.now(timezone.utc).isoformat(),review_confirmed=True,lines=[dict(sku=self.sku,quantity=quantity,
   unit=self.unit,source='visual_confirmed',evidence_ids=[cap['id']],note='')])
 def test_auth(self):
  self.assertEqual(TestClient(module.app).get('/api/items').status_code,401)
 def new_item_body(self, **overrides):
  key=str(uuid.uuid4())
  return {'sku':'NEW-'+key,'name':'New test ingredient '+key,'unit':'kg','category':'Produce',**overrides}
 def test_new_item_requires_auth_and_nonblank_name_and_unit(self):
  body=self.new_item_body()
  self.assertEqual(TestClient(module.app).post('/api/items',json=body).status_code,401)
  for fields in ({'name':'   '},{'name':'---'},{'unit':'   '},{'pack_size':2,'pack_unit':''}):
   with self.subTest(fields=fields):self.assertEqual(self.client.post('/api/items',json={**body,**fields}).status_code,422)
  self.assertNotIn(body['sku'],[i['sku'] for i in module.item_list()])
 def test_new_item_create_and_retry_preserve_stock_and_do_not_overwrite(self):
  cap=self.capture();body=self.new_item_body(reference_photo=cap['id'])
  stock=self.client.get('/api/stock').json();before=len(module.item_list())
  result=self.client.post('/api/items',json=body)
  self.assertEqual(result.status_code,200,result.text);self.assertEqual(result.json()['name'],body['name'])
  self.assertEqual(self.client.post('/api/items',json=body).json(),result.json())
  self.assertEqual(len(module.item_list()),before+1)
  self.assertEqual(self.client.get('/api/stock').json(),stock)
  changed=self.client.post('/api/items',json={**body,'unit':'packs'})
  self.assertEqual(changed.status_code,409);self.assertEqual(changed.json()['detail']['existing_item']['unit'],'kg')
  self.assertEqual(next(i for i in module.item_list() if i['sku']==body['sku'])['unit'],'kg')
 def test_normalized_duplicate_name_or_barcode_returns_existing_item(self):
  key=str(uuid.uuid4());body=self.new_item_body(name='Café   Beans '+key,barcode='BAR-'+key)
  self.assertEqual(self.client.post('/api/items',json=body).status_code,200)
  for other in (self.new_item_body(name='  CAFE BEANS '+key+' '),self.new_item_body(barcode=body['barcode'])):
   result=self.client.post('/api/items',json=other)
   self.assertEqual(result.status_code,409,result.text)
   self.assertEqual(result.json()['detail']['existing_item']['sku'],body['sku'])
   self.assertNotIn(other['sku'],[i['sku'] for i in module.item_list()])
 def test_new_item_rejects_deleted_reference_photo(self):
  cap=self.capture();self.client.delete('/api/captures/'+cap['id'])
  body=self.new_item_body(reference_photo=cap['id'])
  self.assertEqual(self.client.post('/api/items',json=body).status_code,422)
  self.assertNotIn(body['sku'],[i['sku'] for i in module.item_list()])
 def test_capture_retry_and_collision(self):
  b=self.capture();self.assertEqual(self.client.post('/api/captures',json=b).status_code,200)
  b['location']='Other';self.assertEqual(self.client.post('/api/captures',json=b).status_code,409)
 def test_photo_delete_requires_auth_and_unknown_ids_do_not_create_tombstones(self):
  cap=self.capture();url='/api/captures/'+cap['id']
  self.assertEqual(TestClient(module.app).delete(url).status_code,401)
  self.assertEqual(self.client.get('/api/evidence/'+cap['id']).status_code,200)
  unknown=str(uuid.uuid4())
  self.assertEqual(self.client.delete('/api/captures/'+unknown).status_code,404)
  self.assertEqual(self.client.delete('/api/captures/not-a-uuid').status_code,422)
  with module.db() as c:self.assertIsNone(c.execute('SELECT id FROM deleted_captures WHERE id=?',(unknown,)).fetchone())
 def test_delete_removes_photo_analysis_and_reference_and_blocks_offline_reupload(self):
  cap=self.capture();key=cap['id'];url='/api/captures/'+key
  self.assertEqual(self.client.post(url+'/analyze').status_code,200)
  original=next(i for i in module.item_list() if i['sku']==self.sku)
  self.assertEqual(self.client.put('/api/items/'+self.sku,json={**original,'reference_photo':key}).status_code,200)
  before=self.client.get('/api/status').json()['uploaded_photos']
  self.assertEqual(self.client.delete(url).json(),{'id':key,'deleted':True})
  self.assertEqual(self.client.delete(url).status_code,200)
  self.assertEqual(self.client.get('/api/evidence/'+key).status_code,404)
  self.assertEqual(self.client.post(url+'/analyze').status_code,404)
  self.assertEqual(self.client.post('/api/captures',json=cap).status_code,410)
  self.assertFalse((module.DATA/'evidence'/(key+'.jpg')).exists())
  self.assertNotIn(key,[x['id'] for x in self.client.get('/api/captures?limit=100').json()])
  self.assertEqual(self.client.get('/api/status').json()['uploaded_photos'],before-1)
  self.assertEqual(next(i for i in module.item_list() if i['sku']==self.sku)['reference_photo'],'')
  self.assertEqual(self.client.put('/api/items/'+self.sku,json={**original,'reference_photo':key}).status_code,422)
  with module.db() as c:self.assertIsNone(c.execute('SELECT * FROM analysis_failures WHERE capture_id=?',(key,)).fetchone())
 def test_deleting_saved_evidence_preserves_stock_and_idempotent_commits(self):
  cap=self.capture();body=self.commit_body(cap)
  self.assertEqual(self.client.post('/api/commits',json=body).status_code,200)
  before=self.client.get('/api/stock').json()
  self.assertEqual(self.client.delete('/api/captures/'+cap['id']).status_code,200)
  self.assertEqual(self.client.get('/api/stock').json(),before)
  self.assertTrue(self.client.post('/api/commits',json=body).json()['duplicate'])
  self.assertEqual(self.client.post('/api/commits',json=self.commit_body(cap,7)).status_code,422)
 def test_failed_file_delete_preserves_photo_and_all_metadata(self):
  cap=self.capture()
  with patch.object(module.Path,'unlink',side_effect=OSError('private-path-do-not-expose')):
   response=self.client.delete('/api/captures/'+cap['id'])
  self.assertEqual(response.status_code,503);self.assertNotIn('private-path',response.text)
  self.assertEqual(self.client.get('/api/evidence/'+cap['id']).status_code,200)
  with module.db() as c:self.assertIsNone(c.execute('SELECT id FROM deleted_captures WHERE id=?',(cap['id'],)).fetchone())
 def test_inflight_analysis_cannot_restore_deleted_photo_or_error(self):
  for failure in (False,True):
   with self.subTest(failure=failure):
    cap=self.capture();key=cap['id']
    def analyzing(*args):
     self.assertEqual(self.client.delete('/api/captures/'+key).status_code,200)
     if failure:raise module.HTTPException(502,'Upstream failed')
     return {'status':'needs_review','proposals':[],'scene_note':'test'}
    with patch.object(module,'analyze_photo',side_effect=analyzing):
     response=self.client.post('/api/captures/'+key+'/analyze')
    self.assertEqual(response.status_code,404)
    with module.db() as c:
     self.assertIsNone(c.execute('SELECT * FROM captures WHERE id=?',(key,)).fetchone())
     self.assertIsNone(c.execute('SELECT * FROM analysis_failures WHERE capture_id=?',(key,)).fetchone())
 def test_unconfigured_vision_does_not_invent_count(self):
  b=self.capture();r=self.client.post('/api/captures/'+b['id']+'/analyze')
  self.assertEqual(r.status_code,200);self.assertEqual(r.json()['proposals'],[])
  self.assertEqual(r.json()['status'],'pending_configuration')
 def test_vision_proposal_is_review_only_and_open_quantity_is_removed(self):
  cap=self.capture()
  model={'proposals':[dict(sku='invented-sku',label='Uncertain bag',visible_packs=4,open_container=True,
   evidence_text='An open bag',question='Check the label')],'scene_note':'Needs review'}
  response=MagicMock();response.json.return_value={'status':'completed','output':[
   {'type':'message','content':[{'type':'output_text','text':json.dumps(model)}]}]}
  with patch.dict(os.environ,{'OPENAI_API_KEY':'test-key'}),patch.object(module.httpx,'Client') as client:
   client.return_value.__enter__.return_value.post.return_value=response
   result=self.client.post('/api/captures/'+cap['id']+'/analyze')
  self.assertEqual(result.status_code,200,result.text)
  self.assertEqual(result.json()['status'],'needs_review')
  self.assertIsNone(result.json()['proposals'][0]['sku'])
  self.assertIsNone(result.json()['proposals'][0]['visible_packs'])
  self.assertFalse(any(x['location']==self.location for x in self.client.get('/api/stock').json()))
 def test_retry_is_not_addition_and_recount_replaces(self):
  cap=self.capture();b=self.commit_body(cap);r=self.client.post('/api/commits',json=b)
  self.assertEqual(r.status_code,200,r.text)
  self.assertTrue(self.client.post('/api/commits',json=b).json()['duplicate'])
  b2=self.commit_body(cap,5);self.assertEqual(self.client.post('/api/commits',json=b2).status_code,200)
  rows=[r for r in self.client.get('/api/stock').json() if r['location']==self.location]
  self.assertEqual(len(rows),1);self.assertEqual(rows[0]['quantity'],5)
 def test_old_offline_count_cannot_replace_newer(self):
  cap=self.capture();b=self.commit_body(cap);self.client.post('/api/commits',json=b)
  old=self.commit_body(cap,100);old['counted_at']=(datetime.now(timezone.utc)-timedelta(days=1)).isoformat()
  self.assertEqual(self.client.post('/api/commits',json=old).status_code,409)
 def test_foreign_evidence_and_duplicate_sku_rejected(self):
  cap=self.capture();b=self.commit_body(cap);b['location']='Other'
  self.assertEqual(self.client.post('/api/commits',json=b).status_code,422)
  b['location']=self.location;b['lines']*=2
  self.assertEqual(self.client.post('/api/commits',json=b).status_code,422)
 def test_unreviewed_and_negative_counts_rejected(self):
  cap=self.capture();b=self.commit_body(cap);b['review_confirmed']=False
  self.assertEqual(self.client.post('/api/commits',json=b).status_code,422)
  b['review_confirmed']=True;b['lines'][0]['quantity']=-1
  self.assertEqual(self.client.post('/api/commits',json=b).status_code,422)
 def test_sheet_not_configured_is_explicit(self):
  self.assertEqual(self.client.post('/api/sheets/sync').status_code,503)
 def test_no_zeroing_unvisited_items(self):
  cap=self.capture();b=self.commit_body(cap);self.client.post('/api/commits',json=b)
  rows=[r for r in self.client.get('/api/stock').json() if r['location']==self.location]
  self.assertEqual([r['sku'] for r in rows],[self.sku])
 def vision_response(self):
  response=MagicMock()
  result={'proposals':[dict(sku=self.sku,label='Test item',visible_packs=3,open_container=False,
   evidence_text='Three separate sealed packs',question='')],'scene_note':'Review these visible packs.'}
  response.json.return_value={'status':'completed','output':[{'type':'message','content':[{'type':'output_text','text':json.dumps(result)}]}]}
  return response
 def test_live_preview_does_not_create_evidence_or_stock(self):
  with module.db() as c:
   before=(c.execute('SELECT count(*) FROM captures').fetchone()[0],c.execute('SELECT count(*) FROM current_stock').fetchone()[0])
  with patch.dict(os.environ,{'OPENAI_API_KEY':'test-key'}),patch.object(module.httpx,'Client') as client:
   client.return_value.__enter__.return_value.post.return_value=self.vision_response()
   result=self.client.post('/api/vision/preview',json={'image_base64':self.image})
  self.assertEqual(result.status_code,200,result.text)
  self.assertEqual(result.json()['proposals'][0]['visible_packs'],3)
  with module.db() as c:
   after=(c.execute('SELECT count(*) FROM captures').fetchone()[0],c.execute('SELECT count(*) FROM current_stock').fetchone()[0])
  self.assertEqual(before,after)
 def test_selected_live_view_reuses_analysis_for_saved_evidence(self):
  with patch.dict(os.environ,{'OPENAI_API_KEY':'test-key'}),patch.object(module.httpx,'Client') as client:
   post=client.return_value.__enter__.return_value.post
   post.return_value=self.vision_response()
   self.assertEqual(self.client.post('/api/vision/preview',json={'image_base64':self.image}).status_code,200)
   cap=self.capture()
   result=self.client.post('/api/captures/'+cap['id']+'/analyze')
   self.assertEqual(result.status_code,200,result.text)
   self.assertEqual(post.call_count,1)
  photo=next(x for x in self.client.get('/api/captures').json() if x['id']==cap['id'])
  self.assertEqual(photo['analysis']['status'],'needs_review')
  self.assertNotIn('image_path',photo)
 def test_live_and_photo_listing_require_device_auth(self):
  client=TestClient(module.app)
  self.assertEqual(client.get('/api/captures').status_code,401)
  self.assertEqual(client.post('/api/vision/preview',json={'image_base64':self.image}).status_code,401)
 def test_ai_billing_error_is_actionable_and_does_not_leak_response(self):
  import httpx
  cap=self.capture()
  req=httpx.Request('POST','https://api.openai.com/v1/responses')
  response=httpx.Response(429,json={'error':{'code':'insufficient_quota','message':'secret-do-not-expose'}},request=req)
  with patch.dict(os.environ,{'OPENAI_API_KEY':'test-key'}),patch.object(module.httpx,'Client') as client:
   client.return_value.__enter__.return_value.post.return_value=response
   result=self.client.post('/api/captures/'+cap['id']+'/analyze')
  self.assertEqual(result.status_code,502)
  self.assertIn('credits',result.json()['detail'])
  self.assertNotIn('secret-do-not-expose',result.text)
  photo=next(x for x in self.client.get('/api/captures').json() if x['id']==cap['id'])
  self.assertIsNone(photo['analysis'])
  self.assertIn('credits',photo['analysis_error'])
  with patch.dict(os.environ,{'OPENAI_API_KEY':'test-key'}),patch.object(module.httpx,'Client') as client:
   client.return_value.__enter__.return_value.post.return_value=self.vision_response()
   self.assertEqual(self.client.post('/api/captures/'+cap['id']+'/analyze').status_code,200)
  photo=next(x for x in self.client.get('/api/captures').json() if x['id']==cap['id'])
  self.assertIsNone(photo['analysis_error'])
 def test_bad_preview_image_and_missing_configuration_are_explicit(self):
  self.assertEqual(self.client.post('/api/vision/preview',json={'image_base64':'not-a-real-image'}).status_code,422)
  result=self.client.post('/api/vision/preview',json={'image_base64':self.image})
  self.assertEqual(result.status_code,503)
  self.assertIn('OPENAI_API_KEY',result.json()['detail'])
 def test_live_camera_page_is_available_without_embedding_credentials(self):
  result=TestClient(module.app).get('/capture')
  self.assertEqual(result.status_code,200)
  self.assertIn('Start live video + AI',result.text)
  self.assertNotIn('test-device',result.text)
 def sheet_client(self):
  import sheets
  fake=MagicMock()
  def get(url,**kwargs):
   response=MagicMock()
   response.json.return_value=({'values':[['stale']]*5} if '/values/' in url else {'sheets':[
    {'properties':{'title':title,'sheetId':i,'gridProperties':{'rowCount':1,'columnCount':10}}}
    for i,title in enumerate(sheets.TITLES)]})
   return response
  fake.get.side_effect=get
  oauth=types.ModuleType('google.oauth2');oauth.service_account=MagicMock()
  transport=types.ModuleType('google.auth.transport.requests');transport.AuthorizedSession=MagicMock(return_value=fake)
  return fake,{'google.oauth2':oauth,'google.auth.transport.requests':transport}
 def test_sheet_retry_rewrites_same_snapshot_and_expands_grid(self):
  import sheets
  cap=self.capture();body=self.commit_body(cap);self.client.post('/api/commits',json=body)
  fake,modules=self.sheet_client()
  with patch.dict(sys.modules,modules),patch.dict(os.environ,{'GOOGLE_SERVICE_ACCOUNT_JSON':'{}','GOOGLE_SHEET_ID':'test'}):
   for _ in range(2):sheets.sync(module.db,module.item_list(),module.DATA)
  writes=[c.kwargs['json'] for c in fake.post.call_args_list if c.args[0].endswith('/values:batchUpdate')]
  self.assertEqual(len(writes),2);self.assertEqual(writes[0],writes[1]);self.assertEqual(writes[0]['valueInputOption'],'RAW')
  self.assertFalse(any(':append' in c.args[0] for c in fake.post.call_args_list))
  grid=fake.post.call_args_list[0].kwargs['json']['requests'][0]['updateSheetProperties']['properties']['gridProperties']
  self.assertGreaterEqual(grid['rowCount'],len(module.item_list())+1)
  with module.db() as db:self.assertIsNotNone(db.execute('SELECT sheets_synced_at FROM commits WHERE id=?',(body['id'],)).fetchone()[0])
 def test_failed_sheet_write_keeps_commit_pending(self):
  import sheets
  from fastapi import HTTPException
  cap=self.capture();body=self.commit_body(cap);self.client.post('/api/commits',json=body)
  fake,modules=self.sheet_client()
  def post(url,**kwargs):
   if url.endswith('/values:batchUpdate'):raise RuntimeError('temporary network failure')
   return MagicMock()
  fake.post.side_effect=post
  with patch.dict(sys.modules,modules),patch.dict(os.environ,{'GOOGLE_SERVICE_ACCOUNT_JSON':'{}','GOOGLE_SHEET_ID':'test'}):
   with self.assertRaises(HTTPException):sheets.sync(module.db,module.item_list(),module.DATA)
  with module.db() as db:self.assertIsNone(db.execute('SELECT sheets_synced_at FROM commits WHERE id=?',(body['id'],)).fetchone()[0])
 def test_sheet_sync_after_deletion_keeps_counts_and_removes_photo_links_and_review(self):
  import sheets
  saved=self.capture();unused=self.capture();body=self.commit_body(saved)
  self.assertEqual(self.client.post('/api/commits',json=body).status_code,200)
  for cap in (saved,unused):self.assertEqual(self.client.delete('/api/captures/'+cap['id']).status_code,200)
  fake,modules=self.sheet_client()
  with patch.dict(sys.modules,modules),patch.dict(os.environ,{'GOOGLE_SERVICE_ACCOUNT_JSON':'{}','GOOGLE_SHEET_ID':'test','PUBLIC_BASE_URL':'https://inventory.example'}):
   sheets.sync(module.db,module.item_list(),module.DATA)
  writes=[c.kwargs['json'] for c in fake.post.call_args_list if c.args[0].endswith('/values:batchUpdate')]
  datasets=writes[0]['data'];obs=datasets[1]['values'];stocks=datasets[2]['values'];reviews=datasets[3]['values']
  row=next(r for r in obs if r[0]==body['id'])
  self.assertEqual(row[6],3);self.assertEqual(row[9],saved['id']);self.assertEqual(row[10],'')
  self.assertEqual(next(r for r in stocks if r[0]==self.location)[3],3)
  self.assertFalse(any(r[0] in (saved['id'],unused['id']) for r in reviews))
 def test_created_item_syncs_to_ht_items_and_can_support_reviewed_count(self):
  import sheets
  cap=self.capture();item=self.new_item_body(reference_photo=cap['id'])
  self.assertEqual(self.client.post('/api/items',json=item).status_code,200)
  fake,modules=self.sheet_client()
  with patch.dict(sys.modules,modules),patch.dict(os.environ,{'GOOGLE_SERVICE_ACCOUNT_JSON':'{}','GOOGLE_SHEET_ID':'test'}):
   sheets.sync(module.db,module.item_list(),module.DATA)
  write=next(c.kwargs['json'] for c in fake.post.call_args_list if c.args[0].endswith('/values:batchUpdate'))
  row=next(r for r in write['data'][0]['values'] if r[0]==item['sku'])
  self.assertEqual(row[1],item['name']);self.assertEqual(row[2],'Produce');self.assertEqual(row[4],'kg')
  self.assertFalse(any(r[1]==item['sku'] for r in write['data'][2]['values']))
  count=self.commit_body(cap,2.5);count['lines'][0].update(sku=item['sku'],unit='kg',source='operator_reported')
  self.assertEqual(self.client.post('/api/commits',json=count).status_code,200)
  self.assertEqual(next(r for r in self.client.get('/api/stock').json() if r['location']==self.location)['quantity'],2.5)

if __name__=='__main__':unittest.main()
