"""Read-only operation witnesses derived from successful native calls.

These contracts cannot become repair rules or authorize HEALTHY/publication. They
let routine monitoring exercise previously proven operations even where schema 1
cannot express the complete native parser (filters, dates or WebView transports).
"""
import hashlib
import json
import re
from urllib.parse import urlsplit, urljoin, parse_qsl
from bs4 import BeautifulSoup
from .core import Unsafe, image, public_url, path, extract
from .infer import leaves, nodes


def hashed(value):
    return hashlib.sha256(str(value).encode()).hexdigest()


def shape(value, depth=0):
    if depth > 5:
        return type(value).__name__
    if isinstance(value, dict):
        return {k: shape(v, depth+1) for k, v in sorted(value.items()) if k not in {'timestamp', 'updatedAt', 'views', 'count', 'total'}}
    if isinstance(value, list):
        return [shape(value[0], depth+1)] if value else []
    return type(value).__name__


def representation(body, url):
    try:
        data = json.loads(body)
    except (ValueError, UnicodeError):
        doc = BeautifulSoup(body, 'html.parser')
        if doc.select('#challenge-form,#cf-wrapper,.challenge-running') or doc.title and any(x in doc.title.get_text().lower() for x in ['just a moment', 'access denied', 'attention required']):
            raise Unsafe('CHALLENGE_REPRESENTATION')
        atoms = {t.strip() for t in doc.stripped_strings}
        for a in doc.select('a[href]'):
            atoms.add(a['href']); atoms.add(urljoin(url, a['href'])); atoms.add(urlsplit(urljoin(url, a['href'])).path)
        for a in doc.select('img'):
            for key in ('src', 'data-src', 'data-lazy-src'):
                if a.get(key): atoms.add(urljoin(url, a[key]))
        return doc, {hashed(a) for a in atoms}, 'HTML'
    atoms = set()
    def visit(node):
        if isinstance(node, dict):
            for value in node.values(): visit(value)
        elif isinstance(node, list):
            for value in node: visit(value)
        elif node is not None:
            atoms.add(hashed(node))
    visit(data)
    return data, atoms, hashed(json.dumps(shape(data), sort_keys=True))


def safe_request(url, origin):
    public_url(url)
    if urlsplit(url).netloc != urlsplit(origin).netloc:
        raise Unsafe('REPLAY_ORIGIN_UNVERIFIED')
    for key, value in parse_qsl(urlsplit(url).query):
        if re.search(r'(?i)token|nonce|cookie|session|secret|auth|signature|key', key):
            raise Unsafe('PRIVATE_REQUEST_NOT_REPLAYABLE')
        if re.search(r'(?i)"(?:token|nonce|cookie|session|secret|authorization|signature|key)"\s*:', value):
            raise Unsafe('PRIVATE_REQUEST_NOT_REPLAYABLE')
    return url


def contracts(native):
    # Only the exact source's successful native details and reader can seed witnesses.
    steps = native['steps']
    if any(steps.get(op) != 'PASS' for op in ['popular', 'search', 'details-0', 'reader', 'image-decode']):
        return []
    observations = native.get('observations', [])
    detail = next((o for o in observations if o['operation'] == 'details'), None)
    if not detail:
        return []
    expected = [detail['input']['url'], detail['manga']['title']]
    expected += [str(v) for k,v in detail['input'].get('memo', {}).items() if k == 'id' or k.endswith('.id')]
    chapter_ids = []
    for c in detail['chapters']:
        chapter_ids += [c['url']] + [str(v) for k,v in c.get('memo', {}).items() if k == 'id' or k.endswith('.id')]
    pages = next((o for o in observations if o['operation'] == 'pages'), {})
    remote_ids = {str(v) for k,v in detail['input'].get('memo', {}).items() if k == 'id' or k.endswith('.id')}
    output = []
    for trace in native.get('traces', []):
        role = trace['operation']
        if role not in {'popular', 'pagination', 'latest', 'search', 'details-0', 'reader'} or trace['method'] != 'GET' or trace['status'] != 200:
            continue
        try:
            url = safe_request(trace['url'], native['baseUrl'])
            if urlsplit(trace['finalUrl']).netloc != urlsplit(url).netloc:
                continue
            doc, atoms, fingerprint = representation(trace['body'], trace['finalUrl'])
            markers = [hashed(v) for v in (chapter_ids + expected if role == 'details-0' else expected if role == 'search' else expected + chapter_ids if role == 'reader' else []) if hashed(v) in atoms]
            identity_check = None
            if role == 'details-0':
                if fingerprint == 'HTML':
                    headings = [h for h in doc.select('h1') if h.get_text(' ', strip=True) == detail['manga']['title']]
                    if len(headings) == 1 and urlsplit(url).path.rstrip('/') == urlsplit(urljoin(native['baseUrl'], detail['input']['url'])).path.rstrip('/'):
                        identity_check = {'kind': 'HTML', 'titleHash': hashed(detail['manga']['title'])}
                else:
                    matches = []
                    for container, node in nodes(doc):
                        if not isinstance(node, dict): continue
                        titles = [k for k,v in node.items() if v == detail['manga']['title']]
                        ids = [k for k,v in node.items() if not isinstance(v, (list, dict)) and str(v) in remote_ids]
                        if len(titles) == len(ids) == 1:
                            matches.append({'kind':'JSON','path':container,'titleField':titles[0], 'idField':ids[0],
                                            'titleHash':hashed(node[titles[0]]),'idHash':hashed(node[ids[0]])})
                    if len(matches) == 1: identity_check = matches[0]
                if identity_check is None:
                    role = 'chapters'
                    markers = [hashed(v) for v in chapter_ids if hashed(v) in atoms]
            if role in {'search', 'chapters'} and not markers:
                continue
            selectors = []
            if fingerprint == 'HTML' and markers:
                for node in doc.select('a[href]'):
                    if hashed(node['href']) in markers or hashed(urlsplit(urljoin(url, node['href'])).path) in markers:
                        parent = node.parent
                        if parent.get('id') and re.fullmatch('[A-Za-z_][A-Za-z0-9_-]*', parent['id']):
                            selectors.append('#'+parent['id']+' a[href]')
                        elif node.get('class') and all(re.fullmatch('[A-Za-z_][A-Za-z0-9_-]*', c) for c in node['class']):
                            selectors.append('a.'+'.'.join(node['class']))
            image_field = None
            if role == 'reader' and pages.get('images'):
                target = pages['images'][0]
                if fingerprint != 'HTML':
                    matches = [p for p,v in leaves(doc) if v == target]
                    if len(matches) == 1: image_field = {'path':matches[0]}
                else:
                    matches = [(i,k) for i in doc.select('#readerarea img') for k in ('src','data-src','data-lazy-src') if i.get(k) and urljoin(url,i[k])==target]
                    if len(matches)==1: image_field={'path':'#readerarea img','attribute':matches[0][1]}
            chapter_markers = []
            if role in {'details-0', 'chapters'} and detail.get('completeness') == 'COMPLETE' and 1 < len(detail['chapters']) <= 2000:
                for chapter in detail['chapters']:
                    identities = [chapter['url']] + [str(v) for k,v in chapter.get('memo',{}).items() if k=='id' or k.endswith('.id')]
                    present = next((hashed(v) for v in identities if hashed(v) in atoms), None)
                    if present is None:
                        chapter_markers = []; break
                    chapter_markers.append(present)
            # No raw HTML or readable request-specific identities in the history.
            output.append({'role': role, 'url': url, 'shape': fingerprint, 'markers': list(dict.fromkeys(markers))[:32],
                           'selectors': list(dict.fromkeys(selectors))[:8], 'headers': trace.get('publicHeaders', {}),
                           'identityCheck': identity_check, 'imageField':image_field,
                           'chapterMarkers':chapter_markers})
        except Unsafe:
            continue
        if len(output) >= 10:
            break
    return output


def check(source, row, http):
    ops = {k:'SKIPPED' for k in ['popular','pagination','latest','search','details','chapters','reader','image']}
    result = dict(operations=ops, classification='INSUFFICIENT_EVIDENCE', complete=False, ok=False,
                  evidence='NATIVE_WITNESS_OBSERVATION', unknown=True, needsDeep=True)
    role = 'popular'
    try:
        for contract in row['replayContracts']:
            role = 'details' if contract['role']=='details-0' else contract['role']
            if role in {'chapters','reader'} and ops['details'] != 'PASS':
                ops[role] = 'DEPENDENT_FAILURE'
                result.update(reason='DETAILS_IDENTITY_NOT_REPLAYABLE')
                return result
            response = http.get(contract['url'], headers=contract.get('headers', {}))
            if response.status != 200:
                raise Unsafe('HTTP_'+str(response.status))
            if urlsplit(response.url).netloc != urlsplit(contract['url']).netloc:
                raise Unsafe('REPLAY_ORIGIN_CHANGED')
            doc, atoms, fingerprint = representation(response.body, response.url)
            if fingerprint != contract['shape'] or any(not doc.select(css) for css in contract['selectors']):
                raise Unsafe('PROVEN_REPRESENTATION_CHANGED')
            if contract['markers'] and not any(marker in atoms for marker in contract['markers']):
                raise Unsafe('PROVEN_IDENTITY_WITNESS_LOST')
            if not set(contract.get('chapterMarkers',[])) <= atoms:
                raise Unsafe('KNOWN_CHAPTER_IDENTITIES_LOST')
            identity = contract.get('identityCheck')
            if role == 'details':
                if not identity: raise Unsafe('DETAILS_IDENTITY_NOT_PROVEN')
                if identity['kind']=='HTML':
                    headings=doc.select('h1')
                    if len(headings)!=1 or hashed(headings[0].get_text(' ',strip=True))!=identity['titleHash']:
                        raise Unsafe('DETAILS_IDENTITY_MISMATCH')
                else:
                    node=path(doc,identity['path'])
                    if not isinstance(node,dict) or hashed(node.get(identity['idField']))!=identity['idHash'] or hashed(node.get(identity['titleField']))!=identity['titleHash']:
                        raise Unsafe('DETAILS_IDENTITY_MISMATCH')
            ops[role] = 'PASS'
            if role == 'reader' and contract.get('imageField'):
                url = urljoin(response.url, extract(doc, contract['imageField'], fingerprint=='HTML') or '')
                if urlsplit(url).hostname not in row.get('imageHosts',[]):
                    raise Unsafe('UNVERIFIED_IMAGE_ORIGIN')
                image(http.get(url,headers=contract.get('headers',{})|{'Range':'bytes=0-511'},image=True))
                ops['image']='PASS'
            if role == 'details':
                ops['chapters'] = 'PARTIAL'  # Presence/ownership witnessed; never infer completeness from rows.
        result.update(classification='DEGRADED', ok=True, unknown=False, needsDeep=False,
                      reason='NATIVE_WITNESSES_MATCH_FULL_SEMANTICS_UNVERIFIED')
        if not all(ops[k] != 'SKIPPED' for k in ['popular', 'search', 'details', 'reader']):
            result.update(classification='INSUFFICIENT_EVIDENCE', unknown=True, needsDeep=False,
                          reason='SOME_NATIVE_OPERATIONS_NOT_REPLAYABLE')
    except Unsafe as error:
        ops[role] = 'FAILED'
        if role == 'details':
            ops['chapters'] = ops['reader'] = ops['image'] = 'DEPENDENT_FAILURE'
        classification = 'TRANSIENT_NETWORK_ERROR' if str(error).startswith(('NETWORK','DNS','REQUEST_BUDGET','HTTP_5')) else 'BLOCKED_BY_CHALLENGE' if str(error) in {'HTTP_403','HTTP_429','CHALLENGE_REPRESENTATION'} else 'UNVERIFIED_TRANSPORT' if str(error) in {'UNVERIFIED_IMAGE_ORIGIN','REPLAY_ORIGIN_CHANGED'} else 'DEGRADED'
        result.update(classification=classification, ok=False, unknown=False, reason=str(error), operation=role, needsDeep=True)
    return result
