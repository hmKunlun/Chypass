# Chypass
Chypass - AI驱动的XSS WAF绕过工具
[开源协议: MIT](https://opensource.org/licenses/MIT)
[BurpSuite扩展](https://portswigger.net/bappstore)
[Java](https://java.com)

Chypass是一款先进的Burp Suite扩展插件，利用人工智能技术检测XSS漏洞并智能绕过WAF（Web应用防火墙）保护。该工具能够自动化分析、构造和测试复杂的XSS有效载荷，根据服务器响应进行自适应调整。

免责声明
本工具仅用于合法安全测试和教育目的。在测试任何系统前，务必获得适当授权。作者对本工具的任何滥用或造成的损害不承担责任。

Chypass界面截图
![image](https://github.com/user-attachments/assets/6050c7b5-c2d5-41b0-bb3d-2102c441c75e)

![image](https://github.com/user-attachments/assets/05055136-d6a4-434a-9df1-42b13228ec3f)


功能特点
AI驱动的XSS分析：集成多种大型语言模型API（DeepSeek、SiliconFlow、Kimi、QwenAI）分析请求、响应并构造有效的XSS载荷
自动化WAF绕过：基于WAF行为和响应模式智能调整有效载荷
多次迭代测试：执行持续测试循环，根据先前的响应分析优化有效载荷
全面的绕过技术：采用多种规避方法，包括：
字符编码变种
HTML实体修改
SVG矢量封装
协议级混淆
逻辑分段处理
上下文感知的有效载荷生成
直观的用户界面：整洁有序的控制面板，可追踪所有测试迭代历史
详细的测试分析：直观显示XSS成功概率和WAF检测状态
手动干预选项：必要时可手动修复和自定义有效载荷

系统要求
Burp Suite专业版或社区版（v2.0或更高版本）
Java 11或更高版本
至少一个支持的AI提供商的有效API密钥

贡献
欢迎贡献！请随时提交Pull Request。

许可证
本项目采用MIT许可证 - 详情请参阅LICENSE文件。

本工具使用各种AI API进行智能载荷生成
后续待更新
非常感谢大家！
