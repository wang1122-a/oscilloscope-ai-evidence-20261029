from pathlib import Path
import csv, json
p=Path(__file__).parent
rows=list(csv.DictReader((p/"ASR_5620_G0_G1_G2_逐条对比.csv").open(encoding="utf-8-sig")))
assert len(rows)==5620 and len({x["event_id"] for x in rows})==5620
out=[]
for g in ["G0","G1","G2"]:
    n=sum(int(x["term_occurrences"]) for x in rows)
    c=sum(int(x[g+"_term_correct"]) for x in rows)
    strict=sum(int(x[g+"_strict_correct"]) for x in rows)
    exact=sum(x[g+"_exact"]=="True" for x in rows)
    r=dict(group=g,rows=len(rows),term_occurrences=n,term_correct=c,term_accuracy=c/n,strict_spelling_accuracy=strict/n,exact_matches=exact,exact_match_rate=exact/len(rows),false_term_insertions=sum(int(x[g+"_false_insertions"]) for x in rows))
    for lang,m in [("zh","cer"),("mixed","mer"),("en","wer")]:
        part=[x for x in rows if x["language"]==lang]
        r[lang+"_"+m]=sum(int(x[g+"_error_count"]) for x in part)/sum(int(x["reference_units"]) for x in part)
    out.append(r)
saved=json.loads((p/"ASR_5620_G0_G1_G2_汇总.json").read_text(encoding="utf-8"))
for a,b in zip(out,saved):
    for k,v in a.items():
        assert (abs(v-b[k])<1e-12 if isinstance(v,float) else v==b[k]),k
print(json.dumps({"verified":True,"metrics":out},ensure_ascii=True,indent=2))
