package com.rankedduels.ui;

import com.rankedduels.DuelState;
import com.rankedduels.RankedDuelsPlugin;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;

/** Persistent status panel while a duel is pending or in progress. */
public class DuelStatusOverlay extends OverlayPanel
{
    private final RankedDuelsPlugin plugin;

    @Inject
    public DuelStatusOverlay(RankedDuelsPlugin plugin)
    {
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        DuelState state = plugin.getState();
        // The fight banner covers PENDING_FIGHT and FIGHTING; this panel
        // only handles the waiting-for-answer state.
        if (state != DuelState.CHALLENGE_SENT)
        {
            return null;
        }

        panelComponent.getChildren().add(TitleComponent.builder()
            .text("Ranked Duel")
            .color(new Color(0xB3, 0x00, 0x00))
            .build());

        switch (state)
        {
            case CHALLENGE_SENT:
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Waiting for").right(plugin.getOpponentName()).build());
                if (plugin.getWinDelta() != 0 || plugin.getLossDelta() != 0)
                {
                    panelComponent.getChildren().add(LineComponent.builder()
                        .left("Win " + RankedDuelsPlugin.formatDelta(plugin.getWinDelta()))
                        .leftColor(new Color(0x6F, 0xA8, 0x5E))
                        .right("Lose " + RankedDuelsPlugin.formatDelta(plugin.getLossDelta()))
                        .rightColor(new Color(0xC7, 0x5B, 0x52))
                        .build());
                }
                break;
            case PENDING_FIGHT:
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Confirmed vs").right(plugin.getOpponentName()).build());
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("First hit starts the duel").right("").build());
                break;
            case FIGHTING:
                panelComponent.getChildren().add(LineComponent.builder()
                    .left("Fighting").right(plugin.getOpponentName()).build());
                if (plugin.getFightStartedAt() != null)
                {
                    long secs = Duration.between(plugin.getFightStartedAt(), Instant.now()).getSeconds();
                    panelComponent.getChildren().add(LineComponent.builder()
                        .left("Duration").right(secs + "s").build());
                }
                break;
            default:
                break;
        }
        return super.render(graphics);
    }
}
