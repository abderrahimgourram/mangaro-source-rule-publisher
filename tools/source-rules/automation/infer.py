"""Evidence-constrained mappings. Ambiguity is a rejection, never a plausible-field guess."""
import copy
import json
from urllib.parse import urlsplit, urljoin, parse_qsl
from bs4 import BeautifulSoup
from .core import Unsafe, canonical, path, primitive, extract, variables, expand, public_url, schema


def leaves(node,prefix=''):
    if isinstance(node,dict):
        for k,v in node.items(): yield from leaves(v,(prefix+'.'+k).strip('.'))
    elif isinstance(node,list):
        for i,v in enumerate(node[:2000]): yield from leaves(v,(prefix+'.'+str(i)).strip('.'))
    else: yield prefix,node

def nodes(node,prefix=''):
    yield prefix,node
    if isinstance(node,dict):
        for k,v in node.items(): yield from nodes(v,(prefix+'.'+k).strip('.'))
    elif isinstance(node,list):
        # Array row schemas only need the first representative object for path exploration.
        for i,v in enumerate(node[:2]): yield from nodes(v,(prefix+'.'+str(i)).strip('.'))

def one(values,code):
    values=list({canonical(x):x for x in values}.values())
    if len(values)!=1: raise Unsafe(code+('_AMBIGUOUS' if values else '_ABSENT'))
    return values[0]

def selector_for(node):
    if node.get('id'): return '#'+escape(node['id'])
    classes=node.get('class',[])
    if classes: return node.name+'.'+'.'.join(escape(x) for x in classes[:3])
    if node.get('data-role'): return node.name+'[data-role="'+escape(node['data-role'])+'"]'
    raise Unsafe('SELECTOR_HAS_NO_STABLE_MARKER')

def escape(value):
    # Strict common CSS subset supported by both Jsoup and SoupSieve, no injected selectors.
    import re
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_-]*',value): raise Unsafe('UNSAFE_CSS_MARKER')
    return value

def equivalent(a,b):
    if isinstance(a,bool) or isinstance(b,bool): return a==b
    try:
        if float(a)==float(b): return True
    except (ValueError,TypeError): pass
    return str(a)==str(b)

def json_field(rows,key,expected,old=None):
    matches=[]
    for p,v in leaves(rows[0]):
        if all(equivalent(path(row,p),want) for row,want in zip(rows,expected)):
            matches.append({'path':p})
    if old and old in matches: return old
    return one(matches,'FIELD_'+key)

class Generator:
    """Inputs are previously validated mappings/witnesses plus new on-site representations.
    Never follows executable code or arbitrary external route suggestions.
    """
    def __init__(self,http): self.http=http; self.attempts=[]
    def generate(self,old,observations):
        candidate=copy.deepcopy(old)
        # Each observation contains trusted previous rows from successful executions and a
        # fresh Response fetched independently using the corresponding old operation.
        for op,evidence in observations.items():
            o=candidate['operations'][op]; before=evidence['before']; response=evidence['response']
            old_base=urlsplit(old['baseUrl'])
            final=urlsplit(response.url)
            if final.scheme!='https': raise Unsafe('INSECURE_DOMAIN_REPLACEMENT')
            if final.netloc!=old_base.netloc:
                public_url(response.url)
                # Cross-domain redirect is a proposal only; all known identities must survive
                # validation against every operation before this can be signed.
                candidate['baseUrl']='https://'+final.netloc
                for k,v in candidate.get('headers',{}).items():
                    if k.lower() in {'referer','origin'} and old_base.netloc==urlsplit(v).netloc:
                        candidate['headers'][k]=v.replace(old_base.netloc,final.netloc,1)
                self.attempts.append(op+':redirect-domain')
            if response.status in {404,410}:
                route=self.route(response,o,evidence)
                o.update(route)
                variables_=evidence.get('variables',{})
                endpoint=expand(o['endpoint'],variables_,True)
                params={k:expand(v,variables_) for k,v in o.get('parameters',{}).items()}
                if o.get('pagination'): params[o['pagination'].get('pageParameter','page')]='1'
                response=self.http.get(urljoin(candidate['baseUrl'],endpoint),o.get('method','GET'),params,candidate.get('headers',{}),o.get('bodyEncoding','FORM'))
                self.attempts.append(op+':evidenced-route')
            if response.status!=200: raise Unsafe('NO_VALID_CHANGED_REPRESENTATION')
            html=o.get('format','JSON')=='HTML'
            try: doc=BeautifulSoup(response.body,'html.parser') if html else json.loads(response.body)
            except ValueError: raise Unsafe('CHALLENGE_OR_NON_DATA_REPRESENTATION') from None
            if html and doc.select('#challenge-form,.cf-challenge,[data-sitekey]'): raise Unsafe('CHALLENGE_CANNOT_BE_REPAIRED')
            if not before: raise Unsafe('NO_KNOWN_IDENTITIES_FOR_INFERENCE')
            if html:
                self.html(o,doc,before)
            else:
                self.json(o,doc,before)
            p=o.get('pagination')
            if p and not html:
                for key,old_value in [('total',evidence.get('total')),('hasNext',evidence.get('next'))]:
                    if p.get(key) and path(doc,p[key]) is None:
                        candidates=[k for k,v in leaves(doc) if type(v) is type(old_value) and v==old_value]
                        p[key]=one(candidates,'PAGINATION_'+key.upper())
                        self.attempts.append(op+':pagination-'+key)
            if p:
                # A next URL/form is the only authority for a renamed page parameter.
                next_urls=[]
                if html: next_urls=[a.get('href','') for a in doc.select('a[rel=next][href]')]
                else: next_urls=[v for k,v in leaves(doc) if k.split('.')[-1] in {'next','nextPageUrl','next_url'} and isinstance(v,str) and v.startswith(('https://','/'))]
                if next_urls:
                    target=one(next_urls,'CONTINUATION_URL')
                    if urlsplit(urljoin(response.url,target)).netloc!=urlsplit(response.url).netloc: raise Unsafe('EXTERNAL_PAGINATION_ROUTE')
                    params=dict(parse_qsl(urlsplit(target).query)); choices=[k for k,v in params.items() if v=='2' and k not in o.get('parameters',{})]
                    if choices:
                        parameter=one(choices,'PAGE_PARAMETER'); p['pageParameter']=parameter; self.attempts.append(op+':continuation-parameter')
                if html and p.get('nextSelector') and not doc.select(p['nextSelector']):
                    next_nodes=doc.select('a[rel=next][href]')
                    if next_nodes: p['nextSelector']='a[rel=next][href]'; self.attempts.append(op+':next-selector')
        candidate['revision']=old['revision']+1
        schema(candidate)
        return candidate
    def route(self,response,o,evidence):
        doc=BeautifulSoup(response.body,'html.parser')
        links=doc.select('link[rel=alternate][href],a[data-replaces][href]')
        forms=doc.select('form[action]')
        candidates=[]
        for node in links+forms:
            target=urljoin(response.url,node.get('href') or node.get('action'))
            if urlsplit(target).netloc!=urlsplit(response.url).netloc: continue
            parsed=urlsplit(target); params=dict(parse_qsl(parsed.query)); method=node.get('method','GET').upper()
            if node.name=='form':
                inputs=node.select('input[name]')
                names=[x['name'] for x in inputs if x.get('type','text') in {'search','text'}]
                if evidence.get('query') and len(names)==1: params[names[0]]='{query}'
                params.update({x['name']:x.get('value','') for x in inputs if x.get('type')=='hidden'})
            candidates.append({'endpoint':parsed.path,'parameters':params,'method':method})
        return one(candidates,'EVIDENCED_ENDPOINT')
    def json(self,o,doc,before):
        html=False; required=o['fields']; identity='id' if 'id' in required else 'image'
        expected=[r[identity] for r in before]
        candidates=[]
        # Locate rows by known stable IDs or known page identities, not container names.
        for p,node in nodes(doc):
            if not isinstance(node,(list,dict)): continue
            rows=node if isinstance(node,list) else [node]
            if len(rows)<len(before): continue
            for idpath,_ in leaves(rows[0]):
                matched=[]
                for want in expected:
                    found=[r for r in rows if equivalent(path(r,idpath),want)]
                    if len(found)!=1: break
                    matched.append(found[0])
                if len(matched)==len(before): candidates.append((p,rows,matched,idpath))
        if not candidates: raise Unsafe('ROW_IDENTITY_MAPPING_ABSENT')
        best=min((0 if isinstance(path(doc,p),list) else 1, len(i.split('.')) if i else 0) for p,_,_,i in candidates)
        ranked=[{'path':p,'identity':i} for p,_,_,i in candidates if (0 if isinstance(path(doc,p),list) else 1,len(i.split('.')) if i else 0)==best]
        chosen=one(ranked,'ROW_IDENTITY_MAPPING')
        _,rows,matched,idpath=next(x for x in candidates if x[0]==chosen['path'] and x[3]==chosen['identity'])
        if o.get('rows','')!=chosen['path']: self.attempts.append('rows:'+o.get('rows','')+'->'+chosen['path'])
        o['rows']=chosen['path']
        for key,f in required.items():
            if f.get('root'):
                wants=[before[0].get(key)]
                selected=json_field([doc],key,wants,f if extract(doc,f)==wants[0] else None)
                selected['root']=True
            else:
                wants=[r.get(key,'') for r in before]
                if f.get('optional') and all(not x for x in wants): continue
                # Keep an existing extraction that still proves the same row bindings.
                if all(equivalent(extract(row,f),w) for row,w in zip(matched,wants)): continue
                selected=json_field(matched,key,wants)
            selected.update({k:v for k,v in f.items() if k in {'optional','transform','root'}})
            if selected!=f: self.attempts.append('field:'+key); o['fields'][key]=selected
    def html(self,o,doc,before):
        identity='url' if 'url' in o['fields'] else 'image'
        targets=[r[identity] for r in before]
        bound=[]
        for want in targets:
            nodes_=[node for node in doc.find_all(True) if any(str(v)==want or urljoin('https://'+urlsplit(want).netloc,str(v))==want for v in node.attrs.values() if isinstance(v,str))]
            node=one([{'index':doc.find_all(True).index(n)} for n in nodes_],'HTML_IDENTITY')['index']
            element=doc.find_all(True)[node]
            ancestors=[element]+list(element.parents)
            choices=[]
            for ancestor in ancestors[:4]:
                if not getattr(ancestor,'attrs',None): continue
                try: selector=selector_for(ancestor)
                except Unsafe: continue
                found=doc.select(selector)
                if len(found)>=len(before): choices.append(selector)
            bound.append(choices)
        common=set(bound[0]).intersection(*[set(x) for x in bound[1:]])
        # Card ancestors must contain all anchored required values; a cover/title link alone
        # is not a card merely because its href matches one expected manga.
        required_values=[k for k in o['fields'] if k in {'id','url','title','name','image'}]
        def binds(selector):
            selected=doc.select(selector)
            for before_row in before:
                found=False
                for row in selected:
                    values=[row.get_text(' ',strip=True)]
                    for node in [row]+list(row.find_all(True)):
                        values.append(node.get_text(' ',strip=True))
                        values += [str(v) for v in node.attrs.values() if isinstance(v,str)]
                    if all(before_row.get(k) in values for k in required_values): found=True; break
                if not found: return False
            return True
        common={s for s in common if binds(s)}
        if o.get('rows') in common: selector=o['rows']
        else: selector=one(list(common),'CSS_CARD_SELECTOR')
        o['rows']=selector
        rows=doc.select(selector); matched=[]
        for want in targets:
            matches=[row for row in rows if any(str(v)==want for node in [row]+list(row.find_all(True)) for v in node.attrs.values() if isinstance(v,str))]
            if len(matches)!=1: raise Unsafe('AMBIGUOUS_HTML_IDENTITY')
            matched.append(matches[0])
        for key,f in o['fields'].items():
            wants=[r.get(key,'') for r in before]
            if f.get('optional') and all(not x for x in wants): continue
            if all(extract(row,f,True)==w for row,w in zip(matched,wants)): continue
            proposals=[]
            for node in [matched[0]]+list(matched[0].find_all(True)):
                try: css='' if node is matched[0] else selector_for(node)
                except Unsafe: continue
                for attr in ['']+list(node.attrs):
                    proposed={'path':css,'attribute':attr}
                    if all(extract(row,proposed,True)==want for row,want in zip(matched,wants)): proposals.append(proposed)
            chosen=one(proposals,'CSS_FIELD_'+key)
            o['fields'][key]=chosen|{k:v for k,v in f.items() if k in {'optional','root','transform'}}
            self.attempts.append('css-field:'+key)


def compile_native(evidence):
    """Cold-start only if every native identity and metadata transformation is representable.
    Failed native records never become a baseline. Schema gaps are explicit, not lossy rules.
    """
    if evidence.get('filters'): raise Unsafe('NATIVE_FILTERS_NOT_REPRESENTABLE_IN_SCHEMA_1')
    details=[x for x in evidence.get('observations',[]) if x['operation']=='details']
    if len(details)<3: raise Unsafe('INSUFFICIENT_NATIVE_WITNESSES')
    if any(x['manga'].get('status',0)!=0 for x in details): raise Unsafe('NATIVE_STATUS_TRANSFORMATION_NOT_REPRESENTABLE')
    if any(x['completeness']!='COMPLETE' for x in details): raise Unsafe('NATIVE_CHAPTERS_NOT_VERIFIED_COMPLETE')
    # Preserve dates/names/memo exactly; ISO-date conversion and generated labels cannot be
    # declared in schema 1. Direct mappings are compiled only by exact observed equality.
    traces=evidence.get('traces',[])
    parsed=[]
    for t in traces:
        if t['status']!=200: continue
        try: parsed.append((t,json.loads(t['body'])))
        except ValueError: raise Unsafe('NATIVE_HTML_CONDITIONAL_PARSER_REQUIRES_COMPILED_UPDATE') from None
    source_id=evidence['sourceId']; operations={}
    def remote(m):
        ids={str(v) for k,v in m.get('memo',{}).items() if k=='id' or k.endswith('.id')}
        if len(ids)==1: return next(iter(ids))
        if '#' in m['url']: return m['url'].split('#')[-1]
        raise Unsafe('NATIVE_REMOTE_ID_NOT_PROVEN')
    def build(role,expected,tag,m=None):
        ts=[(t,d) for t,d in parsed if t['operation']==tag]
        proposals=[]
        for trace,doc in ts:
            for rows_path,node in nodes(doc):
                rows=node if isinstance(node,list) else [node]
                if not rows or not isinstance(rows[0],dict): continue
                for idpath,_ in leaves(rows[0]):
                    matched=[]
                    for want in expected:
                        found=[r for r in rows if str(path(r,idpath))==want['id']]
                        if len(found)!=1: break
                        matched.append(found[0])
                    if len(matched)!=len(expected): continue
                    try:
                        fields={'id':{'path':idpath}}
                        for key in expected[0]:
                            if key=='id': continue
                            wants=[w[key] for w in expected]
                            if all(x in ('',None) for x in wants): continue
                            fields[key]=json_field(matched,key,wants)
                        u=urlsplit(trace['url']); vars_=variables(evidence['baseUrl'],m['url'] if m else '',m.get('memo') if m else {},query=expected[0].get('title',''))
                        endpoint=u.path; params=dict(parse_qsl(u.query))
                        for key,value in list(params.items()):
                            if any(x in key.lower() for x in ['token','cookie','auth','secret']): raise Unsafe('SECRET_REQUEST_NOT_DECLARATIVE')
                            for name in ['id','slug','query']:
                                val=vars_.get(name,'')
                                if val and val in value: value=value.replace(val,'{'+name+'}')
                            params[key]=value
                        for name in ['id','slug']:
                            val=vars_.get(name,'')
                            if m and val and val in endpoint: endpoint=endpoint.replace(val,'{'+name+'}')
                        operation={'endpoint':endpoint,'parameters':params,'rows':rows_path,'fields':fields}
                        if role in {'popular','latest','search','chapters'}:
                            paging_keys=[k for k,v in params.items() if k in {'page','p','pageNumber'} and v=='1']
                            if len(paging_keys)!=1: raise Unsafe('NATIVE_PAGINATION_NOT_DIRECT')
                            parameter=paging_keys[0]; params.pop(parameter)
                            totals=[p for p,v in leaves(doc) if p.split('.')[-1] in {'total','count','totalCount','totalChapterCount'} and type(v) is int]
                            total=one(totals,'NATIVE_VERIFIED_TOTAL')
                            operation['pagination']={'pageParameter':parameter,'pageSize':len(rows),'total':total,'maxPages':25}
                        proposals.append(operation)
                    except Unsafe: continue
        return one(proposals,'NATIVE_'+role.upper()+'_MAPPING')
    for role in ['popular','latest','search']:
        obs=next((x for x in evidence['observations'] if x['operation']==role),None)
        if not obs: raise Unsafe('NATIVE_OPERATION_MISSING')
        expected=[{'id':remote(m),'url':m['url'],'title':m['title'],'cover':m['cover']} for m in obs['mangas']]
        operations[role]=build(role,expected,role)
    example=details[0]; m=example['input']; meta=example['manga']
    expected={'id':remote(meta)}|{k:meta[k] for k in ['title','cover','description','author','artist','genre']}
    operations['details']=build('details',[expected],'details-0',m)
    chapter_expected=[]
    for c in example['chapters']:
        item={'id':remote(c),'url':c['url'],'name':c['name'],'number':c['number'],'date':c['date'],'scanlator':c['scanlator']}
        item.update({'memo.'+k:str(v) for k,v in c.get('memo',{}).items()})
        chapter_expected.append(item)
    operations['chapters']=build('chapters',chapter_expected,'details-0',m)
    pages=next((x for x in evidence['observations'] if x['operation']=='pages'),None)
    if not pages: raise Unsafe('NATIVE_READER_EVIDENCE_MISSING')
    # Page identity is its exact URL. A direct primitive array is the only cold-start reader
    # shape accepted here; encoded/conditional/signed readers remain native.
    candidates=[]
    for trace,doc in parsed:
        if trace['operation']!='reader': continue
        for p,node in nodes(doc):
            if node==pages['images']:
                c={'url':pages['chapterUrl'],'memo':pages['chapterMemo']}; vars_=variables(evidence['baseUrl'],c['url'],c['memo'])
                u=urlsplit(trace['url']); params=dict(parse_qsl(u.query))
                for k,v in params.items():
                    if vars_['id'] and v==vars_['id']: params[k]='{id}'
                candidates.append({'endpoint':u.path,'parameters':params,'rows':p,'fields':{'image':{'path':''}}})
    operations['pages']=one(candidates,'NATIVE_READER_MAPPING')
    return schema({'schema':1,'sourceId':source_id,'revision':1,'baseUrl':evidence['baseUrl'],'operations':operations})
