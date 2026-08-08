package com.suileyan.cloud;

import java.util.Map;

/**
 * 登录上下文，承载登录所需输入
 * 阶段一为空实现；阶段二由账密表单/OAuth code/WebView 结果填充
 */
public class LoginContext {

    private final Map<String, String> inputs;

    public LoginContext(Map<String, String> inputs) {
        this.inputs = inputs;
    }

    public Map<String, String> inputs() {
        return inputs;
    }

    public String get(String key) {
        return inputs == null ? "" : String.valueOf(inputs.getOrDefault(key, ""));
    }
}
