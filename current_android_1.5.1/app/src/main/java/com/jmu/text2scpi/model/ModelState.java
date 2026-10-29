package com.jmu.text2scpi.model;
/**
 * 模型生命周期状态，区分加载、就绪与错误，供服务限制尚未就绪时的推理请求。
 */
public enum ModelState {
    UNINITIALIZED,COPYING_ASSETS,LOADING,READY,ERROR
}
