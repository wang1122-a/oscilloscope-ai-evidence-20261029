package com.jmu.text2scpi.intent;
import java.util.*;
import kotlinx.serialization.json.JsonObject;
/**
 * 单条 SCPI 命令的规则数据。保存参数类型、范围、枚举与单位等约束，供规则映射器校验使用。
 */
public final class CommandRule {
    public final String command,intent,operation,parameterType;
    public final Set<String> allowedUnits,enumValues;
    public final Set<Double> discreteValues;
    public final Double minValue,maxValue;
    public final boolean supportsQuery,capabilityCheckRequired,requiresAsciiQuotes;
    public final JsonObject capabilityRequirements;
    public CommandRule(String command,String intent,String operation,String parameterType,Set<String> allowedUnits,Set<String> enumValues,Set<Double> discreteValues,Double minValue,Double maxValue,boolean supportsQuery,JsonObject capabilityRequirements,boolean capabilityCheckRequired,boolean requiresAsciiQuotes) {
        this.command=command;
        this.intent=intent;
        this.operation=operation;
        this.parameterType=parameterType;
        this.allowedUnits=Collections.unmodifiableSet(new LinkedHashSet<>(allowedUnits));
        this.enumValues=Collections.unmodifiableSet(new LinkedHashSet<>(enumValues));
        this.discreteValues=Collections.unmodifiableSet(new LinkedHashSet<>(discreteValues));
        this.minValue=minValue;
        this.maxValue=maxValue;
        this.supportsQuery=supportsQuery;
        this.capabilityRequirements=capabilityRequirements;
        this.capabilityCheckRequired=capabilityCheckRequired;
        this.requiresAsciiQuotes=requiresAsciiQuotes;
    }
}
