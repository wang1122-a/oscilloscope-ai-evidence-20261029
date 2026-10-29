package com.lilliput.ads10000a.voice;

/** Compatibility facade for deterministic command-family parsing. */
final class ExplicitTableFastPath {
 private final SemanticPolicy policy;
 private final ChineseNaturalCommandParser parser;
 ExplicitTableFastPath(String contract,String aliases){this(new SemanticPolicy(contract),contract,aliases);}
 ExplicitTableFastPath(SemanticPolicy shared,String contract,String aliases){policy=shared;parser=new ChineseNaturalCommandParser(contract);}
 ChineseNaturalCommandParser.Decision decide(String input){return parser.parse(input);}
 boolean requiresRkllm(String input){return decide(input).status==ChineseNaturalCommandParser.Status.NO_MATCH;}
 String match(String input){ChineseNaturalCommandParser.Decision d=decide(input);switch(d.status){case MATCH_QUERY:case MATCH_SET:case MATCH_ACTION:String raw=d.encodedCandidate();SemanticPolicy.Result checked=policy.evaluate(input,raw);return "preview".equals(checked.status)?raw:null;default:return null;}}
}
