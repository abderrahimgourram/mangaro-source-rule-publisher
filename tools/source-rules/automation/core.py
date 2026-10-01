"""Bounded schema-1 evaluator and publication gates. No dynamic expressions or parser code."""
from __future__ import annotations
import base64
import copy
import hashlib
import ipaddress
import json
import re
import socket
import time
from dataclasses import dataclass
from urllib.parse import urljoin, urlsplit, urlencode, parse_qsl, quote
import requests
from bs4 import BeautifulSoup
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

OPS = {'popular', 'latest', 'search', 'details', 'chapters', 'pages'}
PUBLIC_HEADERS = {'referer', 'origin', 'user-agent', 'accept', 'accept-language'}
class Unsafe(Exception):
    """Only stable diagnostic codes, never response/secret contents."""
class Transient(Unsafe): pass

def canonical(value): return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
def digest(value): return hashlib.sha256(canonical(value).encode()).hexdigest()
def public_url(value):
    u = urlsplit(value)
    if u.scheme != 'https' or not u.hostname or '.' not in u.hostname or u.username or u.password or u.hostname.endswith(('.local','.localhost')):
        raise Unsafe('HTTPS_PUBLIC_HOST_REQUIRED')
    try: ipaddress.ip_address(u.hostname)
    except ValueError: return value
    raise Unsafe('IP_LITERAL_REJECTED')

def schema(r):
    if set(r) - {'schema','sourceId','revision','baseUrl','operations','headers','validationQuery','validationMangaId'}: raise Unsafe('UNKNOWN_SCHEMA_FIELD')
    if r.get('schema',1) != 1 or type(r.get('sourceId')) is not int or not 0 < r['sourceId'] < 2**63 or type(r.get('revision')) is not int or r['revision'] < 1: raise Unsafe('INVALID_SCHEMA_ID_REVISION')
    public_url(r['baseUrl'])
    ops = r['operations']
    if set(ops) != OPS: raise Unsafe('FULL_OPERATION_PROFILE_REQUIRED')
    h = r.get('headers',{})
    if len(h)>12 or any(k.lower() not in PUBLIC_HEADERS or len(v)>1024 or '\r' in v or '\n' in v for k,v in h.items()): raise Unsafe('UNSAFE_HEADERS')
    q,i=r.get('validationQuery',''),r.get('validationMangaId','')
    if bool(q.strip()) != bool(i.strip()) or len(q)>256 or len(i)>128: raise Unsafe('INVALID_VALIDATION_SAMPLE')
    for name,o in ops.items():
        if set(o)-{'endpoint','method','bodyEncoding','parameters','format','rows','fields','identity','pagination','required'}: raise Unsafe('UNKNOWN_OPERATION_FIELD')
        e=o['endpoint']
        if len(e)>1024 or not (e.startswith('/') or e=='{path}' or e.startswith('{path}/')) or e.startswith('//'): raise Unsafe('INVALID_ENDPOINT')
        if o.get('method','GET') not in {'GET','POST'} or o.get('bodyEncoding','FORM') not in {'JSON','FORM'} or o.get('format','JSON') not in {'JSON','HTML'}: raise Unsafe('EXECUTABLE_OR_UNSUPPORTED_RULE')
        fields=o['fields']; required= {'id','title'} if name=='details' else {'id','url','name'} if name=='chapters' else {'image'} if name=='pages' else {'id','url','title'}
        if len(fields)>24 or not required <= set(fields) or any(fields[k].get('optional',False) for k in required): raise Unsafe('REQUIRED_FIELD_ABSENT')
        if len(o.get('parameters',{}))>20 or len(o.get('required',{}))>8 or len(o.get('rows',''))>256 or len(o.get('identity','{url}'))>1024: raise Unsafe('RULE_BOUND_EXCEEDED')
        for f in fields.values():
            if set(f)-{'path','attribute','optional','root','transform'} or len(f.get('path',''))>256 or len(f.get('attribute',''))>64 or f.get('transform','NONE') not in {'NONE','BASE64'}: raise Unsafe('UNSUPPORTED_FIELD')
        selectors=[o.get('rows',''),o.get('pagination',{}).get('nextSelector','')]+[f.get('path','') for f in fields.values()]
        if o.get('format','JSON')=='HTML' and any(':matches' in s.lower() or ':has(' in s.lower() for s in selectors): raise Unsafe('UNBOUNDED_SELECTOR')
        p=o.get('pagination')
        if name in {'popular','latest','search','chapters'} and not p: raise Unsafe('UNVERIFIED_PAGINATION')
        if p:
            if set(p)-{'pageParameter','pageSize','total','hasNext','nextSelector','maxPages'}: raise Unsafe('UNSUPPORTED_PAGINATION')
            if not 1<=p.get('pageSize',24)<=200 or not 1<=p.get('maxPages',25)<=25 or not re.fullmatch(r'[A-Za-z0-9_-]{1,40}',p.get('pageParameter','page')) or not any(p.get(k) for k in ['total','hasNext','nextSelector']): raise Unsafe('INVALID_PAGINATION')
    if len(canonical(r).encode())>256*1024: raise Unsafe('OVERSIZED_RULE')
    return r

@dataclass
class Response:
    url: str
    status: int
    body: bytes
    headers: dict

class Http:
    """Publisher only; no Android networking changes. Bounded redirects/body/host pacing."""
    def __init__(self, budget=80, delay=.35):
        self.budget=budget; self.calls=0; self.delay=delay; self.last={}; self.session=requests.Session(); self.responses=[]
    def get(self,url,method='GET',parameters=None,headers=None,encoding='FORM',image=False):
        public_url(url)
        if method not in {'GET','POST'}: raise Unsafe('UNSUPPORTED_METHOD')
        for redirect in range(4):
            host=urlsplit(url).hostname
            try:
                addresses=socket.getaddrinfo(host,443,type=socket.SOCK_STREAM)
                if not addresses or any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses): raise Unsafe('NON_PUBLIC_DNS')
            except socket.gaierror: raise Transient('DNS_FAILURE') from None
            self.calls+=1
            if self.calls>self.budget: raise Transient('REQUEST_BUDGET_EXHAUSTED')
            time.sleep(max(0,self.delay-(time.monotonic()-self.last.get(host,0)))); self.last[host]=time.monotonic()
            kw={'params':parameters} if method=='GET' else ({'json':parameters} if encoding=='JSON' else {'data':parameters})
            try:
                with self.session.request(method,url,headers=headers,timeout=(5,12),stream=True,allow_redirects=False,**kw) as r:
                    if r.status_code in {301,302,303,307,308}:
                        url=public_url(urljoin(r.url,r.headers.get('Location',''))); parameters=None
                        if r.status_code==303: method='GET'
                        continue
                    limit=512 if image else 2*1024*1024
                    body=bytearray()
                    for chunk in r.iter_content(512 if image else 8192):
                        body.extend(chunk)
                        if image and len(body)>=512: break
                        if len(body)>limit: raise Unsafe('OVERSIZED_REPRESENTATION')
                    result=Response(r.url,r.status_code,bytes(body),dict(r.headers))
            except requests.Timeout: raise Transient('NETWORK_TIMEOUT') from None
            except requests.RequestException: raise Transient('NETWORK_FAILURE') from None
            if not image: self.responses.append(result)
            return result
        raise Unsafe('EXCESSIVE_REDIRECTS')

def path(doc,p):
    if p in {'','$'}: return doc
    node=doc
    for part in p.removeprefix('$.').split('.'):
        try: node=node[int(part)] if isinstance(node,list) else node[part]
        except (TypeError,KeyError,IndexError,ValueError): return None
    return node

def primitive(value):
    if value is None or isinstance(value,(dict,list)): return None
    if isinstance(value,bool): return 'true' if value else 'false'
    return str(value)

def extract(doc,f,html=False):
    if html:
        node=doc.select_one(f['path']) if f.get('path') else doc
        return (node.get(f['attribute']) if f.get('attribute') else node.get_text(' ',strip=True)) if node is not None else None
    return primitive(path(doc,f.get('path','')))

def variables(base,url='',memo=None,page=1,query=''):
    parsed=urlsplit(urljoin(base+'/',url.split('#')[0])); parts=[x for x in parsed.path.split('/') if x]
    ids=list({str(v) for k,v in (memo or {}).items() if (k in {'id','remoteId','chapterId'} or k.endswith('.id')) and str(v)})
    remote=ids[0] if len(ids)==1 else url.split('#',1)[1] if '#' in url else url.strip('/') if re.fullmatch(r'[A-Za-z0-9_-]{1,128}',url.strip('/')) else ''
    return dict(url=url,path=parsed.path.rstrip('/') or '/',slug=parts[-1] if parts else '',mangaSlug=parts[-1] if parts else '',id=remote,page=str(page),query=query,**{f'segment{i}':parts[i] if i<len(parts) else '' for i in range(6)})

def expand(template,v,encode=False):
    def sub(m):
        if m[1] not in v: raise Unsafe('UNKNOWN_PLACEHOLDER')
        value=str(v[m[1]])
        return quote(value,safe='/' if m[1]=='path' else '') if encode else value
    return re.sub(r'\{([A-Za-z][A-Za-z0-9]*)\}',sub,template)

class Interpreter:
    def __init__(self,r,http): self.r=schema(r); self.http=http
    def rows(self,name,url='',memo=None,page=1,query=''):
        o=self.r['operations'][name]; v=variables(self.r['baseUrl'],url,memo,page,query)
        endpoint=public_url(urljoin(self.r['baseUrl'],expand(o['endpoint'],v,True)))
        params={k:expand(x,v) for k,x in o.get('parameters',{}).items()}; p=o.get('pagination')
        if p: params[p.get('pageParameter','page')]=str(page)
        headers={k:expand(x,v) for k,x in self.r.get('headers',{}).items()}
        response=self.http.get(endpoint,o.get('method','GET'),params,headers,o.get('bodyEncoding','FORM'))
        if response.status!=200: raise Unsafe('HTTP_'+str(response.status))
        if urlsplit(response.url).netloc!=urlsplit(self.r['baseUrl']).netloc: raise Unsafe('CANONICAL_DOMAIN_CHANGED')
        if not response.body.strip(): raise Unsafe('EMPTY_BODY')
        html=o.get('format','JSON')=='HTML'
        try: doc=BeautifulSoup(response.body,'html.parser') if html else json.loads(response.body)
        except (ValueError,UnicodeDecodeError): raise Unsafe('INVALID_JSON_REPRESENTATION') from None
        if html and doc.select('#challenge-form,.cf-challenge,[data-sitekey]'): raise Unsafe('CHALLENGE_REPRESENTATION')
        raw=doc.select(o['rows']) if html and o.get('rows') else [doc] if html else path(doc,o.get('rows',''))
        if not isinstance(raw,list): raw=[raw] if raw is not None else None
        if raw is None or len(raw)>2000: raise Unsafe('MISSING_OR_UNBOUNDED_ROWS')
        values=[]
        for row in raw:
            if any(extract(row,{'path':f},html) not in allowed for f,allowed in o.get('required',{}).items()): continue
            fields={}
            for k,f in o['fields'].items():
                value=extract(doc if f.get('root') else row,f,html)
                if not value:
                    if not f.get('optional'): raise Unsafe('MISSING_FIELD_'+k)
                    value=''
                if f.get('transform')=='BASE64' and value:
                    try: value=base64.b64decode(value,validate=True).decode()
                    except (ValueError,UnicodeDecodeError): raise Unsafe('INVALID_BASE64_FIELD') from None
                fields[k]=value
            if 'url' in fields: fields['url']=expand(o.get('identity','{url}'),v|fields)
            for k in ['image','cover']:
                if fields.get(k): fields[k]=public_url(urljoin(self.r['baseUrl'],fields[k]))
            values.append(fields)
        total=None
        if p and p.get('total'):
            try: total=int(extract(doc,{'path':p['total']},html))
            except (ValueError,TypeError): raise Unsafe('MISSING_DECLARED_TOTAL') from None
            if total<0: raise Unsafe('NEGATIVE_TOTAL')
        if not p: nxt=False
        elif p.get('hasNext'):
            value=extract(doc,{'path':p['hasNext']},html)
            if value not in {'true','false','1','0'}: raise Unsafe('INVALID_NEXT_STATE')
            nxt=value in {'true','1'}
            if total is not None and nxt != (page*p.get('pageSize',24)<total): raise Unsafe('PAGINATION_TOTAL_MISMATCH')
        elif p.get('nextSelector'):
            if not html: raise Unsafe('INVALID_HTML_PAGINATION')
            nxt=bool(doc.select(p['nextSelector']))
        else: nxt=page*p.get('pageSize',24)<total
        if nxt and not raw: raise Unsafe('EMPTY_INTERMEDIATE_PAGE')
        return values,nxt,total
    def catalogue(self,op,page=1,query=''):
        rows,nxt,_=self.rows(op,page=page,query=query)
        if op!='search' and not rows: raise Unsafe('EMPTY_POPULATED_CATALOGUE')
        unique(rows,'id'); unique(rows,'url')
        return rows,nxt
    def details(self,m):
        rows,_,_=self.rows('details',m['url'],{'id':m['id']})
        if len(rows)!=1 or rows[0]['id']!=m['id']: raise Unsafe('DETAILS_IDENTITY_MISMATCH')
        if not rows[0]['title'].strip(): raise Unsafe('EMPTY_DETAILS_TITLE')
        return m|rows[0] # Preserve produced manga URL through all steps.
    def chapters(self,m):
        allrows=[]; declared=None; pages=set()
        p=self.r['operations']['chapters']['pagination']
        for page in range(1,p.get('maxPages',25)+1):
            rows,nxt,total=self.rows('chapters',m['url'],{'id':m['id']},page)
            fallback=int(m['chapterTotal']) if m.get('chapterTotal') else None
            if total is None: total=fallback
            if fallback is not None and total!=fallback: raise Unsafe('DETAILS_CHAPTER_TOTAL_MISMATCH')
            if total is None: raise Unsafe('UNVERIFIED_CHAPTER_TOTAL')
            if declared is not None and declared!=total: raise Unsafe('CHANGING_CHAPTER_TOTAL')
            declared=total
            ids=tuple(r['id'] for r in rows)
            if ids in pages or page>1 and not rows: raise Unsafe('REPEATED_OR_MISSING_CHAPTER_PAGE')
            pages.add(ids); allrows+=rows; unique(allrows,'id'); unique(allrows,'url')
            if not nxt:
                if len(allrows)!=declared: raise Unsafe('TRUNCATED_CHAPTER_TOTAL')
                return allrows
        raise Unsafe('CHAPTER_PAGE_BOUND_EXCEEDED')
    def pages(self,c):
        rows,_,_=self.rows('pages',c['url'],{'id':c['id']})
        unique(rows,'image')
        if not rows: raise Unsafe('EMPTY_READER')
        if any('order' in r for r in rows):
            try: rows=sorted(rows,key=lambda r:int(r['order']))
            except (ValueError,KeyError): raise Unsafe('INVALID_PAGE_ORDER') from None
            unique(rows,'order')
        return rows

def unique(rows,key):
    values=[r.get(key) for r in rows]
    if any(v is None or not str(v).strip() for v in values) or len(set(values))!=len(values): raise Unsafe('DUPLICATE_OR_EMPTY_'+key.upper())

def image(response):
    b=response.body
    if response.status not in {200,206} or not next((v for k,v in response.headers.items() if k.lower()=='content-type'),'').lower().startswith('image/') or not (b.startswith((b'\xff\xd8\xff',b'\x89PNG\r\n\x1a\n',b'GIF8')) or b.startswith(b'RIFF') and b'WEBP' in b[:16] or b'ftypavif' in b[:16]): raise Unsafe('INVALID_IMAGE_RESPONSE')

def validate(r,http,previous=None):
    """Two distinct catalogue pages, four works, full bounded chapter traversal, image sample.
    Returns durable identities only; never signed image query strings or raw response bodies.
    """
    interp=Interpreter(r,http); first,nxt=interp.catalogue('popular'); second=[]
    if nxt:
        second,_=interp.catalogue('popular',2)
        if {x['id'] for x in first}&{x['id'] for x in second} or {x['url'] for x in first}&{x['url'] for x in second}: raise Unsafe('REPEATED_CATALOGUE_PAGE')
    recent,_=interp.catalogue('latest')
    # Different popularity positions plus latest; include persisted large/older witnesses first.
    samples={m['id']:m for m in first+second+recent}
    known=(previous or {}).get('works',{})
    selected=[]
    for key in known:
        found=samples.get(key)
        if found is None:
            search,_=interp.catalogue('search',query=known[key]['title'])
            found=next((x for x in search if x['id']==key),None)
        if found is None: raise Unsafe('KNOWN_MANGA_IDENTITY_LOST')
        selected.append(found)
    for m in [first[0],first[-1],second[-1] if second else first[len(first)//2],recent[0]]:
        if m['id'] not in {x['id'] for x in selected}: selected.append(m)
    selected=selected[:4]
    if len(selected)<min(3,len(samples)): raise Unsafe('INSUFFICIENT_MULTI_MANGA_SAMPLE')
    witnesses={}
    for index,m in enumerate(selected):
        search,_=interp.catalogue('search',query=m['title'])
        if not any(x['id']==m['id'] and x['url']==m['url'] for x in search): raise Unsafe('SEARCH_IDENTITY_MISMATCH')
        detail=interp.details(m); chapters=interp.chapters(detail)
        ids={c['id'] for c in chapters}; old=set(known.get(m['id'],{}).get('chapters',[]))
        if not old<=ids: raise Unsafe('KNOWN_CHAPTER_IDENTITIES_LOST')
        if chapters:
            pages=interp.pages(chapters[-1])
            if index==0: image(http.get(pages[0]['image'],headers=r.get('headers',{})|{'Range':'bytes=0-511'},image=True))
        if r['sourceId']==2482399499047903203:
            supported={'MANGA','MANHWA','MANHUA'}
            for name in ['popular','latest','search']:
                if set(r['operations'][name].get('required',{}).get('seriesType',[]))!=supported: raise Unsafe('AZORA_NOVEL_FILTER_LOST')
        witnesses[m['id']]={'url':m['url'],'title':m['title'],'chapters':sorted(ids),'count':len(ids)}
    if not witnesses or not any(w['count'] for w in witnesses.values()): raise Unsafe('NO_READER_SAMPLE')
    return {'works':witnesses,'popular':len(first),'page2':len(second),'latest':len(recent)}

def load_public(value):
    try: key=serialization.load_der_public_key(base64.b64decode(value,validate=True))
    except (ValueError,TypeError): raise Unsafe('INVALID_PUBLIC_KEY') from None
    if not isinstance(key,ec.EllipticCurvePublicKey) or not isinstance(key.curve,ec.SECP256R1): raise Unsafe('P256_REQUIRED')
    return key

def verify(envelope,public,source_id=None):
    if set(envelope)!={'payload','signature'} or len(envelope['payload'].encode())>256*1024 or len(envelope['signature'])>256: raise Unsafe('INVALID_ENVELOPE')
    try: load_public(public).verify(base64.b64decode(envelope['signature'],validate=True),envelope['payload'].encode(),ec.ECDSA(hashes.SHA256()))
    except Exception: raise Unsafe('INVALID_SIGNATURE') from None
    try: rules=schema(json.loads(envelope['payload']))
    except (ValueError,TypeError,KeyError): raise Unsafe('MALFORMED_PAYLOAD') from None
    if source_id is not None and rules['sourceId']!=source_id: raise Unsafe('WRONG_SOURCE_ID')
    return rules

def sign(r,key_bytes,public):
    schema(r)
    try: key=serialization.load_pem_private_key(key_bytes,password=None)
    except Exception: raise Unsafe('SIGNING_KEY_INVALID') from None
    if not isinstance(key,ec.EllipticCurvePrivateKey) or not isinstance(key.curve,ec.SECP256R1) or key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)!=base64.b64decode(public): raise Unsafe('SIGNING_TRUST_ANCHOR_MISMATCH')
    payload=canonical(r); sig=key.sign(payload.encode(),ec.ECDSA(hashes.SHA256()))
    return {'payload':payload,'signature':base64.b64encode(sig).decode()}

STATE_PURPOSE='MANGARO_PUBLISHER_STATE_V1'
def seal_state(state,key_bytes,public):
    key=serialization.load_pem_private_key(key_bytes,password=None)
    if key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)!=base64.b64decode(public): raise Unsafe('STATE_TRUST_ANCHOR_MISMATCH')
    body=canonical({'purpose':STATE_PURPOSE,'state':state})
    return {'body':body,'signature':base64.b64encode(key.sign(body.encode(),ec.ECDSA(hashes.SHA256()))).decode()}
def open_state(envelope,public):
    try:
        load_public(public).verify(base64.b64decode(envelope['signature'],validate=True),envelope['body'].encode(),ec.ECDSA(hashes.SHA256()))
        data=json.loads(envelope['body'])
        if set(data)!={'purpose','state'} or data['purpose']!=STATE_PURPOSE: raise ValueError()
        return data['state']
    except Exception: raise Unsafe('STATE_SIGNATURE_INVALID') from None
