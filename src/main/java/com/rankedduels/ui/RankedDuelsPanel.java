package com.rankedduels.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rankedduels.DuelState;
import com.rankedduels.RankedDuelsConfig;
import com.rankedduels.api.ApiClient;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Ranked Duels side panel. Fixed to the plugin-panel width (no horizontal
 * scrolling), vertical scroll only when needed, contextual Cancel button
 * while a duel is pending or live.
 */
@Singleton
public class RankedDuelsPanel extends PluginPanel
{
    private static final Color GOLD = new Color(0xC9, 0xA2, 0x27);
    private static final Color HITSPLAT = new Color(0xC0, 0x28, 0x1C);
    private static final Color WIN = new Color(0x6F, 0xA8, 0x5E);
    private static final Color LOSS = new Color(0xC7, 0x5B, 0x52);
    private static final Color DIM = new Color(0xA9, 0x9C, 0x78);
    private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
    private static final Color CARD_EDGE = new Color(0x33, 0x2B, 0x1F);

    private final ApiClient api;
    private final RankedDuelsConfig config;
    private final ScheduledExecutorService executor;

    private final JPanel content = new JPanel();
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton cancelButton = new JButton("Cancel duel");

    private Supplier<DuelState> duelStateSupplier = () -> DuelState.IDLE;
    private Runnable cancelAction = () -> {};

    @Inject
    public RankedDuelsPanel(ApiClient api, RankedDuelsConfig config, ScheduledExecutorService executor)
    {
        super(false); // manage our own borders
        this.api = api;
        this.config = config;
        this.executor = executor;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // ---- Header: title + refresh ----
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);
        header.setBorder(new EmptyBorder(0, 0, 8, 0));

        JLabel title = new JLabel("Ranked Duels");
        title.setFont(FontManager.getRunescapeBoldFont().deriveFont(17f));
        title.setForeground(GOLD);
        header.add(title, BorderLayout.WEST);

        JButton refresh = new JButton("\u21bb");
        refresh.setToolTipText("Refresh");
        refresh.setFocusPainted(false);
        refresh.setMargin(new Insets(2, 8, 2, 8));
        refresh.addActionListener(e -> refreshAsync());
        header.add(refresh, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // ---- Content: north-anchored column inside a v-only scroll ----
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel anchor = new JPanel(new BorderLayout());
        anchor.setBackground(ColorScheme.DARK_GRAY_COLOR);
        anchor.add(content, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(anchor);
        scroll.setBorder(null);
        scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(7, 0));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        // ---- Footer: cancel (contextual) + status line ----
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
        footer.setBorder(new EmptyBorder(8, 0, 0, 0));

        cancelButton.setBackground(new Color(0x5A, 0x1C, 0x16));
        cancelButton.setForeground(Color.WHITE);
        cancelButton.setFocusPainted(false);
        cancelButton.setVisible(false);
        cancelButton.addActionListener(e -> {
            cancelAction.run();
            // Give the state machine a moment, then re-check visibility.
            executor.schedule(
                () -> SwingUtilities.invokeLater(this::updateCancelButton),
                600, java.util.concurrent.TimeUnit.MILLISECONDS);
        });
        footer.add(cancelButton, BorderLayout.NORTH);

        statusLabel.setForeground(DIM);
        statusLabel.setFont(FontManager.getRunescapeSmallFont());
        footer.add(statusLabel, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        showNotLinked();
    }

    public void setDuelStateSupplier(Supplier<DuelState> supplier)
    {
        this.duelStateSupplier = supplier;
    }

    public void setCancelAction(Runnable action)
    {
        this.cancelAction = action;
    }

    private void updateCancelButton()
    {
        DuelState state = duelStateSupplier.get();
        boolean active = state != DuelState.IDLE;
        cancelButton.setVisible(active);
        switch (state)
        {
            case CHALLENGE_SENT:
                cancelButton.setText("Cancel challenge");
                break;
            case CHALLENGE_RECEIVED:
                cancelButton.setText("Decline challenge");
                break;
            case PENDING_FIGHT:
            case FIGHTING:
                cancelButton.setText("Void duel");
                break;
            default:
                break;
        }
        revalidate();
        repaint();
    }

    /** Call when the panel is opened or a duel state changes. Any thread. */
    public void refreshAsync()
    {
        SwingUtilities.invokeLater(this::updateCancelButton);
        if (!api.isLinked())
        {
            SwingUtilities.invokeLater(this::showNotLinked);
            return;
        }
        SwingUtilities.invokeLater(() -> statusLabel.setText("Loading..."));
        executor.execute(() -> {
            JsonObject me = api.getMe();
            SwingUtilities.invokeLater(() -> {
                if (me == null)
                {
                    statusLabel.setText("Could not reach the ladder server.");
                    return;
                }
                render(me);
                statusLabel.setText(" ");
            });
        });
    }

    private void showNotLinked()
    {
        content.removeAll();
        JTextArea msg = new JTextArea(
            "Log into OSRS and you'll be placed on the ladder automatically.\n\n" +
            "Optional: register at rankedduel.com and paste your link code in " +
            "the plugin settings to claim your profile.");
        msg.setEditable(false);
        msg.setLineWrap(true);
        msg.setWrapStyleWord(true);
        msg.setBackground(ColorScheme.DARK_GRAY_COLOR);
        msg.setForeground(Color.WHITE);
        msg.setFont(FontManager.getRunescapeSmallFont());
        msg.setBorder(new EmptyBorder(8, 2, 8, 2));
        content.add(msg);
        content.revalidate();
        content.repaint();
        statusLabel.setText(" ");
    }

    private void render(JsonObject me)
    {
        content.removeAll();

        // ---- Identity card: name, rating, tier / rank / peak ----
        JPanel identity = card();
        identity.add(centered(label(me.get("name").getAsString(),
            FontManager.getRunescapeBoldFont().deriveFont(15f), Color.WHITE)));
        identity.add(Box.createVerticalStrut(2));
        identity.add(centered(label(String.valueOf(me.get("rating").getAsInt()),
            FontManager.getRunescapeBoldFont().deriveFont(26f), HITSPLAT)));

        String tierName = me.has("tier") && me.get("tier").isJsonObject()
            ? me.getAsJsonObject("tier").get("name").getAsString() : null;
        if (tierName != null)
        {
            identity.add(centered(label(tierName.toUpperCase(),
                FontManager.getRunescapeSmallFont(), GOLD)));
        }
        identity.add(Box.createVerticalStrut(4));
        // Split sub info over lines: avoids the old one-line overflow
        identity.add(centered(label("Rank #" + me.get("global_rank").getAsInt()
                + "   Peak " + me.get("peak_rating").getAsInt(),
            FontManager.getRunescapeSmallFont(), DIM)));
        if (me.get("provisional").getAsBoolean())
        {
            identity.add(centered(label("provisional rating",
                FontManager.getRunescapeSmallFont(), DIM)));
        }
        content.add(identity);
        content.add(Box.createVerticalStrut(8));

        // ---- Duels record ----
        content.add(statRow(new String[][] {
            {"Wins", String.valueOf(me.get("wins").getAsInt())},
            {"Losses", String.valueOf(me.get("losses").getAsInt())},
        }, new Color[] {WIN, LOSS}));
        content.add(Box.createVerticalStrut(8));

        // ---- PK record ----
        if (me.has("pk_kills"))
        {
            int kills = me.get("pk_kills").getAsInt();
            int deaths = me.has("pk_deaths") ? me.get("pk_deaths").getAsInt() : 0;
            String kd = deaths > 0 ? String.format("%.2f", (double) kills / deaths) : String.valueOf(kills);
            content.add(statRow(new String[][] {
                {"PK Kills", String.valueOf(kills)},
                {"Deaths", String.valueOf(deaths)},
                {"K/D", kd},
            }, new Color[] {WIN, LOSS, GOLD}));
            content.add(Box.createVerticalStrut(12));
        }

        // ---- Recent duels ----
        JLabel recentTitle = label("RECENT DUELS", FontManager.getRunescapeSmallFont(), GOLD);
        recentTitle.setBorder(new EmptyBorder(0, 2, 6, 0));
        recentTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(recentTitle);

        JsonArray recent = me.getAsJsonArray("recent_duels");
        if (recent == null || recent.size() == 0)
        {
            JLabel none = label("No finished duels yet.", FontManager.getRunescapeSmallFont(), DIM);
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(none);
        }
        else
        {
            for (var el : recent)
            {
                JsonObject d = el.getAsJsonObject();
                boolean won = d.get("won").getAsBoolean();
                double delta = d.get("rating_change").getAsDouble();

                JPanel row = new JPanel(new BorderLayout(6, 0));
                row.setBackground(CARD);
                row.setBorder(new CompoundBorder(
                    new MatteBorder(0, 2, 0, 0, won ? WIN : LOSS),
                    new EmptyBorder(5, 8, 5, 8)));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                row.setAlignmentX(Component.LEFT_ALIGNMENT);

                JLabel opp = label(d.get("opponent").getAsString(),
                    FontManager.getRunescapeSmallFont(), Color.WHITE);
                row.add(opp, BorderLayout.CENTER);

                JLabel deltaLabel = label((delta >= 0 ? "+" : "") + String.format("%.1f", delta),
                    FontManager.getRunescapeSmallFont(), delta >= 0 ? WIN : LOSS);
                row.add(deltaLabel, BorderLayout.EAST);

                content.add(row);
                content.add(Box.createVerticalStrut(4));
            }
        }

        updateCancelButton();
        content.revalidate();
        content.repaint();
    }

    // ------------------------------------------------------------------
    // Small UI helpers
    // ------------------------------------------------------------------
    private JPanel card()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(CARD);
        panel.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, CARD_EDGE),
            new EmptyBorder(10, 10, 10, 10)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private JPanel statRow(String[][] labelValues, Color[] colors)
    {
        JPanel row = new JPanel(new GridLayout(1, labelValues.length, 6, 0));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (int i = 0; i < labelValues.length; i++)
        {
            JPanel cell = new JPanel();
            cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
            cell.setBackground(CARD);
            cell.setBorder(new CompoundBorder(
                new MatteBorder(1, 1, 1, 1, CARD_EDGE),
                new EmptyBorder(8, 4, 8, 4)));
            cell.add(centered(label(labelValues[i][1],
                FontManager.getRunescapeBoldFont().deriveFont(16f), colors[i])));
            cell.add(centered(label(labelValues[i][0],
                FontManager.getRunescapeSmallFont(), DIM)));
            row.add(cell);
        }
        return row;
    }

    private JLabel label(String text, Font font, Color color)
    {
        JLabel l = new JLabel(text);
        l.setFont(font);
        l.setForeground(color);
        return l;
    }

    private Component centered(JLabel l)
    {
        l.setAlignmentX(Component.CENTER_ALIGNMENT);
        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.X_AXIS));
        wrap.setBackground(CARD);
        wrap.add(Box.createHorizontalGlue());
        wrap.add(l);
        wrap.add(Box.createHorizontalGlue());
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        return wrap;
    }
}
