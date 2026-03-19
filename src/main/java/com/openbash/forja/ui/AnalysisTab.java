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

    public AnalysisTab(AppModel appModel, ConfigManager config, LLMProviderFactory providerFactory) {
        this.appModel = appModel;
        this.config = config;
        this.providerFactory = providerFactory;

        setLayout(new BorderLayout());

        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        analyzeBtn = new JButton("Analyze Traffic");
        analyzeBtn.addActionListener(e -> runAnalysis());
        statusLabel = new JLabel("Ready");
        toolbar.add(analyzeBtn);
        toolbar.add(Box.createHorizontalStrut(10));
        toolbar.add(statusLabel);
        add(toolbar, BorderLayout.NORTH);

        // Split pane
        tableModel = new FindingsTableModel();
        table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);

        // Severity color renderer
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (value instanceof String s) {
                    Severity sev = Severity.fromString(s);
                    c.setForeground(sev.getColor());
                }
                return c;
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetail();
        });

        detailArea = new JTextArea();
        detailArea.setEditable(false);
        detailArea.setFont(UIConstants.MONO_FONT);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), new JScrollPane(detailArea));
        split.setDividerLocation(250);
        add(split, BorderLayout.CENTER);
    }

    public List<Finding> getFindings() {
        return tableModel.getFindings();
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

        new SwingWorker<List<Finding>, Void>() {
            @Override
            protected List<Finding> doInBackground() throws Exception {
                return analyzer.analyze(appModel);
            }

            @Override
            protected void done() {
                analyzeBtn.setEnabled(true);
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
