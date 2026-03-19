package com.openbash.forja.ui;

import com.openbash.forja.agent.AgentAction;
import com.openbash.forja.agent.AgentMode;
import com.openbash.forja.agent.AgentResponse;
import com.openbash.forja.agent.BurpAgent;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.toolkit.GeneratedTool;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class AgentTab extends JPanel {

    private final BurpAgent agent;
    private final ConfigManager config;
    private JTextPane chatPane;
    private JTextField inputField;
    private JButton sendBtn;
    private JButton stopBtn;
    private JComboBox<AgentMode> modeCombo;
    private JLabel statusLabel;
    private JProgressBar progressBar;

    // Right panel - files
    private DefaultListModel<String> filesListModel;
    private JList<String> filesList;
    private JTextArea codeViewer;
    private JTextField pathField;

    private Timer progressTimer;
    private int elapsedSeconds;
    private int lastKnownToolCount = 0;
    private volatile boolean stopRequested = false;
    private SwingWorker<?, ?> currentWorker = null;

    // Output directory for auto-saving generated files (mutable, persisted in config)
    private Path outputDir;

    // Colors
    private static final Color BG_DARK = new Color(30, 30, 30);
    private static final Color BG_SIDEBAR = new Color(25, 25, 25);
    private static final Color BG_INPUT = new Color(45, 45, 45);
    private static final Color BG_TOOLBAR = new Color(38, 38, 38);
    private static final Color BG_CODE = new Color(20, 20, 20);
    private static final Color TEXT_DEFAULT = new Color(210, 210, 210);
    private static final Color TEXT_USER = new Color(220, 220, 255);
    private static final Color TEXT_AGENT = new Color(200, 230, 200);
    private static final Color TEXT_ACTION = new Color(130, 180, 230);
    private static final Color TEXT_ACTION_RESULT = new Color(160, 160, 160);
    private static final Color TEXT_ERROR = new Color(255, 110, 110);
    private static final Color TEXT_MUTED = new Color(120, 120, 120);
    private static final Color TEXT_CODE = new Color(190, 220, 190);
    private static final Color ACCENT_GREEN = new Color(80, 200, 120);
    private static final Color ACCENT_SEND = new Color(100, 160, 255);
    private static final Color BORDER_COLOR = new Color(55, 55, 55);
    private static final Color PROMPT_BG = new Color(40, 40, 40);
    private static final Color PROMPT_HOVER = new Color(55, 55, 60);
    private static final Color FILE_SELECTED = new Color(50, 70, 90);

    private static final Font CHAT_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private static final Font LABEL_FONT = new Font(Font.MONOSPACED, Font.BOLD, 13);
    private static final Font INPUT_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    private static final Font CODE_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Font SIDEBAR_FONT = new Font(Font.DIALOG, Font.PLAIN, 11);
    private static final Font SIDEBAR_TITLE = new Font(Font.DIALOG, Font.BOLD, 11);
    private static final String PLACEHOLDER = "Type a command...";

    // Predefined prompts: {null, "CATEGORY"} for headers, {label, prompt} for actions
    private static final String[][] PROMPTS = {
            {null, "RECON & SCOPE"},
            {"Scope status", "what hosts are in scope and what's not?"},
            {"Map attack surface", "list all endpoints grouped by functionality, highlight the ones with parameters and auth"},
            {"Interesting params", "show me endpoints with query parameters that could be injectable"},
            {"Hidden endpoints", "list endpoints that returned 403, 401, or 301 — potential access control bypasses"},
            {"Tech fingerprint", "show the full tech stack detected and any interesting patterns"},
            {"Burp issues", "list all issues found by Burp's scanner, grouped by severity"},

            {null, "AUTH & SESSION"},
            {"Test auth bypass", "send all authenticated endpoints to repeater without the auth headers to test for auth bypass"},
            {"Session analysis", "analyze cookies and auth tokens — are they secure? httponly? samesite? predictable?"},
            {"JWT audit", "check if there are JWT tokens — analyze algorithm, expiration, and generate a jwt-manipulator tool"},
            {"Privilege escalation", "generate a Burp extension that replays requests swapping auth tokens between user roles"},

            {null, "INJECTION & INPUT"},
            {"SQLi candidates", "list endpoints with parameters likely vulnerable to SQL injection and send them to intruder"},
            {"XSS candidates", "find endpoints that reflect user input in responses — potential XSS vectors"},
            {"Parameter fuzzer", "generate a fuzzer script for all query parameters using SQLi, XSS and command injection payloads"},
            {"Mass assignment", "find POST/PUT endpoints with JSON bodies — test for mass assignment by adding admin/role fields"},

            {null, "IDOR & ACCESS CONTROL"},
            {"IDOR scan", "find endpoints with sequential or predictable IDs and generate an IDOR scanner"},
            {"Horizontal privesc", "identify endpoints that access user-specific resources — test accessing other users' data"},
            {"Forced browsing", "generate a tool that brute-forces common admin paths and sensitive files on the target"},

            {null, "API SECURITY"},
            {"API enumeration", "list all API endpoints with their methods, params, and response codes"},
            {"Rate limit test", "generate a script to test rate limiting on login and sensitive endpoints"},
            {"CORS check", "check for CORS misconfigurations — test with arbitrary origins on all endpoints"},
            {"Verb tampering", "send GET endpoints to repeater with PUT/DELETE/PATCH to test HTTP verb tampering"},

            {null, "EXPLOIT & REPORT"},
            {"Generate all PoCs", "generate proof-of-concept exploit scripts for all critical and high findings"},
            {"Full report", "generate a detailed markdown pentest report with findings, evidence, and recommendations"},
            {"Active scan", "start an active scan on all in-scope targets"},
            {"Traffic sniffer", "generate and inject a JS traffic sniffer that captures all XHR/fetch requests in the browser"},
    };

    public AgentTab(BurpAgent agent, ConfigManager config) {
        this.agent = agent;
        this.config = config;
        this.outputDir = initOutputDir();
        setLayout(new BorderLayout());
        setBackground(BG_DARK);

        // --- LEFT: Prompts sidebar ---
        JPanel leftSidebar = buildPromptsSidebar();

        // --- CENTER: Chat (toolbar + messages + input) ---
        JPanel centerPanel = buildCenterPanel();

        // --- RIGHT: Files panel ---
        JPanel rightPanel = buildFilesPanel();

        // Layout: left | center | right
        JSplitPane rightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerPanel, rightPanel);
        rightSplit.setDividerLocation(600);
        rightSplit.setDividerSize(1);
        rightSplit.setResizeWeight(0.65);
        rightSplit.setBorder(BorderFactory.createEmptyBorder());
        rightSplit.setContinuousLayout(true);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSidebar, rightSplit);
        mainSplit.setDividerLocation(190);
        mainSplit.setDividerSize(1);
        mainSplit.setBorder(BorderFactory.createEmptyBorder());
        mainSplit.setContinuousLayout(true);

        add(mainSplit, BorderLayout.CENTER);

        // Welcome
        appendAgent("Forja Agent ready. I can control Burp Suite for you.\n"
                + "Use the quick prompts on the left, or type your own command.\n\n"
                + "Modes:  Ask = single Q&A  |  Agent = autonomous multi-step  |  Planner = attack plan without executing\n"
                + "Generated files auto-saved to: " + outputDir);
    }

    // ========== Output directory ==========

    private Path initOutputDir() {
        Path dir = Path.of(config.getOutputDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {}
        return dir;
    }

    /** Returns the current output directory. Used by other tabs (e.g. ToolkitTab) to save files. */
    public Path getOutputDir() {
        return outputDir;
    }

    private void autoSaveFile(GeneratedTool tool) {
        String filename = tool.getName().replaceAll("[^a-zA-Z0-9._-]", "_") + fileExtension(tool.getLanguage());
        Path filePath = outputDir.resolve(filename);
        try {
            Files.writeString(filePath, tool.getCode());
        } catch (IOException ignored) {}
    }

    // ========== Left sidebar (prompts only) ==========

    private JPanel buildPromptsSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(BG_SIDEBAR);
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER_COLOR));
        sidebar.setMinimumSize(new Dimension(150, 0));

        JPanel promptsSection = new JPanel();
        promptsSection.setLayout(new BoxLayout(promptsSection, BoxLayout.Y_AXIS));
        promptsSection.setBackground(BG_SIDEBAR);
        promptsSection.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

        for (String[] prompt : PROMPTS) {
            if (prompt[0] == null) {
                JLabel cat = new JLabel("  " + prompt[1]);
                cat.setFont(SIDEBAR_TITLE);
                cat.setForeground(ACCENT_GREEN);
                cat.setAlignmentX(LEFT_ALIGNMENT);
                cat.setBorder(BorderFactory.createEmptyBorder(10, 8, 4, 8));
                promptsSection.add(cat);
            } else {
                promptsSection.add(createPromptButton(prompt[0], prompt[1]));
            }
        }

        JScrollPane scroll = new JScrollPane(promptsSection);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG_SIDEBAR);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sidebar.add(scroll, BorderLayout.CENTER);
        return sidebar;
    }

    private JButton createPromptButton(String label, String prompt) {
        JButton btn = new JButton(label);
        btn.setFont(SIDEBAR_FONT);
        btn.setForeground(TEXT_DEFAULT);
        btn.setBackground(PROMPT_BG);
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        btn.setAlignmentX(LEFT_ALIGNMENT);
        btn.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 8));
        btn.setToolTipText(prompt);
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { btn.setBackground(PROMPT_HOVER); }
            @Override public void mouseExited(MouseEvent e) { btn.setBackground(PROMPT_BG); }
        });
        btn.addActionListener(e -> {
            inputField.setText(prompt);
            inputField.setForeground(TEXT_DEFAULT);
            sendMessage();
        });
        return btn;
    }

    // ========== Center panel (toolbar + chat + input) ==========

    private JPanel buildCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_DARK);

        // Toolbar
        panel.add(buildToolbar(), BorderLayout.NORTH);

        // Chat
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setBackground(BG_DARK);
        chatPane.setForeground(TEXT_DEFAULT);
        chatPane.setFont(CHAT_FONT);
        chatPane.setCaretColor(BG_DARK);
        chatPane.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.setBorder(BorderFactory.createEmptyBorder());
        chatScroll.getViewport().setBackground(BG_DARK);
        chatScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        chatScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(chatScroll, BorderLayout.CENTER);

        // Input
        panel.add(buildInputPanel(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setBackground(BG_TOOLBAR);
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        left.setOpaque(false);

        // Mode selector
        JLabel modeLabel = new JLabel("Mode:");
        modeLabel.setForeground(TEXT_MUTED);
        modeLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));
        left.add(modeLabel);

        modeCombo = new JComboBox<>(AgentMode.values());
        modeCombo.setFont(new Font(Font.DIALOG, Font.BOLD, 11));
        modeCombo.setBackground(BG_INPUT);
        modeCombo.setForeground(ACCENT_GREEN);
        modeCombo.setPreferredSize(new Dimension(95, 24));
        modeCombo.addActionListener(e -> {
            AgentMode sel = (AgentMode) modeCombo.getSelectedItem();
            if (sel != null) {
                agent.setMode(sel);
                statusLabel.setText(sel.getDisplayName() + " mode");
            }
        });
        left.add(modeCombo);
        left.add(Box.createHorizontalStrut(4));

        JButton clearBtn = createToolbarButton("Clear");
        clearBtn.addActionListener(e -> clearChat());
        left.add(clearBtn);

        stopBtn = new JButton("Stop");
        stopBtn.setFont(new Font(Font.DIALOG, Font.BOLD, 11));
        stopBtn.setForeground(Color.WHITE);
        stopBtn.setBackground(TEXT_ERROR);
        stopBtn.setFocusPainted(false);
        stopBtn.setBorderPainted(false);
        stopBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        stopBtn.setPreferredSize(new Dimension(60, 24));
        stopBtn.setVisible(false);
        stopBtn.addActionListener(e -> requestStop());
        left.add(stopBtn);

        toolbar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        right.setOpaque(false);
        statusLabel = new JLabel("Ready");
        statusLabel.setForeground(TEXT_MUTED);
        statusLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(100, 12));
        progressBar.setVisible(false);
        progressBar.setBorderPainted(false);
        right.add(statusLabel);
        right.add(progressBar);
        toolbar.add(right, BorderLayout.EAST);

        return toolbar;
    }

    private JPanel buildInputPanel() {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG_DARK);
        wrapper.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR));

        JPanel inputPanel = new JPanel(new BorderLayout(8, 0));
        inputPanel.setBackground(BG_INPUT);
        inputPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(10, 16, 10, 16),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                        BorderFactory.createEmptyBorder(8, 12, 8, 8)
                )
        ));

        inputField = new JTextField();
        inputField.setFont(INPUT_FONT);
        inputField.setBackground(BG_INPUT);
        inputField.setForeground(TEXT_MUTED);
        inputField.setCaretColor(TEXT_DEFAULT);
        inputField.setBorder(BorderFactory.createEmptyBorder());
        inputField.setText(PLACEHOLDER);

        inputField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (inputField.getText().equals(PLACEHOLDER)) {
                    inputField.setText("");
                    inputField.setForeground(TEXT_DEFAULT);
                }
            }
            @Override
            public void focusLost(FocusEvent e) {
                if (inputField.getText().trim().isEmpty()) {
                    inputField.setText(PLACEHOLDER);
                    inputField.setForeground(TEXT_MUTED);
                }
            }
        });

        sendBtn = new JButton("Send");
        sendBtn.setFont(new Font(Font.DIALOG, Font.BOLD, 12));
        sendBtn.setForeground(Color.WHITE);
        sendBtn.setBackground(ACCENT_SEND);
        sendBtn.setFocusPainted(false);
        sendBtn.setBorderPainted(false);
        sendBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendBtn.setPreferredSize(new Dimension(70, 30));

        inputField.addActionListener(e -> sendMessage());
        sendBtn.addActionListener(e -> sendMessage());

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendBtn, BorderLayout.EAST);
        wrapper.add(inputPanel, BorderLayout.CENTER);
        return wrapper;
    }

    // ========== Right panel (files) ==========

    private JPanel buildFilesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_SIDEBAR);
        panel.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, BORDER_COLOR));
        panel.setMinimumSize(new Dimension(200, 0));

        // Header: title + editable path + browse/copy buttons + recommendation
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(BG_TOOLBAR);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));

        JLabel title = new JLabel("  GENERATED FILES");
        title.setFont(SIDEBAR_TITLE);
        title.setForeground(TEXT_MUTED);
        title.setAlignmentX(LEFT_ALIGNMENT);
        title.setBorder(BorderFactory.createEmptyBorder(6, 4, 4, 4));
        header.add(title);

        // Output path label
        JLabel pathLabel = new JLabel("  Output directory:");
        pathLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 10));
        pathLabel.setForeground(TEXT_MUTED);
        pathLabel.setAlignmentX(LEFT_ALIGNMENT);
        pathLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 4));
        header.add(pathLabel);

        // Path row: editable text field + Browse + Copy buttons
        JPanel pathRow = new JPanel(new BorderLayout(3, 0));
        pathRow.setBackground(BG_TOOLBAR);
        pathRow.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        pathRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        pathRow.setAlignmentX(LEFT_ALIGNMENT);

        pathField = new JTextField(outputDir.toString());
        pathField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        pathField.setBackground(BG_INPUT);
        pathField.setForeground(ACCENT_GREEN);
        pathField.setCaretColor(ACCENT_GREEN);
        pathField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
        ));
        pathField.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { pathField.selectAll(); }
        });
        // Commit path on Enter
        pathField.addActionListener(e -> changeOutputDir(pathField.getText().trim()));
        pathRow.add(pathField, BorderLayout.CENTER);

        JPanel pathButtons = new JPanel();
        pathButtons.setLayout(new BoxLayout(pathButtons, BoxLayout.X_AXIS));
        pathButtons.setOpaque(false);

        JButton browseBtn = new JButton("...");
        browseBtn.setFont(new Font(Font.DIALOG, Font.BOLD, 11));
        browseBtn.setForeground(TEXT_DEFAULT);
        browseBtn.setBackground(BG_INPUT);
        browseBtn.setFocusPainted(false);
        browseBtn.setBorderPainted(false);
        browseBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        browseBtn.setMinimumSize(new Dimension(32, 24));
        browseBtn.setPreferredSize(new Dimension(32, 24));
        browseBtn.setMaximumSize(new Dimension(32, 24));
        browseBtn.setToolTipText("Browse for output directory");
        browseBtn.addActionListener(e -> browseOutputDir());
        pathButtons.add(browseBtn);
        pathButtons.add(Box.createHorizontalStrut(2));

        JButton copyPathBtn = new JButton("Copy Path");
        copyPathBtn.setFont(new Font(Font.DIALOG, Font.BOLD, 10));
        copyPathBtn.setForeground(Color.WHITE);
        copyPathBtn.setBackground(ACCENT_GREEN);
        copyPathBtn.setFocusPainted(false);
        copyPathBtn.setBorderPainted(false);
        copyPathBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copyPathBtn.setMinimumSize(new Dimension(72, 24));
        copyPathBtn.setPreferredSize(new Dimension(72, 24));
        copyPathBtn.setMaximumSize(new Dimension(72, 24));
        copyPathBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(outputDir.toString()), null);
            statusLabel.setText("Path copied!");
            copyPathBtn.setText("OK!");
            Timer t = new Timer(1500, ev -> copyPathBtn.setText("Copy Path"));
            t.setRepeats(false);
            t.start();
        });
        pathButtons.add(copyPathBtn);

        pathRow.add(pathButtons, BorderLayout.EAST);
        header.add(pathRow);

        // Recommendation — plain text JTextArea (no HTML rendering issues)
        String recText = "Tip: Set this to your project directory where you run "
                + "Claude Code, Cursor, or your agent framework. Generated "
                + "scripts and reports will be available there instantly.";
        JTextArea rec = new JTextArea(recText);
        rec.setEditable(false);
        rec.setLineWrap(true);
        rec.setWrapStyleWord(true);
        rec.setFont(new Font(Font.DIALOG, Font.ITALIC, 10));
        rec.setForeground(TEXT_MUTED);
        rec.setBackground(BG_TOOLBAR);
        rec.setBorder(BorderFactory.createEmptyBorder(2, 12, 6, 12));
        rec.setAlignmentX(LEFT_ALIGNMENT);
        rec.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        header.add(rec);

        panel.add(header, BorderLayout.NORTH);

        // File list (top) + code viewer (bottom) in split
        filesListModel = new DefaultListModel<>();
        filesList = new JList<>(filesListModel);
        filesList.setBackground(BG_SIDEBAR);
        filesList.setForeground(TEXT_DEFAULT);
        filesList.setFont(SIDEBAR_FONT);
        filesList.setSelectionBackground(FILE_SELECTED);
        filesList.setSelectionForeground(ACCENT_GREEN);
        filesList.setFixedCellHeight(22);
        filesList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelectedFileContent();
        });

        JScrollPane listScroll = new JScrollPane(filesList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.getViewport().setBackground(BG_SIDEBAR);
        listScroll.setPreferredSize(new Dimension(0, 120));

        // Code viewer
        codeViewer = new JTextArea();
        codeViewer.setEditable(false);
        codeViewer.setBackground(BG_CODE);
        codeViewer.setForeground(TEXT_CODE);
        codeViewer.setFont(CODE_FONT);
        codeViewer.setCaretColor(BG_CODE);
        codeViewer.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        codeViewer.setLineWrap(false);
        codeViewer.setTabSize(4);
        codeViewer.setText("Select a file to view its content.");

        JScrollPane codeScroll = new JScrollPane(codeViewer);
        codeScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR));
        codeScroll.getViewport().setBackground(BG_CODE);

        JSplitPane filesSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listScroll, codeScroll);
        filesSplit.setDividerLocation(130);
        filesSplit.setDividerSize(1);
        filesSplit.setResizeWeight(0.2);
        filesSplit.setBorder(BorderFactory.createEmptyBorder());
        panel.add(filesSplit, BorderLayout.CENTER);

        // Action buttons
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        buttons.setBackground(BG_TOOLBAR);
        buttons.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COLOR));

        JButton runBtn = createFileButton("Run");
        runBtn.setForeground(ACCENT_GREEN);
        runBtn.addActionListener(e -> runSelectedFile());
        buttons.add(runBtn);

        JButton copyBtn = createFileButton("Copy");
        copyBtn.addActionListener(e -> copySelectedFile());
        buttons.add(copyBtn);

        JButton saveAsBtn = createFileButton("Save As...");
        saveAsBtn.addActionListener(e -> saveSelectedFile());
        buttons.add(saveAsBtn);

        JButton saveAllBtn = createFileButton("Save All");
        saveAllBtn.addActionListener(e -> saveAllFiles());
        buttons.add(saveAllBtn);

        JButton openDirBtn = createFileButton("Open Dir");
        openDirBtn.addActionListener(e -> openOutputDir());
        buttons.add(openDirBtn);

        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JButton createFileButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font(Font.DIALOG, Font.PLAIN, 10));
        btn.setForeground(TEXT_DEFAULT);
        btn.setBackground(BG_INPUT);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setMargin(new Insets(2, 8, 2, 8));
        return btn;
    }

    // ========== Message rendering ==========

    private void appendUser(String text) {
        appendLabel("You", TEXT_USER);
        appendStyled(text + "\n\n", CHAT_FONT, TEXT_USER);
    }

    private void appendAgent(String text) {
        appendLabel("Agent", ACCENT_GREEN);
        appendStyled(text + "\n\n", CHAT_FONT, TEXT_AGENT);
    }

    private void appendAction(String tool, String result) {
        appendStyled("  > " + tool, LABEL_FONT, TEXT_ACTION);
        appendStyled("  " + result + "\n\n", CHAT_FONT, TEXT_ACTION_RESULT);
    }

    private void appendError(String text) {
        appendLabel("Error", TEXT_ERROR);
        appendStyled(text + "\n\n", CHAT_FONT, TEXT_ERROR);
    }

    private void appendLabel(String label, Color color) {
        appendStyled(label + "\n", LABEL_FONT, color);
    }

    private void appendStyled(String text, Font font, Color color) {
        StyledDocument doc = chatPane.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attrs, font.getFamily());
        StyleConstants.setFontSize(attrs, font.getSize());
        StyleConstants.setBold(attrs, font.isBold());
        StyleConstants.setForeground(attrs, color);
        try {
            doc.insertString(doc.getLength(), text, attrs);
        } catch (BadLocationException ignored) {}
        chatPane.setCaretPosition(doc.getLength());
    }

    // ========== Send logic ==========

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty() || text.equals(PLACEHOLDER)) return;

        inputField.setText("");
        inputField.setForeground(TEXT_DEFAULT);
        setInputEnabled(false);
        appendUser(text);

        AgentMode currentMode = agent.getMode();
        switch (currentMode) {
            case AGENT -> sendAgentMode(text);
            default -> sendAskMode(text);
        }
    }

    private void sendAskMode(String text) {
        startProgress("Thinking...");
        stopBtn.setVisible(true);
        stopRequested = false;

        currentWorker = new SwingWorker<AgentResponse, Void>() {
            @Override
            protected AgentResponse doInBackground() throws Exception {
                return agent.processUserMessage(text);
            }

            @Override
            protected void done() {
                stopBtn.setVisible(false);
                if (stopRequested) {
                    appendStyled("Cancelled.\n\n", LABEL_FONT, TEXT_MUTED);
                    stopProgress("Cancelled");
                } else {
                    try {
                        AgentResponse response = get();
                        appendAgent(response.getResponse());
                        executeActions(response.getActions());
                        refreshFilesList();
                        stopProgress("Ready");
                    } catch (Exception e) {
                        String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                        appendError(msg);
                        stopProgress("Error");
                    }
                }
                setInputEnabled(true);
                currentWorker = null;
            }
        };
        currentWorker.execute();
    }

    private void sendAgentMode(String text) {
        stopRequested = false;
        stopBtn.setVisible(true);
        modeCombo.setEnabled(false);
        startProgress("Agent working...");

        currentWorker = new SwingWorker<Void, AgentUpdate>() {
            @Override
            protected Void doInBackground() throws Exception {
                int maxIterations = agent.getMaxAgentIterations();

                AgentResponse response = agent.processUserMessage(text);
                publish(new AgentUpdate(response, 1));

                int iteration = 1;
                while (response.isWorking() && iteration < maxIterations && !stopRequested) {
                    for (AgentAction action : response.getActions()) {
                        if (stopRequested) break;
                        String result = agent.executeAction(action);
                        publish(new AgentUpdate(action, result));
                    }

                    if (stopRequested) break;

                    iteration++;
                    response = agent.processUserMessage(
                            "[System] Actions completed. Continue with the mission. Iteration " + iteration + "/" + maxIterations);
                    publish(new AgentUpdate(response, iteration));
                }

                if (!stopRequested) {
                    for (AgentAction action : response.getActions()) {
                        String result = agent.executeAction(action);
                        publish(new AgentUpdate(action, result));
                    }
                }

                return null;
            }

            @Override
            protected void process(List<AgentUpdate> updates) {
                for (AgentUpdate update : updates) {
                    if (update.response != null) {
                        String iterLabel = " [step " + update.iteration + "]";
                        appendAgent(update.response.getResponse() + iterLabel);
                        if (update.response.getNextStep() != null) {
                            appendStyled("  Next: " + update.response.getNextStep() + "\n\n",
                                    CHAT_FONT, TEXT_MUTED);
                        }
                    }
                    if (update.action != null) {
                        appendAction(update.action.getTool(), update.actionResult);
                    }
                    refreshFilesList();
                }
            }

            @Override
            protected void done() {
                try {
                    get();
                    if (stopRequested) {
                        appendStyled("Agent stopped by user.\n\n", LABEL_FONT, TEXT_MUTED);
                        stopProgress("Stopped");
                    } else {
                        stopProgress("Done");
                    }
                } catch (Exception e) {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    appendError(msg);
                    stopProgress("Error");
                }
                stopBtn.setVisible(false);
                modeCombo.setEnabled(true);
                setInputEnabled(true);
                stopRequested = false;
                currentWorker = null;
            }
        };
        currentWorker.execute();
    }

    private void requestStop() {
        stopRequested = true;
        if (currentWorker != null) {
            currentWorker.cancel(true);
        }
        stopBtn.setVisible(false);
        statusLabel.setText("Stopping...");
    }

    private void setInputEnabled(boolean enabled) {
        inputField.setEnabled(enabled);
        sendBtn.setEnabled(enabled);
        if (enabled) inputField.requestFocus();
    }

    private void executeActions(List<AgentAction> actions) {
        for (AgentAction action : actions) {
            String result = agent.executeAction(action);
            appendAction(action.getTool(), result);
        }
    }

    private static class AgentUpdate {
        final AgentResponse response;
        final int iteration;
        final AgentAction action;
        final String actionResult;

        AgentUpdate(AgentResponse response, int iteration) {
            this.response = response;
            this.iteration = iteration;
            this.action = null;
            this.actionResult = null;
        }

        AgentUpdate(AgentAction action, String actionResult) {
            this.response = null;
            this.iteration = 0;
            this.action = action;
            this.actionResult = actionResult;
        }
    }

    private void clearChat() {
        chatPane.setText("");
        agent.clearHistory();
        appendAgent("Chat cleared. How can I help?");
        statusLabel.setText("Ready");
    }

    // ========== Files panel logic ==========

    private void refreshFilesList() {
        List<GeneratedTool> tools = agent.getGeneratedTools();
        if (tools.size() > lastKnownToolCount) {
            for (int i = lastKnownToolCount; i < tools.size(); i++) {
                GeneratedTool t = tools.get(i);
                String filename = t.getName() + fileExtension(t.getLanguage());
                filesListModel.addElement(filename);
                autoSaveFile(t);
            }
            lastKnownToolCount = tools.size();
            filesList.setSelectedIndex(filesListModel.size() - 1);
        }
    }

    private void showSelectedFileContent() {
        int idx = filesList.getSelectedIndex();
        if (idx < 0) {
            codeViewer.setText("Select a file to view its content.");
            return;
        }
        List<GeneratedTool> tools = agent.getGeneratedTools();
        if (idx >= tools.size()) return;
        GeneratedTool tool = tools.get(idx);
        codeViewer.setText(tool.getCode());
        codeViewer.setCaretPosition(0);
    }

    private void copySelectedFile() {
        int idx = filesList.getSelectedIndex();
        if (idx < 0) return;
        List<GeneratedTool> tools = agent.getGeneratedTools();
        if (idx >= tools.size()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(tools.get(idx).getCode()), null);
        statusLabel.setText("Copied to clipboard");
    }

    private void saveSelectedFile() {
        int idx = filesList.getSelectedIndex();
        if (idx < 0) return;
        List<GeneratedTool> tools = agent.getGeneratedTools();
        if (idx >= tools.size()) return;
        GeneratedTool tool = tools.get(idx);

        JFileChooser fc = new JFileChooser(outputDir.toFile());
        fc.setSelectedFile(new File(tool.getName().replaceAll("[^a-zA-Z0-9._-]", "_") + fileExtension(tool.getLanguage())));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.writeString(fc.getSelectedFile().toPath(), tool.getCode());
                statusLabel.setText("Saved: " + fc.getSelectedFile().getName());
            } catch (IOException e) {
                statusLabel.setText("Save failed: " + e.getMessage());
            }
        }
    }

    private void saveAllFiles() {
        List<GeneratedTool> tools = agent.getGeneratedTools();
        if (tools.isEmpty()) {
            statusLabel.setText("No files to save");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Choose directory to save all files");
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path dir = fc.getSelectedFile().toPath();
            int saved = 0;
            for (GeneratedTool tool : tools) {
                String filename = tool.getName().replaceAll("[^a-zA-Z0-9._-]", "_") + fileExtension(tool.getLanguage());
                try {
                    Files.writeString(dir.resolve(filename), tool.getCode());
                    saved++;
                } catch (IOException ignored) {}
            }
            statusLabel.setText("Saved " + saved + " files to " + dir.getFileName());
        }
    }

    private void runSelectedFile() {
        int idx = filesList.getSelectedIndex();
        if (idx < 0) {
            statusLabel.setText("Select a file first");
            return;
        }
        List<GeneratedTool> tools = agent.getGeneratedTools();
        if (idx >= tools.size()) return;
        GeneratedTool tool = tools.get(idx);

        // Determine interpreter based on language/extension
        String lang = tool.getLanguage();
        String[] command = resolveCommand(lang);
        if (command == null) {
            codeViewer.setText("[Run] No interpreter found for ." + lang + " files.\n"
                    + "Supported: python (.py), javascript (.js), bash (.sh).\n"
                    + "You can run it manually from: " + outputDir);
            return;
        }

        // Make sure file is saved to disk
        String filename = tool.getName().replaceAll("[^a-zA-Z0-9._-]", "_") + fileExtension(lang);
        Path filePath = outputDir.resolve(filename);
        try {
            Files.writeString(filePath, tool.getCode());
        } catch (IOException e) {
            codeViewer.setText("[Run] Failed to write file: " + e.getMessage());
            return;
        }

        // Make executable if shell script
        if ("bash".equals(lang) || "sh".equals(lang)) {
            filePath.toFile().setExecutable(true);
        }

        // Build full command
        String[] fullCmd = new String[command.length + 1];
        System.arraycopy(command, 0, fullCmd, 0, command.length);
        fullCmd[command.length] = filePath.toString();

        statusLabel.setText("Running " + filename + "...");
        codeViewer.setText("[Run] $ " + String.join(" ", fullCmd) + "\n\n");

        new SwingWorker<String, String>() {
            @Override
            protected String doInBackground() throws Exception {
                ProcessBuilder pb = new ProcessBuilder(fullCmd);
                pb.directory(outputDir.toFile());
                pb.redirectErrorStream(true);
                Process proc = pb.start();

                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        publish(line);
                    }
                }

                boolean finished = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    return "\n[Timeout] Process killed after 60s";
                }
                return "\n[Exit code: " + proc.exitValue() + "]";
            }

            @Override
            protected void process(List<String> lines) {
                for (String line : lines) {
                    codeViewer.append(line + "\n");
                }
                // Auto-scroll
                codeViewer.setCaretPosition(codeViewer.getDocument().getLength());
            }

            @Override
            protected void done() {
                try {
                    String result = get();
                    codeViewer.append(result);
                    statusLabel.setText("Done");
                } catch (Exception e) {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    codeViewer.append("\n[Error] " + msg);
                    statusLabel.setText("Run failed");
                }
            }
        }.execute();
    }

    private static String[] resolveCommand(String language) {
        return switch (language) {
            case "python" -> findExecutable("python3", "python");
            case "javascript" -> findExecutable("node");
            case "bash", "sh" -> findExecutable("bash", "sh");
            case "ruby" -> findExecutable("ruby");
            case "perl" -> findExecutable("perl");
            default -> null;
        };
    }

    private static String[] findExecutable(String... candidates) {
        for (String cmd : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder("which", cmd);
                Process p = pb.start();
                if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return new String[]{cmd};
                }
            } catch (Exception ignored) {}
            // Fallback: try where on Windows
            try {
                ProcessBuilder pb = new ProcessBuilder("where", cmd);
                Process p = pb.start();
                if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return new String[]{cmd};
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void changeOutputDir(String newPath) {
        if (newPath.isEmpty()) return;
        Path newDir = Path.of(newPath);
        try {
            Files.createDirectories(newDir);
            outputDir = newDir;
            config.setOutputDir(newPath);
            pathField.setText(newPath);
            statusLabel.setText("Output dir: " + newDir.getFileName());

            // Re-save all existing files to new directory
            List<GeneratedTool> tools = agent.getGeneratedTools();
            for (GeneratedTool t : tools) {
                autoSaveFile(t);
            }
            if (!tools.isEmpty()) {
                statusLabel.setText("Output dir changed, " + tools.size() + " files copied");
            }
        } catch (IOException e) {
            statusLabel.setText("Invalid directory: " + e.getMessage());
            pathField.setText(outputDir.toString());
        }
    }

    private void browseOutputDir() {
        JFileChooser fc = new JFileChooser(outputDir.toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Choose output directory for generated files");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            changeOutputDir(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void openOutputDir() {
        try {
            Desktop.getDesktop().open(outputDir.toFile());
        } catch (IOException e) {
            statusLabel.setText("Can't open directory: " + e.getMessage());
        }
    }

    // ========== Progress ==========

    private void startProgress(String message) {
        elapsedSeconds = 0;
        statusLabel.setText(message + " (0s)");
        progressBar.setVisible(true);
        progressTimer = new Timer(1000, e -> {
            elapsedSeconds++;
            statusLabel.setText(message + " (" + elapsedSeconds + "s)");
        });
        progressTimer.start();
    }

    private void stopProgress(String message) {
        if (progressTimer != null) {
            progressTimer.stop();
            progressTimer = null;
        }
        progressBar.setVisible(false);
        statusLabel.setText(message + " (" + elapsedSeconds + "s)");
    }

    // ========== Helpers ==========

    private static String fileExtension(String language) {
        return switch (language) {
            case "python" -> ".py";
            case "javascript" -> ".js";
            case "java" -> ".java";
            case "html" -> ".html";
            case "md" -> ".md";
            case "csv" -> ".csv";
            case "json" -> ".json";
            case "xml" -> ".xml";
            case "txt" -> ".txt";
            default -> "." + language;
        };
    }

    private static JButton createToolbarButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));
        btn.setForeground(TEXT_MUTED);
        btn.setBackground(BG_TOOLBAR);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { btn.setForeground(TEXT_DEFAULT); }
            @Override public void mouseExited(MouseEvent e) { btn.setForeground(TEXT_MUTED); }
        });
        return btn;
    }
}
