package com.rankedduels.ui;

import com.rankedduels.DuelState;
import com.rankedduels.RankedDuelsPlugin;
import com.rankedduels.api.ChallengeInfo;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * Accept / Decline prompt for incoming challenges.
 *
 * Clicks: the mouse listener is registered for the plugin's whole lifetime
 * (registerClicks/unregisterClicks from the plugin), not per-frame - the
 * old per-render registration was unreliable. Hotkeys (default Y / N)
 * provide a second, always-working path and are shown on the buttons.
 */
public class ChallengePromptOverlay extends Overlay
{
    private static final int WIDTH = 280;
    private static final int HEIGHT = 122;

    private static final Color BG = new Color(16, 13, 10, 240);
    private static final Color GOLD = new Color(0xC9, 0xA2, 0x27);
    private static final Color PARCH = new Color(0xE9, 0xDC, 0xB8);
    private static final Color WIN = new Color(0x6F, 0xA8, 0x5E);
    private static final Color LOSS = new Color(0xC7, 0x5B, 0x52);
    private static final Color BTN_ACCEPT = new Color(0x1F, 0x6B, 0x2A);
    private static final Color BTN_DECLINE = new Color(0x7A, 0x1F, 0x1F);

    private final RankedDuelsPlugin plugin;
    private final MouseManager mouseManager;

    private volatile Rectangle acceptBounds = new Rectangle();
    private volatile Rectangle declineBounds = new Rectangle();

    private final MouseAdapter mouseListener = new MouseAdapter()
    {
        @Override
        public MouseEvent mousePressed(MouseEvent e)
        {
            if (plugin.getState() != DuelState.CHALLENGE_RECEIVED || e.getButton() != MouseEvent.BUTTON1)
            {
                return e;
            }
            if (acceptBounds.contains(e.getPoint()))
            {
                plugin.acceptChallenge();
                e.consume();
            }
            else if (declineBounds.contains(e.getPoint()))
            {
                plugin.declineChallenge();
                e.consume();
            }
            return e;
        }
    };

    @Inject
    public ChallengePromptOverlay(RankedDuelsPlugin plugin, MouseManager mouseManager)
    {
        this.plugin = plugin;
        this.mouseManager = mouseManager;
        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    /** Called once from the plugin's startUp. */
    public void registerClicks()
    {
        mouseManager.registerMouseListener(mouseListener);
    }

    /** Called once from the plugin's shutDown. */
    public void unregisterClicks()
    {
        mouseManager.unregisterMouseListener(mouseListener);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        ChallengeInfo ch = plugin.getPendingChallenge();
        if (plugin.getState() != DuelState.CHALLENGE_RECEIVED || ch == null)
        {
            acceptBounds = new Rectangle();
            declineBounds = new Rectangle();
            return null;
        }

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Panel
        RoundRectangle2D panel = new RoundRectangle2D.Float(0, 0, WIDTH, HEIGHT, 10, 10);
        g.setColor(BG);
        g.fill(panel);
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(panel);

        // Title
        g.setFont(new Font(Font.SERIF, Font.BOLD, 15));
        g.setColor(GOLD);
        g.drawString("Ranked Duel Challenge", 14, 24);

        // Who + rating
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.setColor(PARCH);
        String line = ch.getChallengerName()
            + (ch.getChallengerRating() > 0 ? "  (" + ch.getChallengerRating() + ")" : "");
        g.drawString(line, 14, 46);

        // Stakes
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        g.setColor(WIN);
        String winText = "Win " + RankedDuelsPlugin.formatDelta(ch.getWinDelta());
        g.drawString(winText, 14, 66);
        g.setColor(LOSS);
        g.drawString("Lose " + RankedDuelsPlugin.formatDelta(ch.getLossDelta()),
            22 + g.getFontMetrics().stringWidth(winText), 66);

        // Buttons with hotkey hints
        Rectangle a = drawButton(g, 14, 80, 120, 28, "Accept  [Y]", BTN_ACCEPT);
        Rectangle d = drawButton(g, 146, 80, 120, 28, "Decline  [N]", BTN_DECLINE);

        // Convert to canvas coordinates for hit testing
        Rectangle bounds = getBounds();
        if (bounds != null)
        {
            a.translate(bounds.x, bounds.y);
            d.translate(bounds.x, bounds.y);
        }
        acceptBounds = a;
        declineBounds = d;

        return new Dimension(WIDTH, HEIGHT);
    }

    private Rectangle drawButton(Graphics2D g, int x, int y, int w, int h, String label, Color color)
    {
        g.setColor(color);
        g.fillRoundRect(x, y, w, h, 6, 6);
        g.setColor(new Color(255, 255, 255, 40));
        g.drawRoundRect(x, y, w, h, 6, 6);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, x + (w - fm.stringWidth(label)) / 2, y + (h + fm.getAscent() - fm.getDescent()) / 2);
        return new Rectangle(x, y, w, h);
    }
}
