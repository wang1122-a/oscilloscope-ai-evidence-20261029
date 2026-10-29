package com.lilliput.ads10000a.voice;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One verified weight/tokenizer identity, language-specific data-only prompts. */
final class BilingualModelManifest {
 static final String FILE="bilingual_semantic_manifest.json";
 final File model;final String modelId,sha256,tokenizerSha;
 final int contextTokens,newTokens;final Map<String,String> templates,promptHashes;
 private BilingualModelManifest(File model,String id,String sha,String tokenizerSha,int context,int tokens,Map<String,String> templates,Map<String,String> hashes){
  this.model=model;modelId=id;sha256=sha;this.tokenizerSha=tokenizerSha;contextTokens=context;newTokens=tokens;
  this.templates=Collections.unmodifiableMap(templates);promptHashes=Collections.unmodifiableMap(hashes);
 }
 static Map<?,?> metadata(File root)throws IOException {return metadata(root,()->true);}
 static Map<?,?> metadata(File root,java.util.function.BooleanSupplier allowed)throws IOException {return parseMetadata(new String(read(child(root,FILE),65536,allowed),StandardCharsets.UTF_8));}
 static Map<?,?> parseMetadata(String json)throws IOException {
  final Object parsed;try{parsed=StrictJson.parse(json);}catch(IllegalArgumentException invalid){throw new IOException("bilingual_manifest_invalid",invalid);}
  if(!(parsed instanceof Map))throw new IOException("bilingual_manifest_invalid");
  Map<?,?> data=(Map<?,?>)parsed;
  if(!"bilingual_qwen_language_prompts_v1".equals(data.get("protocol"))||data.containsKey("adapters")||data.containsKey("language_models"))
   throw new IOException("one_bilingual_weight_required");
  if(!new HashSet<>(Arrays.asList("protocol","model_id","model_file","model_bytes","model_sha256","tokenizer_sha256","max_context_len","max_new_tokens","prompts","languages","source_kind","selected_production_candidate")).containsAll(data.keySet()))throw new IOException("bilingual_manifest_unknown_key");
  if(data.containsKey("languages")&&!Arrays.asList("zh","en").equals(data.get("languages")))throw new IOException("one_shared_zh_en_package_required");
  if(!(data.get("model_id") instanceof String)||!((String)data.get("model_id")).matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))throw new IOException("bilingual_model_identity_mismatch");
  return data;
 }
 static BilingualModelManifest load(File root)throws IOException{return load(root,()->true);}
 static BilingualModelManifest load(File root,java.util.function.BooleanSupplier allowed)throws IOException {
  Map<?,?> data=metadata(root,allowed);File model=child(root,String.valueOf(data.get("model_file")));
  if(!(data.get("model_id") instanceof String))throw new IOException("bilingual_model_identity_mismatch");
  String id=String.valueOf(data.get("model_id")),sha=String.valueOf(data.get("model_sha256")),tokenizer=String.valueOf(data.get("tokenizer_sha256"));
  if(!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")||!model.getName().endsWith(".rkllm")||!sha.matches("(?i)[a-f0-9]{64}")
    ||!tokenizer.matches("(?i)[a-f0-9]{64}")||!(data.get("model_bytes") instanceof Number)||model.length()<=0
    ||((Number)data.get("model_bytes")).doubleValue()!=model.length()||model.length()>2_000_000_000L||!sha.equalsIgnoreCase(Hashing.sha256(model,allowed)))
   throw new IOException("bilingual_model_identity_mismatch");
  Object descriptors=data.get("prompts");if(!(descriptors instanceof Map))throw new IOException("bilingual_prompts_missing");
  Map<?,?> prompts=(Map<?,?>)descriptors;boolean legacy=prompts.keySet().equals(Set.of("zh","zh_plan","en"));if(!legacy&&!prompts.keySet().equals(Set.of("zh","en")))throw new IOException("bilingual_prompt_keys_invalid");
  Map<String,String> templates=new LinkedHashMap<>(),hashes=new LinkedHashMap<>();
  for(String key:legacy?new String[]{"zh","zh_plan","en"}:new String[]{"zh","en"}) {
   if(!(prompts.get(key) instanceof Map))throw new IOException("bilingual_prompt_descriptor_invalid");
   Map<?,?> descriptor=(Map<?,?>)prompts.get(key);if(descriptor.containsKey("model_file")||descriptor.containsKey("model_sha256"))throw new IOException("language_cannot_select_weight");
   if(!descriptor.keySet().equals(new HashSet<>(Arrays.asList("file","sha256"))))throw new IOException("bilingual_prompt_descriptor_invalid");
   String promptFile=String.valueOf(descriptor.get("file"));if(!promptFile.endsWith(".txt"))throw new IOException("bilingual_prompt_data_file_required");byte[] bytes=read(child(root,promptFile),262144,allowed);
   String hash=String.valueOf(descriptor.get("sha256"));if(!hash.equalsIgnoreCase(Hashing.sha256(new ByteArrayInputStream(bytes))))throw new IOException("bilingual_prompt_identity_mismatch");
   String template=new String(bytes,StandardCharsets.UTF_8);if(template.indexOf("{{USER_TEXT}}")<0||template.indexOf("{{USER_TEXT}}")!=template.lastIndexOf("{{USER_TEXT}}"))throw new IOException("bilingual_prompt_placeholder_invalid");
   templates.put(key,template);hashes.put(key,hash);
  }
  int context=number(data,"max_context_len",4096),tokens=number(data,"max_new_tokens",512);
  if(context<512||context>8192||tokens<64||tokens>1024||tokens>=context)throw new IOException("bilingual_token_budget_invalid");
  return new BilingualModelManifest(model,id,sha.toUpperCase(Locale.ROOT),tokenizer.toUpperCase(Locale.ROOT),context,tokens,templates,hashes);
 }
 static boolean orderedChinese(String text){return text!=null&&text.matches("(?s).*(?:然后|随后|接着|并且|并(?=打开|关闭|设置|查询)).*");}
 String promptKey(SemanticLanguage language,String text){return language==SemanticLanguage.EN?"en":orderedChinese(text)?"zh_plan":"zh";}
 String prompt(SemanticLanguage language,String text){if(text==null||text.trim().isEmpty()||text.length()>512||text.contains("<|")||text.indexOf(0)>=0)throw new IllegalArgumentException("invalid_user_text");return requireTemplate(promptKey(language,text)).replace("{{USER_TEXT}}",text);}
 String prompt(BilingualTextPipeline.Prepared request){if(request==null||!request.context.usable()||request.input.trim().isEmpty()||request.input.length()>512||request.input.contains("<|")||request.input.indexOf(0)>=0)throw new IllegalArgumentException("invalid_user_text");if(request.singleInstructionMode)return request.singlePrompt();String input=request.modelInput();if(input.length()>4096)throw new IllegalArgumentException("validated_context_input_too_large");String key=request.language==SemanticLanguage.EN?"en":request.ordered?"zh_plan":"zh";return requireTemplate(key).replace("{{USER_TEXT}}",input);}
 private String requireTemplate(String key){String template=templates.get(key);if(template==null)throw new IllegalStateException("bilingual_prompt_unavailable:"+key);return template;}
 private static int number(Map<?,?> data,String key,int fallback)throws IOException {Object value=data.get(key);if(value==null)return fallback;if(!(value instanceof Number))throw new IOException("bilingual_token_budget_invalid");double number=((Number)value).doubleValue();if(!Double.isFinite(number)||number!=Math.floor(number)||number<Integer.MIN_VALUE||number>Integer.MAX_VALUE)throw new IOException("bilingual_token_budget_invalid");return (int)number;}
 static File child(File root,String name)throws IOException {if(!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")||name.contains(".."))throw new IOException("bilingual_manifest_path_invalid");File file=new File(root,name);if(!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))throw new IOException("bilingual_manifest_path_invalid");return file;}
 private static byte[] read(File file,int limit,java.util.function.BooleanSupplier allowed)throws IOException {if(!file.isFile()||file.length()>limit)throw new IOException("bilingual_asset_missing_or_large");try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];for(;;){if(allowed==null||!allowed.getAsBoolean())throw new java.util.concurrent.CancellationException("BILINGUAL_VALIDATION_CANCELLED");int n=in.read(buffer);if(n==-1)break;if(out.size()+n>limit)throw new IOException("bilingual_asset_too_large");out.write(buffer,0,n);}return out.toByteArray();}}
}
