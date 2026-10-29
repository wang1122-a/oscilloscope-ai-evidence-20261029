"""Audio-verified candidate decisions and isolated external memory.

No reference, authored task label, expected term, or language label is accepted.
Thresholds are engineering candidates until calibration explicitly selects them.
"""
from dataclasses import dataclass, asdict
from pathlib import Path
from difflib import SequenceMatcher
import re,json,hashlib,sqlite3,time,math,os,unicodedata

def digest(value):
    return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True).encode()).hexdigest()

def norm(text):
    return ' '.join(unicodedata.normalize('NFKC',text).casefold().split())

def phonetic(text):
    from pypinyin import lazy_pinyin
    return ' '.join(lazy_pinyin(norm(text)))

def boundary(text,start,end,surface):
    alnum=lambda c:c.isascii() and c.isalnum()
    return not ((alnum(surface[0]) and start>0 and alnum(text[start-1])) or
                (alnum(surface[-1]) and end<len(text) and alnum(text[end])))

def protected_change(before,after,old,new,term):
    # A registered reading of THIS identifier may change its written form;
    # an arbitrary near identifier (CH10 -> CH1, FFT1 -> FFT) may never do so.
    readings={norm(s) for s in [term['canonical'],*term.get('readings',[])]}
    if re.search(r'\d',old+new) and norm(old) not in readings:
        return True
    numbers=lambda s:re.findall(r'(?<![A-Za-z])[+-]?\d+(?:\.\d+)?(?:\s*(?:ms|us|ns|s|MHz|kHz|Hz|mV|V|mA|A|%))?',s)
    outside_before=before.replace(old,'',1);outside_after=after.replace(new,'',1)
    if numbers(outside_before)!=numbers(outside_after):return True
    if term['canonical']=='时基' and old=='时机':
        if re.search(r'合适.{0,2}时机|等待.{0,3}时机|时机.{0,2}(成熟|到来|合适)|抓住.{0,2}时机',before):return True
    return False

def rebase_candidate(raw,first,candidate):
    """Preserve corrections already committed at other positions."""
    for tag,a,b,c,d in SequenceMatcher(None,raw,first,autojunk=False).get_opcodes():
        if tag=='equal' and a<=candidate['start'] and candidate['end']<=b:
            start=c+candidate['start']-a;end=c+candidate['end']-a
            return {**candidate,'text':first[:start]+candidate['new']+first[end:],
                'raw_start':candidate['start'],'raw_end':candidate['end'],'start':start,'end':end}
    return None

def memory_edit_allowed(raw,candidate,tokens,rules):
    """Additional memory prior must not erase intact ordinary words."""
    old=norm(candidate['old']);new=norm(candidate['new'])
    if old in {new+'s',new+'es',new+'ed',new+'ing'}:return False
    start=candidate.get('raw_start',candidate['start']);end=candidate.get('raw_end',candidate['end'])
    for word in ['记录','结果','数据','保存','来源','参数','设置','检查','说明','波形']:
        for m in re.finditer(re.escape(word),raw):
            if start<m.end() and m.start()<end and word not in candidate['new']:return False
    peaks=[t['ctc_peak_posterior'] for t in tokens if t['start']<end and start<t['end']]
    return min(peaks,default=0)<rules.memory_uncertainty_posterior

@dataclass(frozen=True)
class Rules:
    dictionary_margin:float=0.08
    memory_bonus:float=0.15
    memory_min_raw_margin:float=-0.12
    support_margin:float=0.10
    support_token_posterior:float=0.70
    confirmation_contexts:int=3
    demotion_conflicts:int=2
    retrieval_similarity:float=0.72
    max_candidates:int=8
    memory_uncertainty_posterior:float=0.90

class Lexicon:
    def __init__(self,terms):
        # Deliberately do not copy evaluation_equivalents into recognition.
        self.terms={t['term_id']:{k:t[k] for k in ['term_id','canonical','language','readings'] if k in t} for t in terms}
        self.surfaces=[]
        for tid,t in self.terms.items():
            for surface in dict.fromkeys([t['canonical'],*t.get('readings',[])]):
                if surface.strip():self.surfaces.append((tid,surface,norm(surface),phonetic(surface)))

    def exact(self,text,language):
        source=norm(text);found=[]
        for tid,surface,key,py in self.surfaces:
            term=self.terms[tid]
            if language=='en' and term.get('language')=='zh':continue
            if key in {'can','am','pm','on','off','normal','auto'} and surface!=surface.upper():continue
            for m in re.finditer(re.escape(key),source):
                if not boundary(source,m.start(),m.end(),key):continue
                if key in {'can','am','pm'} and not re.search(r'\b(bus|protocol|mode|modulation|decode|signal)\b',source):continue
                found.append({'term_id':tid,'start':m.start(),'end':m.end(),'surface':m.group()})
        # Longest complete terms win; short components cannot occupy a whole term.
        accepted=[]
        for row in sorted(found,key=lambda x:(-(x['end']-x['start']),x['start'],x['term_id'])):
            if not any(row['start']<r['end'] and r['start']<row['end'] for r in accepted):accepted.append(row)
        return sorted(accepted,key=lambda x:x['start'])

    def candidates(self,text,language,rules):
        # Offsets here use the original string (normalizing punctuation would
        # invalidate offsets). Whitespace in exact registered readings is literal.
        occupied=[]
        for _,surface,_,_ in self.surfaces:
            for m in re.finditer(re.escape(surface),text,re.I):
                if boundary(text,m.start(),m.end(),surface):occupied.append((m.start(),m.end()))
        results=[];seen=set()
        for tid,reading,key,py in self.surfaces:
            term=self.terms[tid];canonical=term['canonical']
            if language=='en' and term.get('language')=='zh':continue
            is_zh=bool(re.search(r'[\u3400-\u9fff]',reading))
            spans=[]
            if is_zh:
                for size in range(max(2,len(reading)-1),len(reading)+2):
                    spans.extend((i,i+size) for i in range(len(text)-size+1) if re.fullmatch(r'[\u3400-\u9fff]+',text[i:i+size]))
            else:
                words=list(re.finditer(r'[A-Za-z]+(?:[_-]?[A-Za-z0-9]+)*|\d+',text))
                n=max(1,len(reading.split()))
                for i in range(len(words)):
                    for count in range(max(1,n-1),n+2):
                        if i+count<=len(words):spans.append((words[i].start(),words[i+count-1].end()))
            for start,end in spans:
                old=text[start:end]
                if norm(old) in {key,norm(canonical)}:continue
                if any(start<b and a<end and (start,end)!=(a,b) for a,b in occupied):continue
                if len(old)>max(4,len(reading)*1.5):continue
                sim=SequenceMatcher(None,phonetic(old) if is_zh else norm(old),py if is_zh else key).ratio()
                if sim<rules.retrieval_similarity:continue
                candidate=text[:start]+canonical+text[end:]
                if protected_change(text,candidate,old,canonical,term):continue
                identity=(candidate,tid)
                if identity in seen:continue
                seen.add(identity);results.append({'text':candidate,'term_id':tid,'old':old,'new':canonical,'start':start,'end':end,'similarity':sim})
        # Rank whole terms ahead of their components on a matching span.
        return sorted(results,key=lambda r:(-r['similarity'],-len(r['new']),r['start'],r['term_id']))[:rules.max_candidates]

class Memory:
    def __init__(self,path,*,arm,readonly=False):
        if arm not in {'G3','G4'}:raise ValueError('Memory arm must be G3/G4')
        self.path=Path(path);self.arm=arm;self.readonly=readonly
        if readonly:
            self.db=sqlite3.connect(self.path.resolve().as_uri()+'?mode=ro',uri=True)
            self.db.execute('PRAGMA query_only=ON')
        else:
            self.path.parent.mkdir(parents=True,exist_ok=True)
            self.db=sqlite3.connect(self.path)
            existing={r[1] for r in self.db.execute('PRAGMA table_info(supports)')}
            if existing and 'evidence_text' not in existing:
                self.db.close();raise ValueError('Older memory schema requires a fresh isolated run; original left unchanged')
            self.db.executescript('''PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL;
            CREATE TABLE IF NOT EXISTS metadata(arm TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS entries(stream TEXT,term TEXT,state TEXT,conflicts INTEGER DEFAULT 0,first_result TEXT,demotion_floor INTEGER DEFAULT 0,PRIMARY KEY(stream,term));
            CREATE TABLE IF NOT EXISTS supports(stream TEXT,term TEXT,context TEXT,event TEXT,audio_sha TEXT,raw_text TEXT,evidence_text TEXT,source_kind TEXT,verification_json TEXT,PRIMARY KEY(stream,term,context),UNIQUE(stream,term,audio_sha));
            CREATE TABLE IF NOT EXISTS processed(stream TEXT,event TEXT,audio_sha TEXT,PRIMARY KEY(stream,event),UNIQUE(stream,audio_sha));
            CREATE TABLE IF NOT EXISTS pending(stream TEXT,event TEXT,audio TEXT,audio_sha TEXT,first_text TEXT,candidates TEXT,resolved INTEGER DEFAULT 0,PRIMARY KEY(stream,event));
            CREATE TABLE IF NOT EXISTS rechecks(stream TEXT,event TEXT,term TEXT,candidate_hash TEXT,epoch INTEGER,PRIMARY KEY(stream,event,term,candidate_hash,epoch));
            CREATE TABLE IF NOT EXISTS conflicts(stream TEXT,term TEXT,context TEXT,event TEXT,audio_sha TEXT,PRIMARY KEY(stream,term,context),UNIQUE(stream,term,audio_sha));
            CREATE TABLE IF NOT EXISTS history(seq INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT,payload TEXT);
            ''')
            if not self.db.execute('SELECT 1 FROM metadata').fetchone():self.db.execute('INSERT INTO metadata VALUES(?)',(arm,));self.db.commit()
        if self.db.execute('SELECT arm FROM metadata').fetchone()[0]!=arm:raise ValueError('Cross-arm memory reuse denied')
        if not readonly and 'evidence_text' not in {r[1] for r in self.db.execute('PRAGMA table_info(supports)')}:
            raise ValueError('Older memory schema: archive and use a fresh isolated run; no silent conversion')

    def known(self,stream,term):
        row=self.db.execute('SELECT state FROM entries WHERE stream=? AND term=?',(stream,term)).fetchone()
        return bool(row and row[0]=='confirmed')

    def evidence(self,stream,term):
        row=self.db.execute('SELECT demotion_floor FROM entries WHERE stream=? AND term=?',(stream,term)).fetchone()
        floor=row[0] if row else 0
        columns={r[1] for r in self.db.execute('PRAGMA table_info(supports)')}
        if 'evidence_text' not in columns:
            items=list(self.db.execute('SELECT event,audio_sha,context FROM supports WHERE stream=? AND term=? ORDER BY rowid',(stream,term)))
            return [dict(event=e,audio_sha=h,context=c,source_kind='legacy_raw_model') for e,h,c in items[floor:]]
        items=list(self.db.execute('SELECT event,audio_sha,context,source_kind,evidence_text,verification_json FROM supports WHERE stream=? AND term=? ORDER BY rowid',(stream,term)))
        return [dict(event=e,audio_sha=h,context=c,source_kind=k,evidence_text_sha256=hashlib.sha256(text.encode()).hexdigest(),verification=json.loads(v)) for e,h,c,k,text,v in items[floor:]]

    def _write(self):
        if self.readonly:raise PermissionError('Frozen snapshot prohibits all memory writes')

    def log(self,kind,payload):
        self._write();self.db.execute('INSERT INTO history(kind,payload) VALUES(?,?)',(kind,json.dumps(payload,ensure_ascii=False)))

    def observe(self,event,raw_text,strong_terms,candidates,first_text,rules,*,eligible,source_details=None):
        if type(eligible) is not bool:raise TypeError('Update eligibility must be a Boolean')
        if not eligible:return []
        if any(v.get('used_memory') for v in (source_details or {}).values()):raise ValueError('Memory-assisted output cannot support its source memory')
        self._write();stream=event.speaker;actions=[]
        try:self.db.execute('INSERT INTO processed VALUES(?,?,?)',(stream,event.event_id,event.audio_sha256))
        except sqlite3.IntegrityError:return []
        # Context is derived from raw recognition only; authored task/goal IDs
        # are kept outside this interface. Offline eligibility remains required.
        for term,surface in strong_terms:
            source=(source_details or {}).get(term,{'kind':'raw_model','memory_free_text':raw_text,'used_memory':False})
            if source.get('used_memory'):raise ValueError('Memory-assisted output cannot support its source memory')
            context=digest(norm(raw_text).replace(norm(surface),'[term]'))
            if self.arm=='G3':
                cached=self.known(stream,term)
                if not cached:
                    self.db.execute("INSERT INTO entries(stream,term,state,first_result) VALUES(?,?,'confirmed',?)",(stream,term,first_text))
                    self.db.execute('INSERT INTO supports VALUES(?,?,?,?,?,?,?,?,?)',(stream,term,context,event.event_id,event.audio_sha256,raw_text,source['memory_free_text'],source['kind'],json.dumps(source,ensure_ascii=False)))
            else:
                self.db.execute("INSERT OR IGNORE INTO entries(stream,term,state) VALUES(?,?,'pending')",(stream,term))
                try:self.db.execute('INSERT INTO supports VALUES(?,?,?,?,?,?,?,?,?)',(stream,term,context,event.event_id,event.audio_sha256,raw_text,source['memory_free_text'],source['kind'],json.dumps(source,ensure_ascii=False)))
                except sqlite3.IntegrityError:continue
                count=len(self.evidence(stream,term))
                if count>=rules.confirmation_contexts:
                    was=self.known(stream,term)
                    self.db.execute("UPDATE entries SET state='confirmed',conflicts=0 WHERE stream=? AND term=?",(stream,term))
                    if not was:actions.append({'kind':'confirmed','term_id':term,'source_events':self.evidence(stream,term)})
        if self.arm=='G4' and candidates:
            self.db.execute('INSERT OR IGNORE INTO pending(stream,event,audio,audio_sha,first_text,candidates) VALUES(?,?,?,?,?,?)',
                (stream,event.event_id,event.audio_path,event.audio_sha256,first_text,json.dumps(candidates,ensure_ascii=False)))
        actions.insert(0,{'kind':'observed','event_id':event.event_id})
        self.log('observe',{'event_id':event.event_id,'stream':stream,'raw_verified_terms':[x[0] for x in strong_terms],
            'support_policy':'raw_or_fixed_dictionary_independent_of_memory','source_details':source_details or {},'cache_first_verified_result':self.arm=='G3','actions':actions})
        self.db.commit();return actions

    def conflict(self,stream,term,event,rules,*,context=None,audio_sha=None,details=None):
        self._write()
        try:self.db.execute('INSERT INTO conflicts VALUES(?,?,?,?,?)',(stream,term,context or event,event,audio_sha or digest(event)))
        except sqlite3.IntegrityError:return False
        self.db.execute('UPDATE entries SET conflicts=conflicts+1 WHERE stream=? AND term=?',(stream,term))
        row=self.db.execute('SELECT conflicts FROM entries WHERE stream=? AND term=?',(stream,term)).fetchone()
        demoted=bool(row and row[0]>=rules.demotion_conflicts)
        if demoted:
            count=self.db.execute('SELECT COUNT(*) FROM supports WHERE stream=? AND term=?',(stream,term)).fetchone()[0]
            self.db.execute("UPDATE entries SET state='demoted',demotion_floor=? WHERE stream=? AND term=?",(count,stream,term))
        self.log('conflict',{'stream':stream,'term_id':term,'event_id':event,'demoted':demoted,'verification':details or {}});self.db.commit()
        return demoted

    def snapshot(self,destination):
        self._write();self.db.commit();destination=Path(destination)
        if destination.exists():raise FileExistsError(destination)
        with sqlite3.connect(destination) as target:self.db.backup(target)
        with destination.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

    def close(self):self.db.close()

class Engine:
    def __init__(self,backend,lexicon,arm,rules,memory=None):
        if arm not in {'G0','G1','G2','G3','G4'}:raise ValueError(arm)
        if arm in {'G3','G4'} and (memory is None or memory.arm!=arm):raise ValueError('Isolated matching memory required')
        self.backend=backend;self.lexicon=lexicon;self.arm=arm;self.rules=rules;self.memory=memory

    def decide(self,event,recognition):
        started=time.perf_counter();raw=recognition.text
        if self.arm in {'G0','G1'}:return {'text':raw,'raw_text':raw,'candidates':[],'strong_terms':[],'verification_seconds':0}
        options=self.lexicon.candidates(raw,recognition.language,self.rules)
        scores=self.backend.score_candidates(event,recognition,[raw,*[c['text'] for c in options]])
        original=scores.get(raw,-math.inf);accepted=[]
        for c in options:
            margin=scores[c['text']]-original
            valid=math.isfinite(margin)
            if not valid:margin=-1e9
            remembered=bool(self.memory and self.memory.known(event.speaker,c['term_id']))
            bonus=self.rules.memory_bonus if remembered else 0
            c.update(raw_ctc_margin=margin,ctc_valid=valid,memory_bonus=bonus,memory_source_events=self.memory.evidence(event.speaker,c['term_id']) if remembered and self.arm=='G4' else [],
                source='verified_memory_and_dictionary' if remembered else 'fixed_dictionary')
            span_peaks=[t['ctc_peak_posterior'] for t in recognition.tokens if t['start']<c['end'] and c['start']<t['end']]
            c['ctc_span_min_posterior']=min(span_peaks) if span_peaks else None;c['ctc_span_peak_count']=len(span_peaks)
            c['memory_extra_allowed']=memory_edit_allowed(raw,c,recognition.tokens,self.rules)
            c['adopted']=valid and (margin>=self.rules.dictionary_margin or (remembered and c['memory_extra_allowed'] and margin>=self.rules.memory_min_raw_margin and margin+bonus>=self.rules.dictionary_margin))
            if c['adopted']:accepted.append(c)
        best=max(accepted,key=lambda c:c['raw_ctc_margin']+c['memory_bonus'],default=None)
        output=best['text'] if best else raw
        strong=[];sources={}
        # Raw evidence and a separate fixed-dictionary verification without
        # ANY memory input are distinguished. Memory-induced outputs cannot
        # support their source entries.
        for match in self.lexicon.exact(raw,recognition.language):
            # An unresolved longer compound cannot supply a confident vote
            # merely because its leading component was recognized correctly.
            if any(c['start']<=match['start'] and match['end']<=c['end'] and match['surface'] in norm(c['new']) and len(c['new'])>len(match['surface']) for c in options):continue
            peaks=[t['ctc_peak_posterior'] for t in recognition.tokens if t['start']<match['end'] and match['start']<t['end']]
            confidence=min(peaks,default=0)
            competing=max((c['raw_ctc_margin'] for c in options if c['start']<match['end'] and match['start']<c['end']),default=-math.inf)
            if confidence>=self.rules.support_token_posterior and competing<=-self.rules.support_margin:
                strong.append((match['term_id'],match['surface']))
                sources[match['term_id']]={'kind':'raw_model','memory_free_text':raw,'raw_text':raw,'used_memory':False}
        memory_free=max((c for c in options if c['ctc_valid'] and c['raw_ctc_margin']>=self.rules.dictionary_margin),key=lambda c:c['raw_ctc_margin'],default=None)
        if self.arm=='G4' and memory_free and memory_free['raw_ctc_margin']>=self.rules.dictionary_margin+self.rules.support_margin:
            rivals=[c['raw_ctc_margin'] for c in options if c['term_id']!=memory_free['term_id'] and c['start']<memory_free['end'] and memory_free['start']<c['end'] and c['text']!=memory_free['text']]
            if not rivals or memory_free['raw_ctc_margin']-max(rivals)>=self.rules.support_margin:
                strong=[(tid,s) for tid,s in strong if not (tid!=memory_free['term_id'] and self.lexicon.terms[tid]['canonical'] in memory_free['new'])]
                strong.append((memory_free['term_id'],memory_free['old']))
                sources[memory_free['term_id']]={'kind':'fixed_dictionary_no_memory','memory_free_text':memory_free['text'],'raw_text':raw,
                    'raw_ctc_margin':memory_free['raw_ctc_margin'],'dictionary_margin':self.rules.dictionary_margin,
                    'heard_fragment':memory_free['old'],'proposed_term':memory_free['new'],'used_memory':False}
        if self.arm=='G3' and best and best['raw_ctc_margin']>=self.rules.dictionary_margin:
            # A direct dictionary verification may populate the ordinary cache;
            # a decision that passes only because of cache memory cannot do so.
            strong.append((best['term_id'],best['old']))
        return {'text':output,'raw_text':raw,'candidates':options,'strong_terms':strong,'support_source_details':sources,
                'verification_seconds':time.perf_counter()-started,'first_output_final_before_memory_update':True}

    def after_commit(self,event,decision,*,eligible,commit_receipt):
        if type(eligible) is not bool:raise TypeError('Update eligibility must be a Boolean')
        self._last_new_update=None
        if not commit_receipt or commit_receipt.get('event_id')!=event.event_id or not commit_receipt.get('durable'):
            raise ValueError('Durable first-output commit is required before update')
        if not eligible or self.memory is None:return []
        pending=[rebase_candidate(decision['raw_text'],decision['text'],c) for c in decision['candidates']]
        pending=[c for c in pending if c is not None]
        actions=self.memory.observe(event,decision['raw_text'],decision['strong_terms'],pending,decision['text'],self.rules,eligible=eligible,source_details=decision.get('support_source_details'))
        if any(a['kind']=='observed' for a in actions):self._last_new_update=event.event_id
        if self.arm=='G4' and any(a['kind']=='observed' for a in actions):
            conflicted=set()
            for c in decision['candidates']:
                tid=c['term_id']
                if tid in conflicted or not c['ctc_valid'] or not self.memory.known(event.speaker,tid):continue
                # The unchanged ASR preference is not new negative evidence.
                # Require a strong OTHER registered raw term, rejection even
                # with the fixed dictionary prior, and an existing correction
                # relation for the SAME heard fragment in a similar context.
                if c['raw_ctc_margin']>=self.rules.dictionary_margin-self.rules.support_margin:continue
                raw_competitors=[k for k,s in decision['strong_terms'] if k!=tid and decision.get('support_source_details',{}).get(k,{}).get('kind')=='raw_model'
                    and norm(self.lexicon.terms[k]['canonical'])==norm(c['old'])]
                if not raw_competitors:continue
                current_context=norm(decision['raw_text']).replace(norm(c['old']),'[term]')
                related=[]
                for source in self.memory.evidence(event.speaker,tid):
                    proof=source.get('verification',{})
                    if proof.get('kind')!='fixed_dictionary_no_memory':continue
                    old_text=proof.get('raw_text','');fixed_text=proof.get('memory_free_text','')
                    # Only an observed replacement relation may be contradicted;
                    # the presence of another valid term cannot erase a word.
                    relation_matches=norm(proof.get('heard_fragment',''))==norm(c['old']) or norm(old_text).replace(norm(c['old']),norm(c['new']),1)==norm(fixed_text)
                    if not relation_matches:continue
                    old_context=norm(old_text).replace(norm(c['old']),'[term]')
                    similarity=SequenceMatcher(None,current_context,old_context,autojunk=False).ratio()
                    if similarity>=.5:related.append({'source_event':source['event'],'context_similarity':similarity})
                if not related:continue
                conflicted.add(tid)
                demoted=self.memory.conflict(event.speaker,tid,event.event_id,self.rules,context=digest(current_context),audio_sha=event.audio_sha256,
                    details={'raw_alternative_terms':raw_competitors,'raw_ctc_margin':c['raw_ctc_margin'],'fixed_dictionary_margin':self.rules.dictionary_margin,'related_mapping_contexts':related})
                actions.append({'kind':'conflict','term_id':tid,'demoted':demoted})
        return actions

    def delayed(self,current_event,*,eligible):
        if type(eligible) is not bool:raise TypeError('Update eligibility must be a Boolean')
        if self.arm!='G4' or not eligible or getattr(self,'_last_new_update',None)!=current_event.event_id:return []
        self._last_new_update=None
        self.memory._write();revisions=[]
        from .sensevoice_backend import AudioInput
        rows=self.memory.db.execute('SELECT event,audio,audio_sha,first_text,candidates FROM pending WHERE stream=? AND resolved=0 AND event<>?',(current_event.speaker,current_event.event_id)).fetchall()
        for eid,path,h,first,encoded in rows:
            candidates=[]
            for c in json.loads(encoded):
                if not self.memory.known(current_event.speaker,c['term_id']) or c['text']==first:continue
                epoch=self.memory.db.execute('SELECT demotion_floor FROM entries WHERE stream=? AND term=?',(current_event.speaker,c['term_id'])).fetchone()[0]
                key=(current_event.speaker,eid,c['term_id'],digest(c['text']),epoch)
                if self.memory.db.execute('SELECT 1 FROM rechecks WHERE stream=? AND event=? AND term=? AND candidate_hash=? AND epoch=?',key).fetchone():continue
                c=dict(c,recheck_key=key);candidates.append(c)
            if not candidates:continue
            event=AudioInput(eid,path,h,current_event.speaker);recognition=self.backend.transcribe(event)
            scores=self.backend.score_candidates(event,recognition,[first,*[c['text'] for c in candidates]])
            eligible_candidates=[]
            for c in candidates:
                raw=scores[c['text']]-scores[first]
                self.memory.db.execute('INSERT INTO rechecks VALUES(?,?,?,?,?)',c['recheck_key'])
                if not math.isfinite(raw):continue
                sources=self.memory.evidence(current_event.speaker,c['term_id'])
                sources=[s for s in sources if s['audio_sha']!=h and s['event']!=eid]
                if len(sources)<self.rules.confirmation_contexts:continue
                counterfactual=raw>=self.rules.dictionary_margin
                guarded=memory_edit_allowed(recognition.text,c,recognition.tokens,self.rules)
                memory_pass=guarded and raw>=self.rules.memory_min_raw_margin and raw+self.rules.memory_bonus>=self.rules.dictionary_margin
                self.memory.log('historical_verification',{'event_id':eid,'trigger_event_id':current_event.event_id,'term_id':c['term_id'],
                    'raw_ctc_margin':raw,'memory_bonus':self.rules.memory_bonus,'memory_pass':memory_pass,
                    'without_memory_pass':counterfactual,'independent_source_events':sources,'reverse_support_added':False})
                if memory_pass and not counterfactual:eligible_candidates.append((raw,c,sources))
            self.memory.db.commit()
            if not eligible_candidates:continue
            raw,c,sources=max(eligible_candidates,key=lambda x:x[0])
            row={'event_id':eid,'trigger_event_id':current_event.event_id,'first_text':first,'revised_text':c['text'],
                'term_id':c['term_id'],'raw_ctc_margin':raw,'memory_bonus':self.rules.memory_bonus,
                'independent_source_events':sources,'no_memory_decision':'retain_first_text','reverse_support_added':False}
            self.memory.log('delayed_revision',row)
            self.memory.db.execute('UPDATE pending SET resolved=1 WHERE stream=? AND event=?',(event.speaker,eid));self.memory.db.commit()
            revisions.append(row)
        return revisions
