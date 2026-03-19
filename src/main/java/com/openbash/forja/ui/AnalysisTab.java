package com.openbash.forja.ui;

import com.openbash.forja.analysis.Finding;
import com.openbash.forja.analysis.SecurityAnalyzer;
import com.openbash.forja.analysis.Severity;
import com.openbash.forja.config.ConfigManager;
import com.openbash.forja.llm.LLMProviderFactory;
import com.openbash.forja.traffic.AppModel;
import com.openbash.forja.util.TokenEstimator;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class AnalysisTab extends JPanel {

    private final AppModel appModel;
    private final ConfigManager config;
    private final LLMProviderFactory providerFactory;
    private final FindingsTableModel tableModel;
    private final JTable table;
    private final JTextArea detailArea;
    private final JLabel statusLabel;
    private final JButton analyzeBtn;
    private final JProgressBar progressBar;

    public AnalysisTab(AppModel appModel, ConfigManager config, LLMProviderFactory providerFactory) {
        this.appModel = appModel;
        this.config = config;
        this.providerFactory = providerFactory;

        setLayout(new BorderLayout());
        ForjaTheme.applyTo(this);

        // Toolbar
        JPanel toolbar = ForjaTheme.toolbarBorder();
        JPanel toolbarLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbarLeft.setBackground(ForjaTheme.BG_TOOLBAR);

        analyzeBtn = ForjaTheme.primaryButton("Analyze Traffic", ForjaTheme.ACCENT_ORANGE);
        analyzeBtn.addActionListener(e -> runAnalysis());

        statusLabel = ForjaTheme.statusLabel("Ready");
        progressBar = ForjaTheme.progressBar();

        toolbarLeft.add(analyzeBtn);
        toolbarLeft.add(Box.createHorizontalStrut(10));
        toolbarLeft.add(statusLabel);
        toolbarLeft.add(progressBar);
        toolbar.add(toolbarLeft, BorderLayout.WEST);
        add(toolbar, BorderLayout.NORTH);

        // Table
        tableModel = new FindingsTableModel();
        table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        ForjaTheme.styleTable(table);

        // Severity color renderer (override the default for column 0)
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (!sel && value instanceof String s) {
                    c.setBackground(row % 2 == 0 ? ForjaTheme.BG_TABLE : ForjaTheme.BG_TABLE_ALT);
                    c.setForeground(severityColor(s));
                }
                return c;
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetail();
        });

        // Detail area
        detailArea = new JTextArea();
        detailArea.setEditable(false);
        ForjaTheme.styleTextArea(detailArea);

        // Split pane
        JScrollPane tableScroll = new JScrollPane(table);
        ForjaTheme.styleScrollPane(tableScroll);
        JScrollPane detailScroll = new JScrollPane(detailArea);
        ForjaTheme.styleScrollPane(detailScroll);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, detailScroll);
        ForjaTheme.styleSplitPane(split);
        split.setDividerLocation(250);
        add(split, BorderLayout.CENTER);
    }

    public List<Finding> getFindings() {
        return tableModel.getFindings();
    }

    private Color severityColor(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> ForjaTheme.SEV_CRITICAL;
            case "HIGH" -> ForjaTheme.SEV_HIGH;
            case "MEDIUM" -> ForjaTheme.SEV_MEDIUM;
            case "LOW" -> ForjaTheme.SEV_LOW;
            default -> ForjaTheme.SEV_INFO;
        };
    }

    private void runAnalysis() {
        if (appModel.getEndpointCount() == 0) {
            statusLabel.setText("No traffic captured yet. Browse the target first.");
            return;
        }

        SecurityAnalyzer analyzer = new SecurityAnalyzer(providerFactory.create(), config);
        int estimatedTokens = analyzer.estimateInputTokens(appModel);
        double estimatedCost = TokenEstimator.estimateCostUsd(estimatedTokens, 4096, config.getModel());
        String costStr = TokenEstimator.formatCost(estimatedCost);

        if (estimatedCost > 0.10) {
            int result = JOptionPane.showConfirmDialog(this,
                    "Estimated cost: " + costStr + " (" + estimatedTokens + " input tokens).\nProceed?",
                    "Cost Confirmation", JOptionPane.YES_NO_OPTION);
            if (result != JOptionPane.YES_OPTION) return;
        }

        analyzeBtn.setEnabled(false);
        statusLabel.setText("Analyzing... (est. " + costStr + ")");
        progressBar.setVisible(true);

        new SwingWorker<List<Finding>, Void>() {
            @Override
            protected List<Finding> doInBackground() throws Exception {
                return analyzer.analyze(appModel);
            }

            @Override
            protected void done() {
                analyzeBtn.setEnabled(true);
                progressBar.setVisible(false);
                try {
                    List<Finding> findings = get();
                    tableModel.setFindings(findings);
                    statusLabel.setText("Found " + findings.size() + " finding(s).");
                } catch (Exception e) {
                    String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    statusLabel.setText("Analysis failed: " + msg);
                }
            }
        }.execute();
    }

    private void showDetail() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        Finding f = tableModel.getFinding(modelRow);
        if (f == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("TITLE: ").append(f.getTitle()).append("\n");
        sb.append("SEVERITY: ").append(f.getSeverity().getDisplayName()).append("\n\n");
        sb.append("DESCRIPTION:\n").append(f.getDescription()).append("\n\n");
        if (!f.getEvidence().isEmpty()) {
            sb.append("EVIDENCE:\n").append(f.getEvidence()).append("\n\n");
        }
        if (!f.getAffectedEndpoints().isEmpty()) {
            sb.append("AFFECTED ENDPOINTS:\n");
            f.getAffectedEndpoints().forEach(e -> sb.append("  - ").append(e).append("\n"));
            sb.append("\n");
        }
        if (!f.getRecommendation().isEmpty()) {
            sb.append("RECOMMENDATION:\n").append(f.getRecommendation()).append("\n\n");
        }
        if (!f.getCwes().isEmpty()) {
            sb.append("CWEs: ").append(String.join(", ", f.getCwes())).append("\n");
        }
        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    private static class FindingsTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Severity", "Title", "Endpoints", "CWEs"};
        private List<Finding> data = new ArrayList<>();

        void setFindings(List<Finding> findings) {
            this.data = new ArrayList<>(findings);
            fireTableDataChanged();
        }

        List<Finding> getFindings() {
            return List.copyOf(data);
        }

        Finding getFinding(int row) {
            return row >= 0 && row < data.size() ? data.get(row) : null;
        }

        @Override public int getRowCount() { return data.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            Finding f = data.get(row);
            return switch (col) {
                case 0 -> f.getSeverity().getDisplayName();
                case 1 -> f.getTitle();
                case 2 -> String.join(", ", f.getAffectedEndpoints());
                case 3 -> String.join(", ", f.getCwes());
                default -> "";
            };
        }
    }
}
