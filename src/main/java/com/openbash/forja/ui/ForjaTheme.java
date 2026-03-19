package com.openbash.forja.ui;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Centralized dark theme for all Forja tabs.
 * Matches the Agent tab's design language.
 */
public final class ForjaTheme {

    private ForjaTheme() {}

    // --- Backgrounds ---
    public static final Color BG_DARK       = new Color(30, 30, 30);
    public static final Color BG_SIDEBAR    = new Color(25, 25, 25);
    public static final Color BG_INPUT      = new Color(45, 45, 45);
    public static final Color BG_TOOLBAR    = new Color(38, 38, 38);
    public static final Color BG_CODE       = new Color(20, 20, 20);
    public static final Color BG_TABLE      = new Color(28, 28, 28);
    public static final Color BG_TABLE_ALT  = new Color(34, 34, 34);
    public static final Color BG_SELECTION  = new Color(50, 70, 90);

    // --- Text ---
    public static final Color TEXT_DEFAULT   = new Color(210, 210, 210);
    public static final Color TEXT_MUTED     = new Color(120, 120, 120);
    public static final Color TEXT_CODE      = new Color(190, 220, 190);
    public static final Color TEXT_LABEL     = new Color(170, 170, 170);
    public static final Color TEXT_BRIGHT    = new Color(240, 240, 240);

    // --- Accents ---
    public static final Color ACCENT_ORANGE  = new Color(232, 162, 56);   // Burp orange
    public static final Color ACCENT_GREEN   = new Color(80, 200, 120);
    public static final Color ACCENT_BLUE    = new Color(100, 160, 255);
    public static final Color ACCENT_RED     = new Color(255, 110, 110);

    // --- Borders ---
    public static final Color BORDER_COLOR   = new Color(55, 55, 55);
    public static final Color BORDER_LIGHT   = new Color(65, 65, 65);

    // --- Severity ---
    public static final Color SEV_CRITICAL   = new Color(255, 70, 70);
    public static final Color SEV_HIGH       = new Color(255, 130, 50);
    public static final Color SEV_MEDIUM     = new Color(255, 200, 50);
    public static final Color SEV_LOW        = new Color(100, 180, 255);
    public static final Color SEV_INFO       = new Color(150, 150, 150);

    // --- Fonts ---
    public static final Font FONT_MONO       = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    public static final Font FONT_MONO_SMALL = new Font(Font.MONOSPACED, Font.PLAIN, 11);
    public static final Font FONT_UI         = new Font(Font.DIALOG, Font.PLAIN, 12);
    public static final Font FONT_UI_SMALL   = new Font(Font.DIALOG, Font.PLAIN, 11);
    public static final Font FONT_UI_BOLD    = new Font(Font.DIALOG, Font.BOLD, 12);
    public static final Font FONT_TITLE      = new Font(Font.DIALOG, Font.BOLD, 11);
    public static final Font FONT_CODE       = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    // ========== Component Factories ==========

    /** Style an entire panel tree with dark background. */
    public static void applyTo(JPanel panel) {
        panel.setBackground(BG_DARK);
        panel.setForeground(TEXT_DEFAULT);
    }

    /** Create a styled toolbar panel. */
    public static JPanel toolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.setBackground(BG_TOOLBAR);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));
        return bar;
    }

    /** Create a styled toolbar with BorderLayout. */
    public static JPanel toolbarBorder() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG_TOOLBAR);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));
        return bar;
    }

    /** Create a primary action button (colored background). */
    public static JButton primaryButton(String text, Color color) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_UI_BOLD);
        btn.setForeground(Color.WHITE);
        btn.setBackground(color);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setMargin(new Insets(4, 14, 4, 14));
        return btn;
    }

    /** Create a secondary/ghost button. */
    public static JButton ghostButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_UI_SMALL);
        btn.setForeground(TEXT_DEFAULT);
        btn.setBackground(BG_INPUT);
        btn.setFocusPainted(false);
        btn.setBorderPainted(true);
        btn.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setMargin(new Insets(3, 10, 3, 10));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { btn.setBackground(new Color(55, 55, 60)); }
            @Override public void mouseExited(MouseEvent e) { btn.setBackground(BG_INPUT); }
        });
        return btn;
    }

    /** Create a styled label. */
    public static JLabel label(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(FONT_UI);
        lbl.setForeground(TEXT_LABEL);
        return lbl;
    }

    /** Create a muted status label. */
    public static JLabel statusLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(FONT_MONO_SMALL);
        lbl.setForeground(TEXT_MUTED);
        return lbl;
    }

    /** Create a section title label. */
    public static JLabel titleLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(FONT_TITLE);
        lbl.setForeground(ACCENT_ORANGE);
        return lbl;
    }

    /** Style a text field. */
    public static void styleTextField(JTextField field) {
        field.setFont(FONT_MONO);
        field.setBackground(BG_INPUT);
        field.setForeground(TEXT_DEFAULT);
        field.setCaretColor(TEXT_DEFAULT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    }

    /** Style a password field. */
    public static void stylePasswordField(JPasswordField field) {
        field.setFont(FONT_MONO);
        field.setBackground(BG_INPUT);
        field.setForeground(TEXT_DEFAULT);
        field.setCaretColor(TEXT_DEFAULT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    }

    /** Style a text area (read-only detail view). */
    public static void styleTextArea(JTextArea area) {
        area.setFont(FONT_MONO);
        area.setBackground(BG_CODE);
        area.setForeground(TEXT_CODE);
        area.setCaretColor(ACCENT_GREEN);
        area.setSelectionColor(BG_SELECTION);
        area.setSelectedTextColor(TEXT_BRIGHT);
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    }

    /** Style a combo box. */
    public static void styleComboBox(JComboBox<?> combo) {
        combo.setFont(FONT_UI);
        combo.setBackground(BG_INPUT);
        combo.setForeground(TEXT_DEFAULT);
    }

    /** Style a JTable with dark theme + alternating rows. */
    public static void styleTable(JTable table) {
        table.setBackground(BG_TABLE);
        table.setForeground(TEXT_DEFAULT);
        table.setSelectionBackground(BG_SELECTION);
        table.setSelectionForeground(TEXT_BRIGHT);
        table.setGridColor(BORDER_COLOR);
        table.setFont(FONT_MONO_SMALL);
        table.setRowHeight(24);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 1));

        // Alternating row colors
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (!sel) {
                    c.setBackground(row % 2 == 0 ? BG_TABLE : BG_TABLE_ALT);
                    c.setForeground(TEXT_DEFAULT);
                }
                return c;
            }
        });

        // Header
        JTableHeader header = table.getTableHeader();
        header.setBackground(BG_TOOLBAR);
        header.setForeground(TEXT_LABEL);
        header.setFont(FONT_TITLE);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR));
    }

    /** Style a JSplitPane. */
    public static void styleSplitPane(JSplitPane split) {
        split.setBackground(BG_DARK);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(4);
        split.setContinuousLayout(true);
    }

    /** Style a JScrollPane viewport. */
    public static void styleScrollPane(JScrollPane scroll) {
        scroll.getViewport().setBackground(BG_DARK);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
    }

    /** Style a JList. */
    public static void styleList(JList<?> list) {
        list.setBackground(BG_SIDEBAR);
        list.setForeground(TEXT_DEFAULT);
        list.setSelectionBackground(BG_SELECTION);
        list.setSelectionForeground(TEXT_BRIGHT);
        list.setFont(FONT_UI_SMALL);
    }

    /** Create a titled section border. */
    public static Border sectionBorder(String title) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(BORDER_COLOR),
                        title,
                        javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                        javax.swing.border.TitledBorder.DEFAULT_POSITION,
                        FONT_TITLE,
                        ACCENT_ORANGE),
                BorderFactory.createEmptyBorder(4, 6, 4, 6));
    }

    /** Create a progress bar styled for the theme. */
    public static JProgressBar progressBar() {
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setPreferredSize(new Dimension(120, 14));
        bar.setBackground(BG_INPUT);
        bar.setForeground(ACCENT_ORANGE);
        bar.setBorderPainted(false);
        bar.setVisible(false);
        return bar;
    }
}
