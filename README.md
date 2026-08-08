# Ycode Android

Ycode 是一个使用 Kotlin 与 XML Views 编写的本地优先 Android AI 工作区。

## 特性

- 支持 OpenAI 兼容模型接口与本地 API 密钥配置
- 普通聊天与可调用文件工具的任务模式
- AI 按需请求 Android 系统目录授权
- 本地工作区、文件编辑、搜索与网页/XML 预览
- SSH、SFTP、FTP 远程连接工具
- 本地密钥使用统计，不上传密钥或聊天历史
- 仅通过 GitHub Release 检查版本更新

## 隐私

Ycode 不需要账号，不连接 Ycode 业务服务器。模型请求只发送到用户主动配置的模型服务商；GitHub 更新检查只访问本仓库的 Release API。模型密钥、聊天历史、目录授权和本地使用统计保存在设备本机。

## 构建

1. 使用 Android Studio 打开项目根目录。
2. 使用 JDK 17 或 Android Studio 内置 JDK。
3. 安装 Android SDK 35。
4. Windows 运行 `gradlew.bat assembleDebug`；其他系统可使用本机 Gradle 执行 `gradle assembleDebug`。

Release 签名通过以下环境变量配置，仓库中不包含签名文件或密码：

```text
YCODE_STORE_FILE
YCODE_STORE_PASSWORD
YCODE_KEY_ALIAS
YCODE_KEY_PASSWORD
```

## 项目信息

- 包名：`com.ycode.app`
- 最低 Android 版本：Android 7.0（API 24）
- 作者：安安
- QQ：3391649367
- GitHub：<https://github.com/anzhichul/Ycode>

## License

本项目暂未指定开源许可证。发布或分发前请由作者选择合适的许可证。
