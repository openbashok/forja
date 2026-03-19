package com.openbash.forja.ui;

import com.openbash.forja.traffic.AppModel;
import com.openbash.forja.traffic.EndpointInfo;
import com.openbash.forja.traffic.TrafficCollector;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class TrafficTab extends JPanel {

    private final AppModel appModel;
    private final TrafficCollector collector;
    private final EndpointTableModel tableModel;
    private final JTable table;
    private final JTextArea detailArea;
    private final JLabel statsLabel;

    public TrafficTab(AppModel appModel, TrafficCollector collector) {
        this.appModel = appModel;
        this.collector = collector;
        setLayout(new BorderLayout());

        // Toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statsLabel = new JLabel("Endpoints: 0");

        JButton importBtn = new JButton("Import Proxy History");
        importBtn.setToolTipText("Import already captured traffic from Burp's Proxy History (in-scope only)");
        importBtn.addActionListener(e -> importHistory(importBtn));

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            appModel.clear();
            refresh();
        });
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> refresh());
        toolbar.add(statsLabel);
        toolbar.add(Box.createHorizontalStrut(20));
        toolbar.add(importBtn);
        toolbar.add(refreshBtn);
        toolbar.add(clearBtn);
        add(toolbar, BorderLayout.NORTH);

        // Split pane
        tableModel = new EndpointTableModel();
        table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetail();
        });

        detailArea = new JTextArea();
        detailArea.setEditable(false);
        detailArea.setFont(UIConstants.MONO_FONT);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), new JScrollPane(detailArea));
        split.setDividerLocation(300);
        add(split, BorderLayout.CENTER);

        // Auto-refresh timer (1 second)
        new Timer(1000, e -> refresh()).start();
    }

    private void importHistory(JButton importBtn) {
        importBtn.setEnabled(false);
        statsLabel.setText("Importing proxy history...");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() {
                return collector.importProxyHistory();
            }

            @Override
            protected void done() {
                importBtn.setEnabled(true);
                try {
                    int count = get();
                    statsLabel.setText("Imported " + count + " items from proxy history.");
                    refresh();
                } catch (Exception e) {
                    statsLabel.setText("Import failed: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void refresh() {
        tableModel.refresh();
        statsLabel.setText("Endpoints: " + appModel.getEndpointCount()
                + " | Auth patterns: " + appModel.getAuthPatterns().size()
                + " | Tech: " + String.join(", ", appModel.getTechStack()));
    }

    private void showDetail() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        EndpointInfo ep = tableModel.getEndpoint(modelRow);
        if (ep == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("Method: ").append(ep.getMethod()).append("\n");
        sb.append("Path: ").append(ep.getPath()).append("\n");
        sb.append("Pattern: ").append(ep.getPathPattern()).append("\n");
        sb.append("Seen: ").append(ep.getTimesSeen()).append(" times\n");
        sb.append("Response codes: ").append(ep.getResponseCodes()).append("\n");
        sb.append("Query params: ").append(ep.getQueryParams()).append("\n");
        sb.append("Content-Type: ").append(ep.getContentType()).append("\n");
        sb.append("Auth: ").append(ep.getAuthInfo() != null ? ep.getAuthInfo() : "None").append("\n");
        sb.append("\n--- Sample Request ---\n");
        sb.append(ep.getSampleRequest() != null ? ep.getSampleRequest() : "(none)");
        sb.append("\n\n--- Sample Response ---\n");
        sb.append(ep.getSampleResponse() != null ? ep.getSampleResponse() : "(none)");
        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    private class EndpointTableModel extends AbstractTableModel {
        private final String[] COLUMNS = {"Method", "Path Pattern", "Seen", "Auth", "Params", "Codes"};
        private List<EndpointInfo> data = new ArrayList<>();

        void refresh() {
            data = new ArrayList<>(appModel.getEndpoints().values());
            data.sort(Comparator.comparingInt(EndpointInfo::getTimesSeen).reversed());
            fireTableDataChanged();
        }

        EndpointInfo getEndpoint(int row) {
            return row >= 0 && row < data.size() ? data.get(row) : null;
        }

        @Override public int getRowCount() { return data.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            EndpointInfo ep = data.get(row);
            return switch (col) {
                case 0 -> ep.getMethod();
                case 1 -> ep.getPathPattern();
                case 2 -> ep.getTimesSeen();
                case 3 -> ep.getAuthInfo() != null ? ep.getAuthInfo().getType().name() : "-";
                case 4 -> ep.getQueryParams().size();
                case 5 -> ep.getResponseCodes().toString();
                default -> "";
            };
        }
    }
}
