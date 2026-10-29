"""Fixed-weight CPU SenseVoice, audio-only auto language, and CTC verification."""
from dataclasses import dataclass,field
from pathlib import Path
import time,re,hashlib,json
from typing import Any

@dataclass(frozen=True,slots=True)
class AudioInput:
    event_id:str
    audio_path:str
    audio_sha256:str
    speaker:str

@dataclass
class Recognition:
    text:str
    language:str
    tokens:list
    seconds:float
    logits:Any=field(repr=False)
    text_frames:Any=field(repr=False)
    metadata:dict=field(default_factory=dict)

class SenseVoiceBackend:
    def __init__(self,model_dir,*,threads=8,allow_heldout=False,expected_sha=None):
        import torch,sentencepiece as spm
        import yaml
        from funasr.models.sense_voice.model import SenseVoiceSmall
        from funasr.frontends.wav_frontend import WavFrontend
        self.torch=torch;self.model_dir=Path(model_dir);self.allow_heldout=allow_heldout
        self.transcribe_calls=0;self.ctc_verification_calls=0
        torch.set_num_threads(threads)
        path=self.model_dir/'model.pt'
        with path.open('rb') as f:self.weight_sha256=hashlib.file_digest(f,'sha256').hexdigest()
        if expected_sha and self.weight_sha256!=expected_sha:raise ValueError('Unexpected model weights')
        config=yaml.safe_load((self.model_dir/'config.yaml').read_text(encoding='utf-8'))
        # SentencePiece's Windows native file reader rejects some Unicode
        # paths. Load the ORIGINAL serialized bytes through Python instead;
        # no transliteration, new tokenizer, hub download or weight change.
        self.tokenizer=spm.SentencePieceProcessor(model_proto=(self.model_dir/'chn_jpn_yue_eng_ko_spectok.bpe.model').read_bytes())
        model=SenseVoiceSmall(encoder=config['encoder'],encoder_conf=config['encoder_conf'],
            input_size=560,vocab_size=self.tokenizer.vocab_size(),**config.get('model_conf',{}))
        state=torch.load(path,map_location='cpu',weights_only=True)
        if 'model_state_dict' in state:state=state['model_state_dict']
        result=model.load_state_dict(state,strict=True)
        self.model=model.eval()
        self.frontend=WavFrontend(fs=16000,window='hamming',n_mels=80,frame_length=25,frame_shift=10,lfr_m=7,lfr_n=6,cmvn_file=str(self.model_dir/'am.mvn'))
        self.load_receipt={'strict_load':True,'missing_keys':result.missing_keys,'unexpected_keys':result.unexpected_keys,
            'weight_sha256':self.weight_sha256,'threads':threads,'device':'cpu','dtype':'float32','language':'auto','ITN':True}

    def logits_for_features(self,x,length,language=0,text_norm=14):
        import numpy as np
        t=self.torch
        features=t.as_tensor(x,dtype=t.float32)
        if features.ndim!=3 or features.shape[0]!=1 or features.shape[2]!=560:raise ValueError('Expected [1,T,560]')
        lengths=t.as_tensor(length,dtype=t.int32).reshape(1)
        if not 1<=int(lengths[0])<=features.shape[1]:raise ValueError('Invalid LFR length')
        with t.inference_mode():
            prefix=t.cat([self.model.embed(t.tensor([[int(language)]])),self.model.embed(t.tensor([[1,2]])),self.model.embed(t.tensor([[int(text_norm)]]))],dim=1)
            encoded,_=self.model.encoder(t.cat([prefix,features],dim=1),lengths+4)
            logits=self.model.ctc.ctc_lo(encoded).float().cpu()
        if logits.shape!=(1,features.shape[1]+4,25055) or not t.isfinite(logits).all():raise ValueError('Invalid model output')
        return logits[:,:int(lengths[0])+4]

    def decode_logits(self,logits):
        t=self.torch;probs=logits[0].softmax(-1);values=probs.argmax(-1).tolist()
        ids=[];runs=[];previous=None
        for i,value in enumerate(values):
            if value!=previous:
                if value!=0:ids.append(value);runs.append((i,float(probs[i,value])))
            elif value!=0 and runs and float(probs[i,value])>runs[-1][1]:runs[-1]=(i,float(probs[i,value]))
            previous=value
        full=self.tokenizer.decode(ids);clean=re.sub(r'<\|[^>]*\|>','',full).strip()
        language='en' if '<|en|>' in full else 'zh' if '<|zh|>' in full else 'unknown'
        tokens=[]
        for k,token_id in enumerate(ids):
            before=re.sub(r'<\|[^>]*\|>','',self.tokenizer.decode(ids[:k])).strip()
            after=re.sub(r'<\|[^>]*\|>','',self.tokenizer.decode(ids[:k+1])).strip()
            if len(after)>len(before):tokens.append({'start':len(before),'end':len(after),'text':after[len(before):],
                'ctc_peak_posterior':runs[k][1],'frame':runs[k][0],'token_id':token_id})
        return clean,language,tokens,ids,full

    def transcribe(self,event:AudioInput):
        import soundfile as sf
        from funasr.utils.load_utils import extract_fbank
        if not isinstance(event,AudioInput):raise TypeError('Recognition accepts only AudioInput')
        path=Path(event.audio_path).resolve()
        heldout=Path('E:/paper的ex/data/evaluation/audio').resolve()
        if path.is_relative_to(heldout) and not self.allow_heldout:raise PermissionError('Held-out predictions disabled before freeze/quality gates')
        with path.open('rb') as f:
            if hashlib.file_digest(f,'sha256').hexdigest()!=event.audio_sha256:raise ValueError('Audio changed')
        self.transcribe_calls+=1
        started=time.perf_counter();audio,sr=sf.read(str(path),dtype='float32')
        if sr!=16000 or audio.ndim!=1:raise ValueError('Expected mono 16kHz PCM input')
        x,length=extract_fbank(audio,data_type='sound',frontend=self.frontend)
        logits=self.logits_for_features(x,length,language=0,text_norm=14)
        text,language,tokens,ids,full=self.decode_logits(logits)
        return Recognition(text,language,tokens,time.perf_counter()-started,logits,logits[:,4:],
            {'ctc_ids':ids,'text_with_tags':full,'LFR_frames':int(length.item()),'model_sha256':self.weight_sha256})

    def score_candidates(self,event,recognition,texts):
        """Full CTC sequence likelihood, normalized by target subword length.

        Query frames are excluded. Scores retain learned model priors; they are
        not independent pure acoustics, calibrated probabilities, or beam scores.
        """
        t=self.torch;texts=list(dict.fromkeys(texts));frames=recognition.text_frames
        if not texts:return {}
        self.ctc_verification_calls+=1
        labels=[self.tokenizer.encode(text,out_type=int) for text in texts]
        if any(not lab or 0 in lab for lab in labels):raise ValueError('Empty/blank CTC target')
        logp=frames.log_softmax(-1).transpose(0,1).expand(-1,len(texts),-1).contiguous()
        targets=t.tensor([n for lab in labels for n in lab],dtype=t.long)
        sizes=t.tensor([len(lab) for lab in labels],dtype=t.long)
        lengths=t.full((len(labels),),frames.shape[1],dtype=t.long)
        with t.inference_mode():loss=t.nn.functional.ctc_loss(logp,targets,lengths,sizes,blank=0,reduction='none',zero_infinity=False)
        values=(-loss/sizes).tolist()
        return dict(zip(texts,values))
