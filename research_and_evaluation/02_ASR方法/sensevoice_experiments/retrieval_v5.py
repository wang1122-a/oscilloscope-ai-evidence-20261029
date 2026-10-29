"""Calibration-development retrieval. Generates proposals, never a correction.

Only canonical forms and registered pronunciation texts enter this module.
All proposals still need actual audio CTC verification. No reference text,
expected term, authored task or evaluation-equivalence registry is accepted.
"""
import re
from difflib import SequenceMatcher
from .notebook import Lexicon as LegacyLexicon, norm, phonetic, boundary, protected_change

ZH_DIGITS=dict(zip('零〇一二两三四五六七八九','001223456789'))
EN_NUM={'zero':'0','one':'1','two':'2','three':'3','four':'4','five':'5','six':'6','seven':'7','eight':'8','nine':'9','ten':'10'}
LETTER_NAMES=dict(zip('abcdefghijklmnopqrstuvwxyz',
    ['ei','bi','si','di','i','ef','ji','eich','ai','jei','kei','el','em','en','ou','pi','kiu','ar','es','ti','iu','vi','dabeliu','eks','wai','zi']))

def numeral_text(s):
    s=norm(s)
    for word,num in EN_NUM.items():s=re.sub(r'\b'+word+r'\b',num,s)
    def zh(m):
        raw=m.group()
        if '十' in raw:
            left,_,right=raw.partition('十')
            return str(int(ZH_DIGITS.get(left,'1'))*10+int(ZH_DIGITS.get(right,'0')))
        return ''.join(ZH_DIGITS.get(c,c) for c in raw)
    return re.sub(r'[零〇一二两三四五六七八九十]+',zh,s)

def identifier_numbers(s):return re.findall(r'\d+',numeral_text(s))

def letter_sound(s):
    s=numeral_text(s)
    # Spell short alphabetic identifiers as registered letter names. For long
    # ordinary words retain their orthography rather than invent pronunciation.
    def spelling(m):
        word=m.group().lower()
        return ' '.join(LETTER_NAMES[c] for c in word) if len(word)<=6 else word
    return phonetic(re.sub(r'[a-z]+',spelling,s))

class Lexicon(LegacyLexicon):
    version='retrieval-v5-whole-terms-numeric-boundaries'

    def __init__(self,terms):
        super().__init__(terms)
        self._prepared=[(tid,reading,key,py,letter_sound(reading)) for tid,reading,key,py in self.surfaces]

    def exact(self,text,language):
        # Duplicate bilingual profiles must not split otherwise identical
        # evidence; selection depends on audio-derived language only.
        compatible=sorted(self.terms,key=lambda tid:(self.terms[tid].get('language')!=('en' if language=='en' else 'zh'),tid))
        rank={tid:i for i,tid in enumerate(compatible)}
        result=super().exact(text,language)
        # Recognizer derives literal channel-number identity from registered
        # pronunciation, independently of evaluator-only equivalent spellings.
        for tid,term in self.terms.items():
            if not re.fullmatch(r'CH\d+',term['canonical']):continue
            for reading in term.get('readings',[]):
                compact=re.sub(r'\s+','',numeral_text(reading))
                if not re.fullmatch(r'channel\d+',compact):continue
                for m in re.finditer(re.escape(compact),text,re.I):
                    if boundary(text,m.start(),m.end(),compact) and not any(m.start()<r['end'] and r['start']<m.end() for r in result):
                        result.append({'term_id':tid,'start':m.start(),'end':m.end(),'surface':m.group()})
        out=[]
        for row in result:
            canonical=self.terms[row['term_id']]['canonical']
            choices=[tid for tid in compatible if self.terms[tid]['canonical']==canonical]
            row={**row,'term_id':min(choices,key=rank.get)};out.append(row)
        return out

    def candidates(self,text,language,rules):
        occupied=[]
        for _,surface,_,_ in self.surfaces:
            for m in re.finditer(re.escape(surface),text,re.I):
                if boundary(text,m.start(),m.end(),surface):occupied.append((m.start(),m.end(),m.group()))
        # A span may combine Latin letters, Chinese numerals and digits.
        # Sliding Han windows also recover whole compounds after short words.
        words=list(re.finditer(r'[A-Za-z0-9_]+(?:[零〇一二两三四五六七八九十]+)?|[零〇一二两三四五六七八九十]+|[\u3400-\u9fff]',text))
        results={}
        for tid,reading,key,py,sound in self._prepared:
            term=self.terms[tid];canonical=term['canonical']
            if language=='en' and term.get('language')=='zh':continue
            is_han=bool(re.search(r'[\u3400-\u9fff]',reading))
            identifier=bool(re.search(r'[A-Z]',canonical)) and not bool(re.search(r'[\u3400-\u9fff]',canonical))
            spans=set()
            if is_han:
                for size in range(max(2,len(reading)-1),len(reading)+2):
                    spans.update((i,i+size) for i in range(len(text)-size+1) if re.fullmatch(r'[\u3400-\u9fff0-9A-Za-z]+',text[i:i+size]))
            n=max(1,len(reading.split()))
            if not is_han or identifier:
                for i in range(len(words)):
                    for count in range(1,min(9,n+2)+1):
                        if i+count<=len(words):spans.add((words[i].start(),words[i+count-1].end()))
            for start,end in spans:
                old=text[start:end]
                if not old or len(old)>max(5,len(reading)*1.6):continue
                if norm(old) in {key,norm(canonical)}:continue
                if re.fullmatch(r'CH\d+',canonical) and re.sub(r'\s+','',numeral_text(old))==re.sub(r'\s+','',numeral_text(reading)):continue
                if not boundary(text,start,end,old):continue
                if '时基' in canonical and re.search(r'合适.{0,2}时机|等待.{0,3}时机|时机.{0,2}(成熟|到来|合适)|抓住.{0,2}时机',text):
                    if any(start<m.end() and m.start()<end for m in re.finditer('时机',text)):continue
                # Preserve every recognized component at the SAME relative
                # offset. "触发是一" -> "触发释抑" may extend 触发, never erase it.
                if any(start<b and a<end and not (start<=a and b<=end and
                    norm(canonical[a-start:b-start])==norm(surface)) for a,b,surface in occupied):continue
                if identifier:
                    if identifier_numbers(old)!=identifier_numbers(canonical):continue
                    sim=max(SequenceMatcher(None,letter_sound(old),sound).ratio(),
                        SequenceMatcher(None,norm(old).replace(' ',''),key.replace(' ','')).ratio())
                else:sim=SequenceMatcher(None,phonetic(old) if is_han else norm(old),py if is_han else key).ratio()
                if sim<rules.retrieval_similarity:continue
                candidate=text[:start]+canonical+text[end:]
                # Permit numeral spelling changes only when the full value
                # agrees; normal surrounding quantities/signs remain protected.
                guardterm=term
                if identifier and identifier_numbers(old)==identifier_numbers(canonical):
                    guardterm={**term,'readings':[*term.get('readings',[]),old]}
                if protected_change(text,candidate,old,canonical,guardterm):continue
                row={'text':candidate,'term_id':tid,'old':old,'new':canonical,'start':start,'end':end,'similarity':sim,
                     'retrieval_version':self.version}
                previous=results.get(candidate)
                rank=lambda c:(c['similarity'],len(c['new']),self.terms[c['term_id']].get('language')==('en' if language=='en' else 'zh'))
                if previous is None or rank(row)>rank(previous):results[candidate]=row
        return sorted(results.values(),key=lambda r:(-r['similarity'],-len(r['new']),r['start'],r['term_id']))[:rules.max_candidates]
