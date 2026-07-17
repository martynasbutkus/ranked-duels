package com.rankedduels.ui;

import com.rankedduels.DuelState;
import com.rankedduels.RankedDuelsPlugin;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;

/**
 * Big "RANKED [star] DUELS" banner drawn top-center while a ranked duel is
 * live: gold serif lettering with a shadow, red spark between the words,
 * opponent name and fight timer underneath.
 */
public class FightBannerOverlay extends Overlay
{
    private static final Color GOLD = new Color(0xE0, 0xCE, 0xA4);
    private static final Color GOLD_DIM = new Color(0xA9, 0x9C, 0x78);
    private static final Color RED = new Color(0xC0, 0x28, 0x1C);
    private static final Color SHADOW = new Color(0, 0, 0, 190);

    private static final Font TITLE_FONT = new Font(Font.SERIF, Font.BOLD, 26);
    private static final Font SUB_FONT = new Font(Font.SERIF, Font.PLAIN, 15);

    private final RankedDuelsPlugin plugin;

    @Inject
    public FightBannerOverlay(RankedDuelsPlugin plugin)
    {
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        DuelState state = plugin.getState();
        if (state != DuelState.FIGHTING && state != DuelState.PENDING_FIGHT)
        {
            return null;
        }

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        String left = "RANKED";
        String right = "DUELS";
        String stakes = (plugin.getWinDelta() != 0 || plugin.getLossDelta() != 0)
            ? "  \u2022  " + RankedDuelsPlugin.formatDelta(plugin.getWinDelta())
                + " / " + RankedDuelsPlugin.formatDelta(plugin.getLossDelta())
            : "";
        String sub;
        if (state == DuelState.FIGHTING)
        {
            long secs = plugin.getFightStartedAt() != null
                ? Duration.between(plugin.getFightStartedAt(), Instant.now()).getSeconds()
                : 0;
            sub = "vs " + plugin.getOpponentName() + "  \u2022  " + secs + "s" + stakes;
        }
        else
        {
            sub = "vs " + plugin.getOpponentName() + stakes + "  \u2022  first hit starts the duel";
        }

        g.setFont(TITLE_FONT);
        FontMetrics fmTitle = g.getFontMetrics();
        int starGap = 14;
        int starSize = 12;
        int titleWidth = fmTitle.stringWidth(left) + starGap * 2 + starSize + fmTitle.stringWidth(right);

        g.setFont(SUB_FONT);
        FontMetrics fmSub = g.getFontMetrics();
        int subWidth = fmSub.stringWidth(sub);

        int width = Math.max(titleWidth, subWidth) + 24;
        int titleY = fmTitle.getAscent() + 6;
        int subY = titleY + fmSub.getHeight() + 8;
        int height = subY + 8;

        // Title with letter-spacing feel via tracking-lite: draw as two words
        int x = (width - titleWidth) / 2;
        g.setFont(TITLE_FONT);
        drawShadowed(g, left, x, titleY, GOLD);
        int starX = x + fmTitle.stringWidth(left) + starGap;
        int starY = titleY - fmTitle.getAscent() / 2;
        drawSpark(g, starX + starSize / 2, starY, starSize);
        drawShadowed(g, right, starX + starSize + starGap, titleY, GOLD);

        // Subtitle
        g.setFont(SUB_FONT);
        drawShadowed(g, sub, (width - subWidth) / 2, subY, GOLD_DIM);

        return new Dimension(width, height);
    }

    private void drawShadowed(Graphics2D g, String text, int x, int y, Color color)
    {
        g.setColor(SHADOW);
        g.drawString(text, x + 1, y + 2);
        g.setColor(color);
        g.drawString(text, x, y);
    }

    /** Four-pointed spark, like the one in the logo. */
    private void drawSpark(Graphics2D g, int cx, int cy, int size)
    {
        int r = size / 2;
        int rIn = Math.max(2, size / 6);
        Polygon p = new Polygon();
        p.addPoint(cx, cy - r);
        p.addPoint(cx + rIn, cy - rIn);
        p.addPoint(cx + r, cy);
        p.addPoint(cx + rIn, cy + rIn);
        p.addPoint(cx, cy + r);
        p.addPoint(cx - rIn, cy + rIn);
        p.addPoint(cx - r, cy);
        p.addPoint(cx - rIn, cy - rIn);
        g.setColor(SHADOW);
        g.translate(1, 2);
        g.fillPolygon(p);
        g.translate(-1, -2);
        g.setColor(RED);
        g.fillPolygon(p);
    }
}
