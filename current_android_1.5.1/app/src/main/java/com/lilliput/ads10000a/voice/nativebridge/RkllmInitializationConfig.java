package com.lilliput.ads10000a.voice.nativebridge;
import com.jmu.text2scpi.BuildConfig;

/** Bounded CLI-only initialization override. No preference, global model state or
 * semantic result is cached. A normal application thread always uses mode 1. */
final class RkllmInitializationConfig {
 private static final class Values {
  final int embed,cpus,mask;final boolean cpuOverride;
  Values(int embed,int cpus,int mask,boolean cpuOverride){this.embed=embed;this.cpus=cpus;this.mask=mask;this.cpuOverride=cpuOverride;}
 }
 private static final ThreadLocal<Values> OVERRIDE=new ThreadLocal<>();
 private RkllmInitializationConfig() {}
 static int embedFlash() {Values value=OVERRIDE.get();return value==null?1:value.embed;}
 static boolean overridden() {return OVERRIDE.get()!=null;}
 static int cpus() {Values value=OVERRIDE.get();return value==null?4:value.cpus;}
 static int mask() {Values value=OVERRIDE.get();return value==null?240:value.mask;}
 static boolean cpuOverridden() {Values value=OVERRIDE.get();return value!=null&&value.cpuOverride;}
 static Scope openCpu(boolean debug,boolean explicitOptIn,int cpus,int mask) {
  if(!debug||!explicitOptIn)throw new SecurityException("GENUINE_DEBUG_AND_EXPLICIT_CPU_DIAGNOSTIC_REQUIRED");
  if(!((cpus==4&&mask==240)||(cpus==2&&mask==192)))throw new IllegalArgumentException("FINITE_CPU_PAIR_REQUIRED");
  return take(new Values(1,cpus,mask,true));
 }
 static Scope open(boolean diagnosticAllowed,int value) {
  if(!diagnosticAllowed)throw new SecurityException("EXPLICIT_ISOLATED_PERFORMANCE_DIAGNOSTIC_REQUIRED");
  if(value!=0&&value!=1)throw new IllegalArgumentException("EMBED_FLASH_EXPECTED_0_OR_1");
  return take(new Values(value,4,240,false));
 }
 private static Scope take(Values value) {
  if(OVERRIDE.get()!=null)throw new IllegalStateException("DIAGNOSTIC_SCOPE_ALREADY_OPEN");
  OVERRIDE.set(value);return new Scope(Thread.currentThread());
 }
 static final class Scope implements AutoCloseable {
  private final Thread owner;private boolean closed;
  Scope(Thread owner) {this.owner=owner;}
  @Override public void close() {
   if(Thread.currentThread()!=owner)throw new IllegalStateException("DIAGNOSTIC_SCOPE_OWNER_THREAD_REQUIRED");
   if(closed)return;OVERRIDE.remove();closed=true;
  }
 }
}
