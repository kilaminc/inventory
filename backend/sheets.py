"""Deterministic sheet snapshots, not append-on-retry. No OAuth employee login."""
import json
import os
import threading
from datetime import datetime, timezone
from fastapi import HTTPException

SYNC_LOCK=threading.Lock()
TITLES=['HT Items','HT Observations','HT Stock','HT Review']

def sync(db, items, data_dir):
    raw=os.getenv('GOOGLE_SERVICE_ACCOUNT_JSON'); sid=os.getenv('GOOGLE_SHEET_ID')
    if not raw or not sid: raise HTTPException(503,'Google Sheets connection has not been configured.')
    with SYNC_LOCK:
        try:
            from google.oauth2 import service_account
            from google.auth.transport.requests import AuthorizedSession
            credentials=service_account.Credentials.from_service_account_info(json.loads(raw),
                scopes=['https://www.googleapis.com/auth/spreadsheets'])
            client=AuthorizedSession(credentials)
            base='https://sheets.googleapis.com/v4/spreadsheets/'+sid
            res=client.get(base,timeout=25);res.raise_for_status()
            properties={s['properties']['title']:s['properties'] for s in res.json()['sheets']}
            have=set(properties)
            missing=[{'addSheet':{'properties':{'title':t}}} for t in TITLES if t not in have]
            if missing:
                r=client.post(base+':batchUpdate',json={'requests':missing},timeout=25);r.raise_for_status()
                res=client.get(base,timeout=25);res.raise_for_status()
                properties={s['properties']['title']:s['properties'] for s in res.json()['sheets']}
            with db() as c:
                commits=[dict(r) for r in c.execute('SELECT * FROM commits ORDER BY created_at,id')]
                stocks=[dict(r) for r in c.execute('SELECT * FROM current_stock ORDER BY location,sku')]
                captures=[dict(r) for r in c.execute('SELECT * FROM captures ORDER BY captured_at,id')]
            names={i['sku']:i['name'] for i in items}
            itemrows=[['SKU','Item','Category','Subcategory','Unit','Barcode','Verified pack size','Pack unit','Notes']]
            for i in items:itemrows.append([i.get(k) if i.get(k) is not None else '' for k in
                ['sku','name','category','subcategory','unit','barcode','pack_size','pack_unit','notes']])
            obs=[['Commit ID','Counted at','Operator','Location','SKU','Item','Quantity','Unit','Method','Evidence IDs','Evidence link','Note']]
            used=set(); baseurl=os.getenv('PUBLIC_BASE_URL','').rstrip('/')
            for record in commits:
                x=json.loads(record['body'])
                for line in x['lines']:
                    used.update(line['evidence_ids'])
                    ids=line['evidence_ids']
                    link=baseurl+'/view/evidence/'+ids[0] if baseurl and ids else ''
                    obs.append([x['id'],x['counted_at'],x['operator'],x['location'],line['sku'],names.get(line['sku'],''),
                        line['quantity'],line['unit'],line['source'],', '.join(ids),link,line.get('note','')])
            current=[['Location','SKU','Item','Quantity','Unit','Method','Counted at','Operator','Commit ID']]
            for x in stocks:current.append([x['location'],x['sku'],names.get(x['sku'],''),x['quantity'],x['unit'],
                x['source'],x['counted_at'],x['operator'],x['commit_id']])
            review=[['Capture ID','Location','Operator','Captured at','Status','Evidence link']]
            for x in captures:
                if x['id'] not in used:
                    review.append([x['id'],x['location'],x['operator'],x['captured_at'],
                        'Needs review' if x['analysis'] else 'Awaiting analysis',
                        baseurl+'/view/evidence/'+x['id'] if baseurl else ''])
            datasets=[itemrows,obs,current,review];updates=[]
            resize=[]
            for title,rows in zip(TITLES,datasets):
                p=properties[title];grid=p.get('gridProperties',{})
                if grid.get('rowCount',0)<len(rows) or grid.get('columnCount',0)<16:
                    resize.append({'updateSheetProperties':{'properties':{'sheetId':p['sheetId'],
                        'gridProperties':{'rowCount':max(len(rows),grid.get('rowCount',0)),
                                          'columnCount':max(16,grid.get('columnCount',0))}},
                        'fields':'gridProperties.rowCount,gridProperties.columnCount'}})
            if resize:
                r=client.post(base+':batchUpdate',json={'requests':resize},timeout=25);r.raise_for_status()
            for title,rows in zip(TITLES,datasets):
                r=client.get(base+'/values/'+"'"+title+"'!A1:P",timeout=25);r.raise_for_status()
                old_count=len(r.json().get('values',[])); width=16
                padded=[row+['']*(width-len(row)) for row in rows]
                padded += [['']*width for _ in range(max(0,old_count-len(rows)))]
                updates.append({'range':"'"+title+"'!A1:P"+str(len(padded)), 'values':padded})
            r=client.post(base+'/values:batchUpdate',json={'valueInputOption':'RAW','data':updates},timeout=45)
            r.raise_for_status()
            at=datetime.now(timezone.utc).isoformat()
            with db() as c:
                for x in commits:c.execute('UPDATE commits SET sheets_synced_at=? WHERE id=?',(at,x['id']))
            return {'synced':True,'commits':len(commits),'stock_rows':len(stocks),
                    'url':'https://docs.google.com/spreadsheets/d/'+sid,'synced_at':at}
        except HTTPException:raise
        except Exception:
            raise HTTPException(502,'Sheets sync failed. Check the service account, sheet sharing and sheet ID. Saved counts are retained.')
