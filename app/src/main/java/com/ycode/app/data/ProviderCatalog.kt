package com.ycode.app.data

import com.ycode.app.model.Provider

object ProviderCatalog {
    val all = listOf(
        Provider("custom", "自定义接口", "添加任意 OpenAI 兼容服务", "", "", "自定义"),
        Provider("deepseek", "DeepSeek", "深度求索，推理与代码能力突出", "https://api.deepseek.com", "deepseek-chat", "热门"),
        Provider("qwen", "通义千问", "阿里云百炼，中文与多模态模型", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "国内"),
        Provider("zhipu", "智谱 GLM", "智谱 AI，适合中文通用任务", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "国内"),
        Provider("moonshot", "月之暗面 Kimi", "长文本理解与中文内容处理", "https://api.moonshot.cn/v1", "moonshot-v1-8k", "长文本"),
        Provider("doubao", "豆包", "火山方舟，日常对话与内容生成", "https://ark.cn-beijing.volces.com/api/v3", "", "国内"),
        Provider("hunyuan", "腾讯混元", "腾讯云大模型服务", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest", "国内"),
        Provider("minimax", "MiniMax", "文本、语音与多模态能力", "https://api.minimax.chat/v1", "MiniMax-Text-01", "多模态"),
        Provider("baidu", "百度千帆", "文心系列及千帆模型平台", "https://qianfan.baidubce.com/v2", "ernie-4.0-8k-latest", "国内"),
        Provider("stepfun", "阶跃星辰", "Step 系列文本与多模态模型", "https://api.stepfun.com/v1", "step-2-16k", "国内"),
        Provider("yi", "零一万物", "Yi 系列中文与通用模型", "https://api.lingyiwanwu.com/v1", "yi-large", "国内"),
        Provider("baichuan", "百川智能", "百川大模型开放平台", "https://api.baichuan-ai.com/v1", "Baichuan4", "国内"),
        Provider("modelscope", "魔搭社区", "阿里魔搭开源模型推理服务", "https://api-inference.modelscope.cn/v1", "Qwen/Qwen2.5-72B-Instruct", "开源"),
        Provider("siliconflow", "硅基流动", "国内多种开源模型统一接口", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3", "模型广场"),
        Provider("openai", "OpenAI", "GPT 系列通用与推理模型", "https://api.openai.com/v1", "gpt-4.1-mini", "国际"),
        Provider("anthropic", "Anthropic Claude", "Claude 系列长文本与编程模型", "https://api.anthropic.com/v1", "claude-sonnet-4-5", "国际"),
        Provider("gemini", "Google Gemini", "Google 多模态 Gemini 模型", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "国际"),
        Provider("xai", "xAI Grok", "Grok 系列实时与推理模型", "https://api.x.ai/v1", "grok-3-mini", "国际"),
        Provider("mistral", "Mistral AI", "欧洲开源与商用模型平台", "https://api.mistral.ai/v1", "mistral-small-latest", "国际"),
        Provider("groq", "Groq", "高速开源模型推理服务", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "高速"),
        Provider("openrouter", "OpenRouter", "全球多模型统一路由平台", "https://openrouter.ai/api/v1", "openai/gpt-4.1-mini", "聚合"),
        Provider("together", "Together AI", "开源模型训练与推理平台", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo", "开源"),
        Provider("fireworks", "Fireworks AI", "高性能生成式 AI 推理平台", "https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/llama-v3p3-70b-instruct", "高速"),
        Provider("perplexity", "Perplexity", "联网搜索与答案生成模型", "https://api.perplexity.ai", "sonar", "联网"),
        Provider("cerebras", "Cerebras", "超高速大模型推理服务", "https://api.cerebras.ai/v1", "llama-3.3-70b", "高速"),
        Provider("nvidia", "NVIDIA NIM", "NVIDIA 模型推理微服务", "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct", "平台")
    )
}
